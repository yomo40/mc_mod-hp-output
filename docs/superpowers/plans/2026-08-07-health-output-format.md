# 统一血量输出协议 v2 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将四个广播器/入口改为「单包带条件字段」的统一 JSON 协议(基准字段 `alive`/`health`/`maxHealth`/`percentage` 常驻,事件字段 `damage`/`heal`/`source`/`death` 按需出现),并修复序列化正确性(完整 JSON 转义 + `Locale.ROOT`)与统一 Fabric/Forge 的 `source` 语义,消除 Forge 重复发送。

**Architecture:** 两个加载器各自的 `HealthBroadcaster`(纯 Java,可独立编译测试)输出结构一致;`common` 显式传 `maxHealth`,Forge 从缓存推导(签名差异保留)。Fabric 保留 tick 差值检测;Forge 的 tick 只发周期基准状态,伤害/治疗/死亡由 `Living*` 事件独占。致命一击只发死亡包。

**Tech Stack:** Java 17, Fabric Loom (MC 1.20.1), MinecraftForge (1.20.1), 无测试框架(用临时 harness 断言), Windows PowerShell/Git Bash。

## Global Constraints

- **不改动:** `ModConfig`、`Config`、两个 `ConfigScreen`、`ModMenuIntegration`、`fabric.mod.json`、`mods.toml`、Gradle 配置、`.gitignore`。
- **不改动心跳节奏:** 100-tick 强制基准包、首帧强制基准包、`>0.01f` 变化阈值、默认端口 `39571`。
- **协议为破坏性变更:** 移除 `type` 字段;消费端(README 示例、`test_udp.bat`)随本次一并更新。
- **数值格式:** `%.1f`(health/maxHealth/damage/heal)、`%.3f`(percentage),全部 `String.format(..., Locale.ROOT)`。
- **`source` 语义:** `类型:攻击者`(如 `mob:Zombie`),无攻击者时裸类型(如 `fall`),解析失败 `unknown`。
- **致命一击只发死亡包**,不另发受伤包;单次事件最多一个包。
- **common 与 forge 的 `HealthBroadcaster` 输出结构与转义逻辑必须一致**(两文件镜像实现,不跨文件共享代码)。
- 字段顺序固定:`alive, health, maxHealth, percentage` 后接事件字段(`damage,source` / `heal` / `death,source`)。
- 不在 `github/`(嵌套克隆)、`graphify-out/`、`.claude/` 下做任何改动。
- Java 源码为 UTF-8;`javac` 必须带 `-encoding UTF-8`;测试 harness 保持纯 ASCII。

---

### Task 1: Fabric 协议迁移(common 广播器重写 + Fabric 入口适配)

**Files:**
- Rewrite: `mc-mod/common/src/main/java/com/healthbroadcast/common/HealthBroadcaster.java`
- Rewrite: `mc-mod/fabric/src/main/java/com/healthbroadcast/fabric/HealthBroadcastFabric.java`
- Test: `$env:TEMP\hp-protocol-test\com\healthbroadcast\common\ProtocolCheck.java`(临时,不入库)

**Interfaces:**
- Consumes: 无(首个任务)。
- Produces:
  - `com.healthbroadcast.common.HealthBroadcaster`(单例):
    - `public boolean sendState(float health, float maxHealth)` — 无条件发基准包
    - `public boolean sendDamageEvent(float damage, float healthAfter, float maxHealth, String source)` — 发受伤包
    - `public boolean sendHealEvent(float amount, float healthAfter, float maxHealth)` — 发治疗包
    - `public boolean sendDeathEvent(float maxHealth, String source)` — 发死亡包
    - `public void reset()`, `setPort(int)`, `getPort()`, `setDebugMode(boolean)`, `close()`, `getInstance()`, `DEFAULT_PORT`(均不变)
    - 包级静态构建器(供 harness 断言):`static String buildStateJson/buildDamageJson/buildHealJson/buildDeathJson(float..., String...)`, `static String escapeJson(String)`
  - `com.healthbroadcast.fabric.HealthBroadcastFabric.onClientTick(MinecraftClient)` 调用上述新方法。

- [ ] **Step 1: 写失败测试(临时 harness)**

```powershell
New-Item -ItemType Directory -Force "$env:TEMP\hp-protocol-test\com\healthbroadcast\common" | Out-Null
$content = @'
package com.healthbroadcast.common;

import java.util.Locale;

public class ProtocolCheck {
    private static int failures = 0;

    private static void check(String name, String actual, String expected) {
        if (!expected.equals(actual)) {
            System.out.println("FAIL: " + name);
            System.out.println("  expected: " + expected);
            System.out.println("  actual:   " + actual);
            failures++;
        } else {
            System.out.println("PASS: " + name);
        }
    }

    public static void main(String[] args) {
        check("state",
            HealthBroadcaster.buildStateJson(3.0f, 10.0f),
            "{\"alive\":true,\"health\":3.0,\"maxHealth\":10.0,\"percentage\":0.300}");
        check("damage",
            HealthBroadcaster.buildDamageJson(2.0f, 3.0f, 10.0f, "mob:Zombie"),
            "{\"alive\":true,\"health\":3.0,\"maxHealth\":10.0,\"percentage\":0.300,\"damage\":2.0,\"source\":\"mob:Zombie\"}");
        check("heal",
            HealthBroadcaster.buildHealJson(2.0f, 5.0f, 10.0f),
            "{\"alive\":true,\"health\":5.0,\"maxHealth\":10.0,\"percentage\":0.500,\"heal\":2.0}");
        check("death",
            HealthBroadcaster.buildDeathJson(10.0f, "fall"),
            "{\"alive\":false,\"health\":0.0,\"maxHealth\":10.0,\"percentage\":0.000,\"death\":true,\"source\":\"fall\"}");
        check("escape-backslash",
            HealthBroadcaster.escapeJson("path\\to\\nowhere"),
            "path\\\\to\\\\nowhere");
        check("escape-control",
            HealthBroadcaster.escapeJson("bad\nName\tX"),
            "bad\\nName\\tX");
        check("escape-quote",
            HealthBroadcaster.escapeJson("Bob\"TheBoss\""),
            "Bob\\\"TheBoss\\\"");
        check("escape-unicode-passthrough",
            HealthBroadcaster.escapeJson("\u5f20\u4e09"),
            "\u5f20\u4e09");

        Locale old = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            check("locale-state",
                HealthBroadcaster.buildStateJson(18.0f, 20.0f),
                "{\"alive\":true,\"health\":18.0,\"maxHealth\":20.0,\"percentage\":0.900}");
        } finally {
            Locale.setDefault(old);
        }

        if (failures == 0) {
            System.out.println("ALL PASS (9 checks)");
        } else {
            System.out.println(failures + " FAILURES");
            System.exit(1);
        }
    }
}
'@
[System.IO.File]::WriteAllText("$env:TEMP\hp-protocol-test\com\healthbroadcast\common\ProtocolCheck.java", $content)
```

