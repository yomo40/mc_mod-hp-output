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
