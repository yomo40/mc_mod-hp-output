package com.healthbroadcast.forge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
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

            // 致命一击且未持不死图腾 → 不发受伤包,由死亡事件发死亡包
            // (持图腾会被救回,LivingDeathEvent 不会触发,须照常发受伤包)
            if (healthAfter <= 0 && !holdsTotem(mc.player)) return;

            // 持图腾被救回时实际血量固定为 1.0
            float reportedHealth = healthAfter <= 0 ? 1.0f : healthAfter;

            LOGGER.info("[HealthBroadcast] LivingDamageEvent: {} damage from {}", event.getAmount(), source);
            HealthBroadcaster.getInstance().sendDamage(
                event.getAmount(),
                reportedHealth,
                source
            );
        }
    }

    /**
     * 玩家主手或副手是否持有不死图腾
     */
    private static boolean holdsTotem(Player player) {
        return player.getMainHandItem().getItem() == Items.TOTEM_OF_UNDYING
            || player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING;
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