- [ ] **Step 2: 编译+运行,确认失败**

```powershell
javac -encoding UTF-8 -d "$env:TEMP\hp-protocol-test\out" "F:\mc-mod-hp\mc-mod\common\src\main\java\com\healthbroadcast\common\HealthBroadcaster.java" "$env:TEMP\hp-protocol-test\com\healthbroadcast\common\ProtocolCheck.java"
```

Expected: 编译失败,`cannot find symbol: method buildStateJson(...)`(旧广播器尚无这些方法)。

- [ ] **Step 3: 重写 common `HealthBroadcaster.java`**

将 `mc-mod/common/src/main/java/com/healthbroadcast/common/HealthBroadcaster.java` **整体替换**为以下内容(删除旧的四个内部数据类 `HealthData`/`DamageData`/`DeathData`/`HealData` 与 `sendHealthUpdate`/`forceHealthUpdate`):

```java
package com.healthbroadcast.common;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * UDP 广播器 - 平台无关的共享代码
 * 支持 MC 1.12 ~ 最新版本
 *
 * 协议 v2:统一单包结构。基准字段 (alive/health/maxHealth/percentage) 常驻,
 * 事件字段 (damage/heal/source/death) 仅在触发时出现。消费者通过字段存在性区分事件。
 * 与 Forge 的 HealthBroadcaster 输出结构保持一致。
 */
public class HealthBroadcaster {

    private static final String UDP_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 39571;

    private static HealthBroadcaster instance;

    private DatagramSocket socket;
    private InetAddress address;
    private int port = DEFAULT_PORT;
    private boolean debugMode = true; // 调试模式

    // 状态缓存
    private float lastHealth = -1;
    private float lastMaxHealth = -1;

    private HealthBroadcaster() {
        initSocket();
    }

    private void initSocket() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            socket = new DatagramSocket();
            address = InetAddress.getByName(UDP_HOST);
            log("UDP socket created successfully, target: " + UDP_HOST + ":" + port);
        } catch (Exception e) {
            System.err.println("[HealthBroadcast] Failed to create UDP socket: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static synchronized HealthBroadcaster getInstance() {
        if (instance == null) {
            instance = new HealthBroadcaster();
        }
        return instance;
    }

    public void setDebugMode(boolean debug) {
        this.debugMode = debug;
    }

    private void log(String message) {
        if (debugMode) {
            System.out.println("[HealthBroadcast] " + message);
        }
    }

    public void setPort(int port) {
        if (port > 0 && port <= 65535) {
            if (this.port != port) {
                this.port = port;
                log("UDP port changed to: " + port);
                initSocket();
            }
        }
    }

    public int getPort() {
        return port;
    }

    // ========== 公开发送方法 ==========

    /**
     * 发送基准状态包 (周期/首帧强制快照)。无条件发送。
     * @return true 如果发送了数据
     */
    public boolean sendState(float health, float maxHealth) {
        lastHealth = health;
        lastMaxHealth = maxHealth;
        String json = buildStateJson(health, maxHealth);
        log("Sending state: " + health + "/" + maxHealth);
        return send(json);
    }

    /**
     * 发送受伤事件包
     */
    public boolean sendDamageEvent(float damage, float healthAfter, float maxHealth, String source) {
        lastHealth = healthAfter;
        lastMaxHealth = maxHealth;
        String json = buildDamageJson(damage, healthAfter, maxHealth, source);
        log("Sending damage event: " + damage + " from " + source + ", health now: " + healthAfter);
        return send(json);
    }

    /**
     * 发送治疗事件包
     */
    public boolean sendHealEvent(float amount, float healthAfter, float maxHealth) {
        lastHealth = healthAfter;
        lastMaxHealth = maxHealth;
        String json = buildHealJson(amount, healthAfter, maxHealth);
        log("Sending heal event: +" + amount + ", health now: " + healthAfter);
        return send(json);
    }

    /**
     * 发送死亡事件包
     */
    public boolean sendDeathEvent(float maxHealth, String source) {
        lastHealth = 0;
        lastMaxHealth = maxHealth;
        String json = buildDeathJson(maxHealth, source);
        log("Sending death event, source: " + source);
        return send(json);
    }

    /**
     * 重置状态 (玩家断开/重生时调用)
     */
    public void reset() {
        lastHealth = -1;
        lastMaxHealth = -1;
        log("State reset");
    }

    // ========== JSON 包构建 (包级可见,便于独立测试) ==========

    static String buildStateJson(float health, float maxHealth) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("{\"alive\":").append(health > 0 ? "true" : "false")
          .append(",\"health\":").append(dec(health, 1))
          .append(",\"maxHealth\":").append(dec(maxHealth, 1))
          .append(",\"percentage\":").append(dec(percent(health, maxHealth), 3))
          .append('}');
        return sb.toString();
    }

    static String buildDamageJson(float damage, float healthAfter, float maxHealth, String source) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"alive\":").append(healthAfter > 0 ? "true" : "false")
          .append(",\"health\":").append(dec(healthAfter, 1))
          .append(",\"maxHealth\":").append(dec(maxHealth, 1))
          .append(",\"percentage\":").append(dec(percent(healthAfter, maxHealth), 3))
          .append(",\"damage\":").append(dec(damage, 1))
          .append(",\"source\":\"").append(escapeJson(source != null ? source : "unknown"))
          .append("\"}");
        return sb.toString();
    }

    static String buildHealJson(float amount, float healthAfter, float maxHealth) {
        StringBuilder sb = new StringBuilder(80);
        sb.append("{\"alive\":").append(healthAfter > 0 ? "true" : "false")
          .append(",\"health\":").append(dec(healthAfter, 1))
          .append(",\"maxHealth\":").append(dec(maxHealth, 1))
          .append(",\"percentage\":").append(dec(percent(healthAfter, maxHealth), 3))
          .append(",\"heal\":").append(dec(amount, 1))
          .append('}');
        return sb.toString();
    }

    static String buildDeathJson(float maxHealth, String source) {
        StringBuilder sb = new StringBuilder(80);
        sb.append("{\"alive\":false,\"health\":0.0")
          .append(",\"maxHealth\":").append(dec(maxHealth, 1))
          .append(",\"percentage\":0.000")
          .append(",\"death\":true")
          .append(",\"source\":\"").append(escapeJson(source != null ? source : "unknown"))
          .append("\"}");
        return sb.toString();
    }

    private static float percent(float health, float maxHealth) {
        return maxHealth > 0 ? health / maxHealth : 0;
    }

    private static String dec(float value, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", value);
    }

    /**
     * JSON 字符串转义:引号、反斜杠、控制字符。非 ASCII 原样保留。
     */
    static String escapeJson(String input) {
        StringBuilder sb = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\t': sb.append("\\t"); break;
                case '\r': sb.append("\\r"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private boolean send(String json) {
        if (socket == null || socket.isClosed()) {
            log("Socket is null or closed, attempting to reinitialize...");
            initSocket();
            if (socket == null || socket.isClosed()) {
                log("Failed to reinitialize socket");
                return false;
            }
        }
        try {
            byte[] buffer = json.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address, port);
            socket.send(packet);
            log("Sent UDP packet to " + UDP_HOST + ":" + port + " - " + json);
            return true;
        } catch (Exception e) {
            log("Failed to send UDP packet: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public void close() {
        if (socket != null && !socket.isClosed()) {
            socket.close();
            log("Socket closed");
        }
    }
}
```

