package com.winss.wbutils.features;

import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.Messages;
import com.winss.wbutils.config.ModConfig;
import com.winss.wbutils.network.NetworkManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;


public class ModUserManager {
    private static final long SYNC_INTERVAL_MS = 60_000L;
    private final Set<String> onlineModUsers = ConcurrentHashMap.newKeySet();
    private final Map<String, String> originalCaseNames = new ConcurrentHashMap<>();
    private long lastSyncTime = 0L;
    private boolean hasNotifiedOnline = false;
    

    public boolean isModUser(String playerName) {
        if (playerName == null) return false;
        return onlineModUsers.contains(playerName.toLowerCase());
    }
    

    public List<String> getOnlineModUsers() {
        List<String> result = new ArrayList<>();
        for (String lower : onlineModUsers) {
            result.add(originalCaseNames.getOrDefault(lower, lower));
        }
        return result;
    }

    public int getOnlineCount() {
        return onlineModUsers.size();
    }

    public void notifyOnline() {
        if (hasNotifiedOnline) return;
        
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (config.authServerUrl == null || config.authServerUrl.isBlank()) {
            return;
        }
        
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        
        String playerName = player.getGameProfile().getName();
        String playerUuid = player.getUuid().toString();
        
        postDebug(Messages.get("modusers.debug.notifying_online"));
        
        CompletableFuture.runAsync(() -> {
            String urlStr = config.authServerUrl + "/modusers/online";
            String json = String.format(
                "{\"username\":\"%s\",\"minecraft_uuid\":\"%s\",\"action\":\"join\",\"version\":\"%s\"}",
                escapeJson(playerName),
                escapeJson(playerUuid),
                escapeJson(WBUtilsClient.getVersion())
            );
            
            NetworkManager.post(urlStr, json, config.authToken)
                .thenAccept(response -> {
                    if (response.isSuccess()) {
                        hasNotifiedOnline = true;
                        postDebug(Messages.get("modusers.debug.online_success"));
                        syncOnlineModUsers(null);
                    } else {
                        postDebug(Messages.format("modusers.debug.online_failed", "code", String.valueOf(response.statusCode())));
                    }
                })
                .exceptionally(e -> {
                    WBUtilsClient.LOGGER.debug("[ModUserManager] Failed to notify online", e);
                    return null;
                });
        });
    }
    public void notifyOffline() {
        if (!hasNotifiedOnline) return;
        
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (config.authServerUrl == null || config.authServerUrl.isBlank()) {
            return;
        }
        
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return;
        
        String playerName = player.getGameProfile().getName();
        String playerUuid = player.getUuid().toString();
        
        postDebug(Messages.get("modusers.debug.notifying_offline"));
        
        CompletableFuture.runAsync(() -> {
            String urlStr = config.authServerUrl + "/modusers/online";
            String json = String.format(
                "{\"username\":\"%s\",\"minecraft_uuid\":\"%s\",\"action\":\"leave\"}",
                escapeJson(playerName),
                escapeJson(playerUuid)
            );
            
            NetworkManager.post(urlStr, json, config.authToken)
                .thenAccept(response -> {
                    if (response.isSuccess()) {
                        postDebug(Messages.get("modusers.debug.offline_success"));
                    }
                })
                .exceptionally(e -> {
                    WBUtilsClient.LOGGER.debug("[ModUserManager] Failed to notify offline", e);
                    return null;
                });
        });
        
        hasNotifiedOnline = false;
    }

    public void syncOnlineModUsers(Consumer<Boolean> callback) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (config.authServerUrl == null || config.authServerUrl.isBlank()) {
            if (callback != null) callback.accept(false);
            return;
        }
        
        CompletableFuture.runAsync(() -> {
            String urlStr = config.authServerUrl + "/modusers/online";
            NetworkManager.get(urlStr, false, config.authToken)
                .thenAccept(response -> {
                    if (response.isSuccess()) {
                        parseOnlineModUsersResponse(response.body());
                        postDebug(Messages.format("modusers.debug.synced", "count", String.valueOf(onlineModUsers.size())));
                        if (callback != null) {
                            MinecraftClient.getInstance().execute(() -> callback.accept(true));
                        }
                    } else {
                        if (callback != null) {
                            MinecraftClient.getInstance().execute(() -> callback.accept(false));
                        }
                    }
                })
                .exceptionally(e -> {
                    WBUtilsClient.LOGGER.debug("[ModUserManager] Failed to sync online mod users", e);
                    if (callback != null) {
                        MinecraftClient.getInstance().execute(() -> callback.accept(false));
                    }
                    return null;
                });
        });
    }

    private void parseOnlineModUsersResponse(String json) {
        try {
            onlineModUsers.clear();
            originalCaseNames.clear();
            
            if (!json.contains("\"users\"")) return;
            
            int start = json.indexOf("[");
            int end = json.lastIndexOf("]");
            if (start < 0 || end < 0 || start >= end) return;
            
            String arrayStr = json.substring(start + 1, end);
            if (arrayStr.isBlank()) return;
            
            String[] parts = arrayStr.split(",");
            for (String part : parts) {
                String name = part.trim().replace("\"", "");
                if (!name.isEmpty()) {
                    String lower = name.toLowerCase();
                    onlineModUsers.add(lower);
                    originalCaseNames.put(lower, name);
                }
            }
        } catch (Exception e) {
            WBUtilsClient.LOGGER.debug("[ModUserManager] Failed to parse online mod users", e);
        }
    }

    public void onClientTick() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        long now = System.currentTimeMillis();
        if ((now - lastSyncTime) >= SYNC_INTERVAL_MS) {
            lastSyncTime = now;
            
            if (WBUtilsClient.getHousingDetector().isInDptb2Housing()) {
                syncOnlineModUsers(null);
            }
        }
    }
    
    public void reset() {
        notifyOffline();
        onlineModUsers.clear();
        originalCaseNames.clear();
        hasNotifiedOnline = false;
    }
    
    private void postDebug(String message) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.debugModUsers) return;
        
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            ClientPlayerEntity player = client.player;
            if (player != null) {
                player.sendMessage(Text.literal(Messages.get("modusers.prefix") + message), false);
            }
        });
    }
    
    private static String escapeJson(String input) {
        if (input == null) return "";
        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
