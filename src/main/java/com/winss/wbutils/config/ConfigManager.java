package com.winss.wbutils.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.winss.wbutils.WBUtilsClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("wbutils.json");
    
    private ModConfig config;
    
    public ConfigManager() {
        this.config = new ModConfig();
    }
    
    public void load() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                String json = Files.readString(CONFIG_PATH);
                config = GSON.fromJson(json, ModConfig.class);
                WBUtilsClient.LOGGER.info("Config loaded successfully");
            } else {
                save();
            }
        } catch (IOException e) {
            WBUtilsClient.LOGGER.error("Failed to load config", e);
        }
    }
    
    public void save() {
        try {
            String json = GSON.toJson(config);
            Files.writeString(CONFIG_PATH, json);
            WBUtilsClient.LOGGER.info("Config saved successfully");
        } catch (IOException e) {
            WBUtilsClient.LOGGER.error("Failed to save config", e);
        }
    }
    
    public ModConfig getConfig() {
        return config;
    }
}