- [ ] **Step 4: 编译+运行 harness,确认通过**

```powershell
javac -encoding UTF-8 -d "$env:TEMP\hp-protocol-test\out" "F:\mc-mod-hp\mc-mod\common\src\main\java\com\healthbroadcast\common\HealthBroadcaster.java" "$env:TEMP\hp-protocol-test\com\healthbroadcast\common\ProtocolCheck.java"
java -Dfile.encoding=UTF-8 -cp "$env:TEMP\hp-protocol-test\out" com.healthbroadcast.common.ProtocolCheck
```

Expected: `PASS` ×9,最后一行 `ALL PASS (9 checks)`,退出码 0。

- [ ] **Step 5: 重写 Fabric 入口 `HealthBroadcastFabric.java`**

将 `mc-mod/fabric/src/main/java/com/healthbroadcast/fabric/HealthBroadcastFabric.java` **整体替换**为以下内容(方法改为新广播器 API;致命一击只发死亡包;反射 source 提取逻辑保持):

```java
package com.healthbroadcast.fabric;

import com.healthbroadcast.common.HealthBroadcaster;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Health Broadcast Mod - Fabric 版本
 * 监听血量变化和受伤事件，通过 UDP 广播
 * 支持 MC 1.14 ~ 1.21+ (Fabric 从 1.14 开始支持)
 */
public class HealthBroadcastFabric implements ClientModInitializer {

    public static final String MOD_ID = "hp_output";

    private final HealthBroadcaster broadcaster = HealthBroadcaster.getInstance();
    private float previousHealth = -1;
    private float previousMaxHealth = -1;
    private int tickCounter = 0;
    private static final int FORCE_UPDATE_INTERVAL = 100; // 每100tick强制发送一次

    @Override
    public void onInitializeClient() {
        System.out.println("[HealthBroadcast] Fabric mod initializing...");

        // 加载配置并应用端口
        ModConfig config = ModConfig.getInstance();
        broadcaster.setPort(config.udpPort);

        // 注册 tick 事件监听血量变化
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        System.out.println("[HealthBroadcast] Fabric mod initialized! UDP port: " + config.udpPort);
    }

    private void onClientTick(MinecraftClient client) {
        PlayerEntity player = client.player;

        if (player == null) {
            if (previousHealth != -1) {
                broadcaster.reset();
                previousHealth = -1;
                previousMaxHealth = -1;
                tickCounter = 0;
            }
            return;
        }

        float currentHealth = player.getHealth();
        float maxHealth = player.getMaxHealth();

        tickCounter++;

        // 定期强制发送基准状态（确保数据被发送）
        if (tickCounter >= FORCE_UPDATE_INTERVAL) {
            tickCounter = 0;
            broadcaster.sendState(currentHealth, maxHealth);
            previousHealth = currentHealth;
            previousMaxHealth = maxHealth;
            return;
        }

        // 首次进入游戏时强制发送
        if (previousHealth < 0) {
            broadcaster.sendState(currentHealth, maxHealth);
            previousHealth = currentHealth;
            previousMaxHealth = maxHealth;
            return;
        }

        // 检测血量变化
        float healthDiff = currentHealth - previousHealth;

        if (healthDiff < -0.01f) {
            // 受伤了
            float damage = -healthDiff;
            String source = getLastDamageSource(player);
            // 致命一击 → 只发死亡包，跳过受伤包
            if (currentHealth <= 0 && previousHealth > 0) {
                broadcaster.sendDeathEvent(maxHealth, source);
            } else {
                broadcaster.sendDamageEvent(damage, currentHealth, maxHealth, source);
            }
        } else if (healthDiff > 0.01f) {
            // 治疗了
            broadcaster.sendHealEvent(healthDiff, currentHealth, maxHealth);
        }
        // 无显著变化:仅更新记录，不发包

        previousHealth = currentHealth;
        previousMaxHealth = maxHealth;
    }

    /**
     * 获取最后的伤害来源描述
     * 使用反射兼容不同 MC 版本
     */
    private String getLastDamageSource(PlayerEntity player) {
        try {
            // 尝试新版 API (1.19.4+): getRecentDamageSource()
            DamageSource source = null;
            try {
                source = (DamageSource) player.getClass()
                    .getMethod("getRecentDamageSource")
                    .invoke(player);
            } catch (NoSuchMethodException e) {
                // 旧版 API - 无法直接获取
            }

            if (source == null) {
                return "unknown";
            }

            return getDamageSourceName(source);
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 获取伤害来源名称，兼容多版本
     */
    private String getDamageSourceName(DamageSource source) {
        try {
            String typeName = "unknown";

            // 尝试 1.19.4+ API: source.getType().msgId()
            try {
                Object type = source.getClass().getMethod("getType").invoke(source);
                typeName = (String) type.getClass().getMethod("msgId").invoke(type);
            } catch (Exception e) {
                // 尝试 1.14 ~ 1.19.3 API: source.getName()
                try {
                    typeName = (String) source.getClass().getMethod("getName").invoke(source);
                } catch (Exception e2) {
                    typeName = source.toString();
                }
            }

            // 获取攻击者名称
            try {
                Object attacker = source.getClass().getMethod("getAttacker").invoke(source);
                if (attacker != null) {
                    Object name = attacker.getClass().getMethod("getName").invoke(attacker);
                    Object nameStr = name.getClass().getMethod("getString").invoke(name);
                    return typeName + ":" + nameStr;
                }
            } catch (Exception e) {
                // 忽略
            }

            return typeName;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
```

