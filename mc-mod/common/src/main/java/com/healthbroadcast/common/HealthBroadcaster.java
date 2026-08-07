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
