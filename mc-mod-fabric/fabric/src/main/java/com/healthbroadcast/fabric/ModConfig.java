package com.healthbroadcast.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.healthbroadcast.common.HealthBroadcaster;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * 配置管理器 - 处理配置文件的读写
 */
public class ModConfig {
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "health-broadcast.json";
    
    private static ModConfig instance;
    
    // 配置项
    public int udpPort = HealthBroadcaster.DEFAULT_PORT;
    
    public static ModConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }
    
    /**
     * 加载配置
     */
    public static ModConfig load() {
        File configFile = getConfigFile();
        
        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                ModConfig config = GSON.fromJson(reader, ModConfig.class);
                if (config != null) {
                    config.apply();
                    return config;
                }
            } catch (Exception e) {
                System.err.println("[HealthBroadcast] Failed to load config: " + e.getMessage());
            }
        }
        
        // 返回默认配置
        ModConfig config = new ModConfig();
        config.save();
        return config;
    }
    
    /**
     * 保存配置
     */
    public void save() {
        File configFile = getConfigFile();
        
        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(this, writer);
        } catch (Exception e) {
            System.err.println("[HealthBroadcast] Failed to save config: " + e.getMessage());
        }
    }
    
    /**
     * 应用配置到广播器
     */
    public void apply() {
        HealthBroadcaster.getInstance().setPort(udpPort);
    }
    
    private static File getConfigFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE).toFile();
    }
}