- [ ] **Step 6: 编译 Fabric 工程**

```powershell
Set-Location F:\mc-mod-hp\mc-mod
.\gradlew.bat :fabric:build
```

Expected: `BUILD SUCCESSFUL`(Java 编译 + Loom remap 通过)。

- [ ] **Step 7: 提交**

```powershell
Set-Location F:\mc-mod-hp
git add mc-mod/common/src/main/java/com/healthbroadcast/common/HealthBroadcaster.java mc-mod/fabric/src/main/java/com/healthbroadcast/fabric/HealthBroadcastFabric.java
git commit -m "feat: 统一血量输出协议 v2 (Fabric) - 单包带条件字段 + 完整JSON转义 + Locale.ROOT"
```

---

### Task 2: Forge 协议迁移(Forge 广播器重写 + Forge 入口重构)

**Files:**
- Rewrite: `mc-mod-forge/src/main/java/com/healthbroadcast/forge/HealthBroadcaster.java`
- Rewrite: `mc-mod-forge/src/main/java/com/healthbroadcast/forge/HealthBroadcastMod.java`
- Test: `$env:TEMP\hp-protocol-test\com\healthbroadcast\forge\ForgeProtocolCheck.java`(临时,不入库)

**Interfaces:**
- Consumes: Task 1 确定的协议(同一套 `build*Json`/`escapeJson` 输出)。
- Produces:
  - `com.healthbroadcast.forge.HealthBroadcaster`(单例 `INSTANCE`):
    - `public boolean forceHealth(float health, float maxHealth)` — 无条件发基准包(签名不变)
    - `public void sendDamage(float damage, float healthAfter, String source)` — 发受伤包(签名不变;`maxHealth` 从缓存推导,空则 20.0f)
    - `public void sendDeath(String source)` — 发死亡包(签名不变;发后 `reset()`)
    - `public void sendHeal(float amount, float healthAfter)` — 发治疗包(签名不变;`maxHealth` 从缓存推导,空则 20.0f)
    - `public void reset()`, `setPort(int)`, `getPort()`, `setDebugMode(boolean)`, `getInstance()`(均不变);**删除 `sendHealth`**
    - 包级静态构建器与 Task 1 common 同名同实现:`buildStateJson/buildDamageJson/buildHealJson/buildDeathJson`, `escapeJson`
  - `com.healthbroadcast.forge.HealthBroadcastMod`:
    - tick 处理器只发周期基准状态;`LivingDamage/LivingDeath/LivingHeal` 事件独占事件包
    - 新增 `private static String formatSource(DamageSource)` 产出 `类型:攻击者`

- [ ] **Step 1: 写失败测试(Forge 版 harness,复用 Task 1 断言)**

