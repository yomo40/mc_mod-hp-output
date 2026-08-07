# 统一血量输出协议 v2 设计

日期:2026-08-07
状态:已批准(用户于 2026-08-07 批准整体设计)

## 背景与问题

本项目是一个在任意 Minecraft 版本运行的客户端角色血量实时 UDP 输出 mod。当前输出存在四类问题:

1. **序列化正确性缺陷**(实测):
   - JSON 字符串只转义双引号,不转义反斜杠与裸控制字符。来源含 `\t`/`\n` 时,反斜杠被消费者解析器静默改写;来源含真实换行/制表符时产出非法 JSON。
   - `String.format` 未传 Locale。在德国/土耳其等系统上输出 `18,0` 而非 `18.0`,非法 JSON。
2. **跨加载器不一致**:Fabric 的 `source` 是 `mob:Zombie`(类型:攻击者),Forge 只有 `mob`(msgId 翻译键),消费端无法统一解析。
3. **Forge 重复发送**:tick 差值检测与 `Living*` 事件双路径并存,同一伤害/死亡可能发两次 UDP。
4. **结构不统一**:四种事件类型(`health`/`damage`/`heal`/`death`)字段各异,消费端需按 `type` 分支。

## 目标

输出改为**单包带条件字段**的统一结构:基准字段(存活+血量)常驻,事件字段仅在触发时出现。同时修复序列化正确性、统一跨加载器语义、消除 Forge 重复发送。

## 非目标

- 不引入 Gson 重构(用户选择保留手工 JSON,仅修复正确性)。
- 不改动心跳节奏(100-tick 强制周期快照、首帧强制发送、`>0.01f` 变化阈值)与默认端口 `39571`。
- 不新增测试框架/依赖。
- 不改 Minecraft 目标版本(仍为 Java 17 / MC 1.20.1 构建目标,源码注释声明多版本兼容)。

## 协议定义

统一 JSON 单包,英文键名。**基准字段常驻**,事件字段按需出现。

| 字段 | 类型 | 说明 | 何时出现 |
|------|------|------|----------|
| `alive` | boolean | 玩家存活 | 恒在 |
| `health` | float | 当前血量 | 恒在 |
| `maxHealth` | float | 最大血量 | 恒在 |
| `percentage` | float | 血量百分比 0.0~1.0 | 恒在 |
| `damage` | float | 受到的伤害值 | 受伤事件时 |
| `heal` | float | 治疗量 | 治疗事件时 |
| `source` | string | 伤害/死亡来源 | 受伤/死亡事件时 |
| `death` | boolean | 恒为 `true` | 死亡事件时 |

示例:

```
心跳/周期:      {"alive":true,"health":3.0,"maxHealth":10.0,"percentage":0.300}
受伤:            {"alive":true,"health":3.0,"maxHealth":10.0,"percentage":0.300,"damage":2.0,"source":"mob:Zombie"}
治疗:            {"alive":true,"health":5.0,"maxHealth":10.0,"percentage":0.500,"heal":2.0}
死亡:            {"alive":false,"health":0.0,"maxHealth":10.0,"percentage":0.000,"death":true,"source":"fall"}
```

约定:

- 消费端通过字段存在性区分事件类型,不再使用 `type` 字段(设计注:基准包与事件包仅凭事件字段存在与否区分)。
- 数值格式:`%.1f`(health/maxHealth/damage/heal)、`%.3f`(percentage),固定 `Locale.ROOT`。
- 死亡时 `alive=false`、`health=0.0`。
- `source` 统一为 `类型:攻击者`(`mob:Zombie`、`player:Steve`),无攻击者时裸类型(`fall`、`lava`、`starve`)。

## 检测架构(每加载器,协议一致)

### 一次触发一个包(致命一击规则)

任何单次游戏事件(一次受伤/一次治疗/一次死亡)最多产生**一个** UDP 包。关键规则:**致命一击只发死亡包,不再另发受伤包**,避免一包带 `damage`+一包带 `death` 的双包重复。

- Fabric(tick 差值):当 `health<=0 && prevHealth>0` 时,只发死亡包,跳过受伤包。
- Forge:
  - `LivingDamageEvent` 中,若该击致命(`player.getHealth() - event.getAmount() <= 0`)→ 不发受伤包,交给死亡事件。
  - `LivingDeathEvent` → 发死亡包。
  - 非致命受伤 → 发受伤包。
- 结果:非致命一击 → 1 个受伤包;致命一击 → 1 个死亡包;治疗 → 1 个治疗包;周期心跳 → 1 个基准包。

### Fabric(`HealthBroadcastFabric`)