```powershell
New-Item -ItemType Directory -Force "$env:TEMP\hp-protocol-test\com\healthbroadcast\forge" | Out-Null
$content = @'
package com.healthbroadcast.forge;

import java.util.Locale;

public class ForgeProtocolCheck {
    private static int failures = 0;

    private static void check(String name, String actual, String expected) {
        if (!expected.equals(actual)) {
            System.out.println("FAIL: " + name);
            System.out.println("  expected: " + expected);
            System.out.println("  actual:   " + actual);
            failures++;
        } else {
            System.out.println("PASS: " + name);
        }
    }

    public static void main(String[] args) {
        check("state",
            HealthBroadcaster.buildStateJson(3.0f, 10.0f),
            "{\"alive\":true,\"health\":3.0,\"maxHealth\":10.0,\"percentage\":0.300}");
        check("damage",
            HealthBroadcaster.buildDamageJson(2.0f, 3.0f, 10.0f, "mob:Zombie"),
            "{\"alive\":true,\"health\":3.0,\"maxHealth\":10.0,\"percentage\":0.300,\"damage\":2.0,\"source\":\"mob:Zombie\"}");
        check("heal",
            HealthBroadcaster.buildHealJson(2.0f, 5.0f, 10.0f),
            "{\"alive\":true,\"health\":5.0,\"maxHealth\":10.0,\"percentage\":0.500,\"heal\":2.0}");
        check("death",
            HealthBroadcaster.buildDeathJson(10.0f, "fall"),
            "{\"alive\":false,\"health\":0.0,\"maxHealth\":10.0,\"percentage\":0.000,\"death\":true,\"source\":\"fall\"}");
        check("escape-backslash",
            HealthBroadcaster.escapeJson("path\\to\\nowhere"),
            "path\\\\to\\\\nowhere");
        check("escape-control",
            HealthBroadcaster.escapeJson("bad\nName\tX"),
            "bad\\nName\\tX");
        check("escape-quote",
            HealthBroadcaster.escapeJson("Bob\"TheBoss\""),
            "Bob\\\"TheBoss\\\"");
        check("escape-unicode-passthrough",
            HealthBroadcaster.escapeJson("\u5f20\u4e09"),
            "\u5f20\u4e09");

        Locale old = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            check("locale-state",
                HealthBroadcaster.buildStateJson(18.0f, 20.0f),
                "{\"alive\":true,\"health\":18.0,\"maxHealth\":20.0,\"percentage\":0.900}");
        } finally {
            Locale.setDefault(old);
        }

        if (failures == 0) {
            System.out.println("ALL PASS (9 checks)");
        } else {
            System.out.println(failures + " FAILURES");
            System.exit(1);
        }
    }
}
'@
[System.IO.File]::WriteAllText("$env:TEMP\hp-protocol-test\com\healthbroadcast\forge\ForgeProtocolCheck.java", $content)
```

- [ ] **Step 2: 编译+运行,确认失败**

```powershell
javac -encoding UTF-8 -d "$env:TEMP\hp-protocol-test\out" "F:\mc-mod-hp\mc-mod-forge\src\main\java\com\healthbroadcast\forge\HealthBroadcaster.java" "$env:TEMP\hp-protocol-test\com\healthbroadcast\forge\ForgeProtocolCheck.java"
```

Expected: 编译失败,`cannot find symbol: method buildStateJson(...)`。

- [ ] **Step 3: 重写 Forge 广播器 `HealthBroadcaster.java`**

将 `mc-mod-forge/src/main/java/com/healthbroadcast/forge/HealthBroadcaster.java` **整体替换**为以下内容(删除 `sendHealth`;新增构建器 + `escapeJson` + `Locale.ROOT`;`send` 改用 `StandardCharsets.UTF_8`):

```java
package com.healthbroadcast.forge;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * UDP Broadcaster - Sends player health data via UDP
 * 支持 MC 1.12 ~ 最新版本
 *
 * 协议 v2:统一单包结构。基准字段 (alive/health/maxHealth/percentage) 常驻,
 * 事件字段 (damage/heal/source/death) 仅在触发时出现。
 * 与 Fabric/common 的 HealthBroadcaster 输出结构保持一致。
 */
public class HealthBroadcaster {

    private static final HealthBroadcaster INSTANCE = new HealthBroadcaster();
    private static final String UDP_HOST = "127.0.0.1";

    private DatagramSocket socket;
    private InetAddress address;
    private int port = 39571;
    private boolean debugMode = true;

    // State cache
    private float lastHealth = -1;
    private float lastMaxHealth = -1;

    private HealthBroadcaster() {
        initSocket();
    }

    private void initSocket() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            socket = new DatagramSocket();
            address = InetAddress.getByName(UDP_HOST);
            log("UDP socket created successfully, target: " + UDP_HOST + ":" + port);
        } catch (Exception e) {
            System.err.println("[HealthBroadcast] Failed to create UDP socket: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static HealthBroadcaster getInstance() {
        return INSTANCE;
    }

    public void setDebugMode(boolean debug) {
        this.debugMode = debug;
    }

    private void log(String message) {
        if (debugMode) {
            System.out.println("[HealthBroadcast] " + message);
        }
    }

    public void setPort(int newPort) {
        if (newPort > 0 && newPort < 65536) {
            if (this.port != newPort) {
                this.port = newPort;
                log("UDP port changed to: " + newPort);
                initSocket();
            }
        }
    }

    public int getPort() {
        return port;
    }

    /**
     * Force send base state packet (ignore cache) - 周期/首帧强制快照
     */
    public boolean forceHealth(float health, float maxHealth) {
        lastHealth = health;
        lastMaxHealth = maxHealth;
        String json = buildStateJson(health, maxHealth);
        log("Force sending state: " + health + "/" + maxHealth);
        return send(json);
    }

    /**
     * Send damage event packet. maxHealth 从缓存推导,缓存为空时回退 20.0f。
     */
    public void sendDamage(float damage, float healthAfter, String source) {
        float maxHealth = lastMaxHealth > 0 ? lastMaxHealth : 20.0f;
        String json = buildDamageJson(damage, healthAfter, maxHealth, source);
        log("Sending damage event: " + damage + " from " + source);
        send(json);
        lastHealth = healthAfter;
    }

    /**
     * Send death event packet. 发送后重置缓存。
     */
    public void sendDeath(String source) {
        float maxHealth = lastMaxHealth > 0 ? lastMaxHealth : 20.0f;
        String json = buildDeathJson(maxHealth, source);
        log("Sending death event: " + source);
        send(json);
        reset();
    }

    /**
     * Send heal event packet. maxHealth 从缓存推导,缓存为空时回退 20.0f。
     */
    public void sendHeal(float amount, float healthAfter) {
        float maxHealth = lastMaxHealth > 0 ? lastMaxHealth : 20.0f;
        String json = buildHealJson(amount, healthAfter, maxHealth);
        log("Sending heal event: +" + amount);
        send(json);
        lastHealth = healthAfter;
    }

    /**
     * Reset state
     */
    public void reset() {
        lastHealth = -1;
        lastMaxHealth = -1;
        log("State reset");
    }

    // ========== JSON 包构建 (包级可见,便于独立测试) ==========

    static String buildStateJson(float health, float maxHealth) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("{\"alive\":").append(health > 0 ? "true" : "false")
          .append(",\"health\":").append(dec(health, 1))
          .append(",\"maxHealth\":").append(dec(maxHealth, 1))
          .append(",\"percentage\":").append(dec(percent(health, maxHealth), 3))
          .append('}');
        return sb.toString();
    }

    static String buildDamageJson(float damage, float healthAfter, float maxHealth, String source) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"alive\":").append(healthAfter > 0 ? "true" : "false")
          .append(",\"health\":").append(dec(healthAfter, 1))
          .append(",\"maxHealth\":").append(dec(maxHealth, 1))
          .append(",\"percentage\":").append(dec(percent(healthAfter, maxHealth), 3))
          .append(",\"damage\":").append(dec(damage, 1))
          .append(",\"source\":\"").append(escapeJson(source != null ? source : "unknown"))
          .append("\"}");
        return sb.toString();
    }

    static String buildHealJson(float amount, float healthAfter, float maxHealth) {
        StringBuilder sb = new StringBuilder(80);
        sb.append("{\"alive\":").append(healthAfter > 0 ? "true" : "false")
          .append(",\"health\":").append(dec(healthAfter, 1))
          .append(",\"maxHealth\":").append(dec(maxHealth, 1))
          .append(",\"percentage\":").append(dec(percent(healthAfter, maxHealth), 3))
          .append(",\"heal\":").append(dec(amount, 1))
          .append('}');
        return sb.toString();
    }

    static String buildDeathJson(float maxHealth, String source) {
        StringBuilder sb = new StringBuilder(80);
        sb.append("{\"alive\":false,\"health\":0.0")
          .append(",\"maxHealth\":").append(dec(maxHealth, 1))
          .append(",\"percentage\":0.000")
          .append(",\"death\":true")
          .append(",\"source\":\"").append(escapeJson(source != null ? source : "unknown"))
          .append("\"}");
        return sb.toString();
    }

    private static float percent(float health, float maxHealth) {
        return maxHealth > 0 ? health / maxHealth : 0;
    }

    private static String dec(float value, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", value);
    }

    /**
     * JSON 字符串转义:引号、反斜杠、控制字符。非 ASCII 原样保留。
     */
    static String escapeJson(String input) {
        StringBuilder sb = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\t': sb.append("\\t"); break;
                case '\r': sb.append("\\r"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private boolean send(String message) {
        if (socket == null || socket.isClosed()) {
            log("Socket is null or closed, reinitializing...");
            initSocket();
            if (socket == null || socket.isClosed()) {
                log("Failed to reinitialize socket");
                return false;
            }
        }
        try {
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
            socket.send(packet);
            log("Sent UDP packet to " + UDP_HOST + ":" + port + " - " + message);
            return true;
        } catch (Exception e) {
            log("Failed to send UDP packet: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
```

- [ ] **Step 4: 编译+运行 harness,确认通过**

```powershell
javac -encoding UTF-8 -d "$env:TEMP\hp-protocol-test\out" "F:\mc-mod-hp\mc-mod-forge\src\main\java\com\healthbroadcast\forge\HealthBroadcaster.java" "$env:TEMP\hp-protocol-test\com\healthbroadcast\forge\ForgeProtocolCheck.java"
java -Dfile.encoding=UTF-8 -cp "$env:TEMP\hp-protocol-test\out" com.healthbroadcast.forge.ForgeProtocolCheck
```

Expected: `PASS` ×9,最后一行 `ALL PASS (9 checks)`,退出码 0。

- [ ] **Step 5: 重构 Forge 入口 `HealthBroadcastMod.java`**

将 `mc-mod-forge/src/main/java/com/healthbroadcast/forge/HealthBroadcastMod.java` **整体替换**为以下内容(tick 只发周期基准状态;`Living*` 事件独占事件包;致命判定;`formatSource` 取攻击者):