- 保留 tick 差值检测:对比前后血量,`<-0.01f` 受伤、`>0.01f` 治疗;叠加致命一击规则(见上)。
- 保留 100-tick 周期强制基准包、首帧强制基准包、`player==null` 时 reset。
- `source` 用现有反射逻辑(`getRecentDamageSource` + `getType().msgId()` + `getAttacker().getName().getString()`),产出 `类型:攻击者`。
- 变化:广播器方法签名调整(见下),事件检测逻辑不变。

### Forge(`HealthBroadcastMod`)

- **tick 处理器改为只发周期基准状态**:保留 100-tick 强制基准包、首帧强制基准包、`mc.player==null` 时 reset;删除 tick 差值检测伤害/治疗/死亡及 `getLastDamageSource` 取值。
- **伤害/治疗/死亡改由事件独占**:
  - `LivingDamageEvent`(已存在)→ 非致命时发受伤包:`damage=event.getAmount()`,`health=max(0, player.getHealth() - amount)`,`source` 从 `event.getSource()` 取(攻击者经 `getEntity()` 显示名)。
  - `LivingHealEvent`(已存在)→ 发治疗包:`heal=event.getAmount()`,`health=min(player.getHealth()+amount, maxHealth)`。
  - `LivingDeathEvent`(已存在)→ 发死亡包:`death=true`,`health=0.0`,`maxHealth=player.getMaxHealth()`,`source` 从 `event.getSource()` 取。
- 事件过滤 `event.getEntity() == mc.player`,保持现有判定。

### source 提取(跨加载器一致)

- 类型名:Fabric 反射 `getType().msgId()`;Forge `event.getSource().getMsgId()`。
- 攻击者:Fabric 反射 `getAttacker()`;Forge `event.getSource().getEntity()`。
- 攻击者存在 → `类型:攻击者`(如 `mob:Zombie`);不存在 → 仅类型(如 `fall`)。
- 未知/解析失败 → `unknown`。
- 需要时两个广播器各自做 `escapeJson`(见下),不跨文件共享实现。

## 序列化正确性(两个广播器镜像实现)

- 所有 `String.format` 传 `Locale.ROOT`,修复小数分隔符随系统 locale 变化的问题。
- 新增 `escapeJson(String)` 正确转义:`"`→`\"`、`\`→`\\`、控制字符 U+0000~U+001F 转义为 `\uXXXX`(换行 `\n`、制表 `\t` 等)。替换现仅 `.replace("\"", "\\\"")` 的实现。
- 非 ASCII(如中文玩家名)原样 UTF-8 透传,不做 `\u` 转义(与 README 现有行为一致)。
- `common` 与 `forge` 的 `HealthBroadcaster` 输出结构与转义逻辑保持一致;签名差异(common 显式传 `maxHealth`、Forge 从缓存取)在重写后仍可保留,但字段输出必须一致。

## 受影响文件

| 文件 | 改动 |
|------|------|
| `mc-mod/common/src/main/java/com/healthbroadcast/common/HealthBroadcaster.java` | 重写序列化为统一单包;`escapeJson`;`Locale.ROOT`;方法签名调整为基准包 + 事件包 |
| `mc-mod/fabric/src/main/java/com/healthbroadcast/fabric/HealthBroadcastFabric.java` | 适配新广播器方法;source 提取逻辑保持,产出 `类型:攻击者` |
| `mc-mod-forge/src/main/java/com/healthbroadcast/forge/HealthBroadcaster.java` | 镜像重写;`escapeJson`;`Locale.ROOT` |
| `mc-mod-forge/src/main/java/com/healthbroadcast/forge/HealthBroadcastMod.java` | tick 仅周期基准包;`Living*` 事件独占事件包;source 取攻击者 |
| `README.md` | 更新 4 个事件示例与字段说明表、去掉 `type` |
| `test_udp.bat` | 由按 `type` 分支改为按字段存在性输出新结构 |

不改动:`ModConfig`、`Config`、两个 `ConfigScreen`、`ModMenuIntegration`、`fabric.mod.json`、`mods.toml`、Gradle 配置。

## 验证

- `cd mc-mod && .\gradlew.bat :common:build`(纯 Java,快)。
- `cd mc-mod && .\gradlew.bat :fabric:build`。
- `cd mc-mod-forge && .\gradlew.bat build`。
- 临时 harness(不入库):实例化新广播器的各事件方法,校验输出 JSON 可解析、字段存在性正确、`source` 转义正确、模拟非 ROOT locale 下小数分隔符仍为点。

## 回滚

协议为破坏性变更,消费端(README 示例脚本、`test_udp.bat`)随本次一并更新。若需回退,恢复四个受影响源码文件 + README + `test_udp.bat` 至原状即可(均受 git 版本管理)。