```java
package com.healthbroadcast.forge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(HealthBroadcastMod.MODID)
public class HealthBroadcastMod {
    
    public static final String MODID = "hp_output";
    private static final Logger LOGGER = LoggerFactory.getLogger(HealthBroadcastMod.class);
    
    // 仅作"是否已观测到玩家"的首帧标记
    private float lastHealth = -1;
    private float lastMaxHealth = -1;
    private int tickCounter = 0;
    private static final int FORCE_UPDATE_INTERVAL = 100;
    
    public HealthBroadcastMod() {
        // Register config
        Config.register();
        
        // Register config screen (游戏内配置界面)
        ModLoadingContext.get().registerExtensionPoint(
            ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory(
                (mc, parent) -> new ConfigScreen(parent)
            )
        );
        
        // Register mod event bus listener
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);
        
        // Register game event handlers
        MinecraftForge.EVENT_BUS.register(this);
        
        LOGGER.info("[HealthBroadcast] Mod constructor completed!");
    }
    
    private void onClientSetup(FMLClientSetupEvent event) {
        // Apply port from config
        HealthBroadcaster.getInstance().setPort(Config.getPort());
        LOGGER.info("[HealthBroadcast] Client setup complete! UDP port: " + Config.getPort());
        
        // Send test data
        event.enqueueWork(() -> {
            HealthBroadcaster.getInstance().forceHealth(20.0f, 20.0f);
            LOGGER.info("[HealthBroadcast] Sent initial test health data");
        });
    }
    
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            if (lastHealth != -1) {
                HealthBroadcaster.getInstance().reset();
                lastHealth = -1;
                lastMaxHealth = -1;
                tickCounter = 0;
            }
            return;
        }
        
        // Update port from config
        HealthBroadcaster.getInstance().setPort(Config.getPort());
        
        Player player = mc.player;
        float health = player.getHealth();
        float maxHealth = player.getMaxHealth();
        
        tickCounter++;
        
        // 定期强制发送基准状态
        if (tickCounter >= FORCE_UPDATE_INTERVAL) {
            tickCounter = 0;
            HealthBroadcaster.getInstance().forceHealth(health, maxHealth);
            lastHealth = health;
            lastMaxHealth = maxHealth;
            return;
        }
        
        // 首次进入游戏
        if (lastHealth < 0) {
            HealthBroadcaster.getInstance().forceHealth(health, maxHealth);
            lastHealth = health;
            lastMaxHealth = maxHealth;
            return;
        }
        
        // 血量变化由 Living* 事件独占处理;此处仅维护周期基准状态
    }
    
    @SubscribeEvent
    public void onLivingDamage(LivingDamageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        
        if (event.getEntity() == mc.player) {
            String source = formatSource(event.getSource());
            float healthAfter = mc.player.getHealth() - event.getAmount();
            
            // 致命一击 → 不发受伤包,由死亡事件发死亡包
            if (healthAfter <= 0) return;
            
            LOGGER.info("[HealthBroadcast] LivingDamageEvent: {} damage from {}", event.getAmount(), source);
            HealthBroadcaster.getInstance().sendDamage(
                event.getAmount(),
                healthAfter,
                source
            );
        }
    }
    
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        
        if (event.getEntity() == mc.player) {
            String source = formatSource(event.getSource());
            LOGGER.info("[HealthBroadcast] LivingDeathEvent: {}", source);
            HealthBroadcaster.getInstance().sendDeath(source);
        }
    }
    
    @SubscribeEvent
    public void onLivingHeal(LivingHealEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;
        
        if (event.getEntity() == player) {
            float healthAfter = Math.min(player.getHealth() + event.getAmount(), player.getMaxHealth());
            LOGGER.info("[HealthBroadcast] LivingHealEvent: +{}", event.getAmount());
            HealthBroadcaster.getInstance().sendHeal(
                event.getAmount(),
                healthAfter
            );
        }
    }
    
    /**
     * 统一来源:类型:攻击者 (如 mob:Zombie),无攻击者时仅类型 (如 fall)。
     */
    private static String formatSource(DamageSource source) {
        if (source == null) return "unknown";
        String typeName = source.getMsgId();
        try {
            Entity attacker = source.getEntity();
            if (attacker != null) {
                String attackerName = attacker.getDisplayName().getString();
                if (attackerName != null && !attackerName.isEmpty()) {
                    return typeName + ":" + attackerName;
                }
            }
        } catch (Exception ignored) {
        }
        return typeName;
    }
}
```

- [ ] **Step 6: 编译 Forge 工程**

```powershell
Set-Location F:\mc-mod-hp\mc-mod-forge
.\gradlew.bat build
```

Expected: `BUILD SUCCESSFUL`(首次 Forge Gradle 构建需下载 FG 依赖,可能较慢;若超时用 `run_in_background` 并轮询)。

- [ ] **Step 7: 提交**

```powershell
Set-Location F:\mc-mod-hp
git add mc-mod-forge/src/main/java/com/healthbroadcast/forge/HealthBroadcaster.java mc-mod-forge/src/main/java/com/healthbroadcast/forge/HealthBroadcastMod.java
git commit -m "feat: 统一血量输出协议 v2 (Forge) - 事件独占 + source取攻击者 + 序列化修复"
```

---

### Task 3: 更新 README 协议文档

**Files:**
- Modify: `README.md`(两个区段)

**Interfaces:**
- Consumes: Task 1/2 确定的协议(字段名、示例、致命一击规则)。

- [ ] **Step 1: 替换「UDP 数据格式」区段**

将 `README.md` 中 `## UDP 数据格式` 起、至 `## 接收数据示例` 之前(即旧的"事件类型/字段说明/常见伤害来源"三节)整段替换为:

```markdown
## UDP 数据格式

向 `127.0.0.1:<port>`（默认 39571）发送 JSON 格式 UDP 数据包。

每次发送都是一个统一结构的 JSON 包：**基准字段常驻**（`alive`/`health`/`maxHealth`/`percentage`），**事件字段仅在触发时出现**（`damage`/`heal`/`death`/`source`）。通过字段存在性区分事件类型。

### 包类型

#### 1. 基准状态（周期心跳 / 首次进入）
```json
{"alive":true,"health":18.0,"maxHealth":20.0,"percentage":0.900}
```

#### 2. 受伤事件
```json
{"alive":true,"health":17.0,"maxHealth":20.0,"percentage":0.850,"damage":3.0,"source":"mob:Zombie"}
```

#### 3. 治疗事件
```json
{"alive":true,"health":19.0,"maxHealth":20.0,"percentage":0.950,"heal":2.0}
```

#### 4. 死亡事件
```json
{"alive":false,"health":0.0,"maxHealth":20.0,"percentage":0.000,"death":true,"source":"fall"}
```

> 注意：致命一击只发送死亡事件，不会同时发送受伤事件；每个游戏事件最多产生一个数据包。

### 字段说明

| 字段 | 类型 | 说明 | 出现时机 |
|------|------|------|----------|
| `alive` | boolean | 玩家是否存活 | 恒在 |
| `health` | float | 当前血量（半心=1.0，满血=20.0） | 恒在 |
| `maxHealth` | float | 最大血量 | 恒在 |
| `percentage` | float | 血量百分比（0.0 ~ 1.0） | 恒在 |
| `damage` | float | 受到的伤害值 | 受伤时 |
| `heal` | float | 治疗量 | 治疗时 |
| `source` | string | 伤害/死亡来源 | 受伤/死亡时 |
| `death` | boolean | 恒为 `true` | 死亡时 |

### 常见伤害来源

| 来源 | 说明 |
|------|------|
| `mob:Zombie` | 被僵尸攻击 |
| `mob:Skeleton` | 被骷髅射击 |
| `player:Steve` | 被其他玩家攻击 |
| `fall` | 摔落伤害 |
| `lava` | 岩浆伤害 |
| `drown` | 溺水 |
| `starve` | 饥饿 |
| `explosion` | 爆炸 |
```

- [ ] **Step 2: 替换「接收数据示例」中的 Python 与 Node.js 代码块**

将 Python 示例替换为:

```python
import socket, json
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(('127.0.0.1', 39571))
while True:
    data, _ = sock.recvfrom(1024)
    event = json.loads(data.decode())
    line = f"alive={event['alive']} hp={event['health']}/{event['maxHealth']}"
    if 'damage' in event: line += f" damage={event['damage']} from {event['source']}"
    if 'heal' in event: line += f" heal=+{event['heal']}"
    if 'death' in event: line += " DEATH"
    print(line)
```

将 Node.js 示例替换为:

```javascript
const dgram = require('dgram');
const server = dgram.createSocket('udp4');
server.on('message', (msg) => {
  const data = JSON.parse(msg.toString());
  let line = `alive=${data.alive} hp=${data.health}/${data.maxHealth}`;
  if (data.damage) line += ` damage=${data.damage} from ${data.source}`;
  if (data.heal) line += ` heal=+${data.heal}`;
  if (data.death) line += ' DEATH';
  console.log(line);
});
server.bind(39571, '127.0.0.1');
```

PowerShell 示例保持不动(仅打印原始字符串)。

- [ ] **Step 3: 自检**

在 `README.md` 中全文检索,确认不再出现 `event['type']`、`data.type`、`"type":"health"`、`amount` 字段、`timestamp` 字段等旧协议残留。

- [ ] **Step 4: 提交**

```powershell
git add README.md
git commit -m "docs: README 更新为新协议 (单包带条件字段,去掉 type)"
```

---

### Task 4: 更新 test_udp.bat 监听器

**Files:**
- Rewrite: `test_udp.bat`

**Interfaces:**
- Consumes: Task 1/2 协议(通过字段存在性分支)。

- [ ] **Step 1: 整体替换 `test_udp.bat`**

```bat
@echo off
chcp 65001 >nul
echo ========================================
echo   HP Output UDP 监听器
echo   监听端口: 39571
echo   按 Ctrl+C 退出
echo ========================================
echo.

powershell -NoProfile -Command "$udp = New-Object System.Net.Sockets.UdpClient(39571); $ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0); Write-Host '正在监听 UDP 端口 39571...' -ForegroundColor Green; while ($true) { $data = $udp.Receive([ref]$ep); $msg = [System.Text.Encoding]::UTF8.GetString($data); $json = $msg | ConvertFrom-Json; $time = Get-Date -Format 'HH:mm:ss.fff'; if ($json.death) { Write-Host \"[$time] DEATH: $($json.source)\" -ForegroundColor Magenta } elseif ($null -ne $json.damage) { Write-Host \"[$time] DAMAGE: -$($json.damage) from '$($json.source)' | HP: $($json.health)/$($json.maxHealth) ($([math]::Round($json.percentage * 100))%%)\" -ForegroundColor Red } elseif ($null -ne $json.heal) { Write-Host \"[$time] HEAL: +$($json.heal) | HP: $($json.health)/$($json.maxHealth) ($([math]::Round($json.percentage * 100))%%)\" -ForegroundColor Green } else { Write-Host \"[$time] HP: $($json.health)/$($json.maxHealth) ($([math]::Round($json.percentage * 100))%%)\" -ForegroundColor Cyan } }"

pause
```

> 分支顺序:先判死亡(死亡包含 `source` 但无 `damage`),再判受伤/治疗,最后基准状态。`ConvertFrom-Json` 对缺失字段返回 `$null`,故用 `$null -ne $json.damage` 判断存在性。批处理内 `%%` 用于转义字面 `%`。

- [ ] **Step 2: 自检**

```powershell
Get-Content F:\mc-mod-hp\test_udp.bat | Select-String -Pattern '\$json\.type'
```

Expected: 无输出(不再引用 `type` 字段)。

- [ ] **Step 3: 提交**

```powershell
git add test_udp.bat
git commit -m "chore: test_udp.bat 适配新协议 (按字段存在性分支)"
```

---

## Final Verification(全部任务完成后)

```powershell
Set-Location F:\mc-mod-hp\mc-mod
.\gradlew.bat :common:build
.\gradlew.bat :fabric:build
Set-Location F:\mc-mod-hp\mc-mod-forge
.\gradlew.bat build
```

- 三个构建均 Expected `BUILD SUCCESSFUL`。
- 两个临时 harness(Task 1/2)再跑一遍,均 `ALL PASS (9 checks)`。
- `git status --short` 应只含两个 untracked 目录 `.claude/`、`github/`(起始即存在,非本次改动)与本次提交的文件。
- 清理:`Remove-Item -Recurse -Force "$env:TEMP\hp-protocol-test"`。
