package com.winss.wbutils.features;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.Messages;
import com.winss.wbutils.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// StatSpy - Detects and logs when other players check your stats.
public class StatSpy {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path LOG_PATH = FabricLoader.getInstance().getConfigDir().resolve("wbutils_statspy.json");
    private static final int MAX_ENTRIES = 100; 
    private static final Pattern SUBTITLE_PATTERN = Pattern.compile(
        "^(?:\\[([^\\]]+)\\]\\s+)?(.+?)\\s+is checking your Stats!$",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("§.");

    private boolean statsCheckTitleDetected = false;
    private long lastTitleTime = 0;
    private static final long TITLE_EXPIRY_MS = 1000; 
    
    private static final long AFK_TIMEOUT_MS = 60_000; 
    private long lastActivityTime = System.currentTimeMillis();
    private boolean wasInactive = false;
    private List<StatCheckEntry> pendingNotifications = new ArrayList<>();
    
    private List<StatCheckEntry> logEntries = new ArrayList<>();
    
    public StatSpy() {
        load();
    }

    public void onClientTick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        
        boolean currentlyInactive = isPlayerInactive();
        
        
        if (wasInactive && !currentlyInactive) {
            showPendingNotifications();
        }
        
        wasInactive = currentlyInactive;
    }

    private boolean isPlayerInactive() {
        MinecraftClient client = MinecraftClient.getInstance();
        
        
        if (!client.isWindowFocused()) {
            return true;
        }
        
        
        long now = System.currentTimeMillis();
        return (now - lastActivityTime) > AFK_TIMEOUT_MS;
    }

    public void onPlayerActivity() {
        lastActivityTime = System.currentTimeMillis();
    }

    private void showPendingNotifications() {
        if (pendingNotifications.isEmpty()) return;
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        
        int count = pendingNotifications.size();
        
        if (count == 1) {
            
            StatCheckEntry entry = pendingNotifications.get(0);
            String message = Messages.format("statspy.detected", "player", entry.playerName);
            client.player.sendMessage(Text.literal(message), false);
        } else {
            
            String message = Messages.format("statspy.while_away", "count", String.valueOf(count));
            client.player.sendMessage(Text.literal(message), false);
            
            
            StringBuilder names = new StringBuilder();
            int showCount = Math.min(count, 5);
            for (int i = 0; i < showCount; i++) {
                if (i > 0) names.append(", ");
                names.append(pendingNotifications.get(i).playerName);
            }
            if (count > 5) {
                names.append(" §7+").append(count - 5).append(" more");
            }
            client.player.sendMessage(Text.literal(Messages.get("statspy.list_prefix") + names), false);
        }
        
        pendingNotifications.clear();
    }

    public void handleTitle(Text title) {
        if (title == null) return;
        
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.statSpyEnabled) return;
        
        String stripped = title.getString().trim();
        
        // Only log when debug is on
        if (config.debugStatSpy) {
            WBUtilsClient.LOGGER.info("[StatSpy] Title received: '{}'", stripped);
        }
        
        if (stripped.toUpperCase().contains("STATS CHECK")) {
            statsCheckTitleDetected = true;
            lastTitleTime = System.currentTimeMillis();
            
            if (config.debugStatSpy) {
                WBUtilsClient.LOGGER.info("[StatSpy] Detected STATS CHECK title!");
            }
        }
    }

    public void handleSubtitle(Text subtitle) {
        if (subtitle == null) return;
        
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.statSpyEnabled) return;
        
        String stripped = subtitle.getString().trim();
        
        
        if (config.debugStatSpy) {
            WBUtilsClient.LOGGER.info("[StatSpy] Subtitle received: '{}' (title detected: {})", stripped, statsCheckTitleDetected);
        }
        
        
        if (!statsCheckTitleDetected) return;
        
        long now = System.currentTimeMillis();
        if (now - lastTitleTime > TITLE_EXPIRY_MS) {
            statsCheckTitleDetected = false;
            if (config.debugStatSpy) {
                WBUtilsClient.LOGGER.info("[StatSpy] Title expired, ignoring subtitle");
            }
            return;
        }
        
        statsCheckTitleDetected = false;
        
        // Strip color codes before matching
        String cleanSubtitle = COLOR_CODE_PATTERN.matcher(stripped).replaceAll("").trim();
        if (config.debugStatSpy) {
            WBUtilsClient.LOGGER.info("[StatSpy] Clean subtitle: '{}'", cleanSubtitle);
        }
        
        Matcher matcher = SUBTITLE_PATTERN.matcher(cleanSubtitle);
        
        if (matcher.matches()) {
            String rank = matcher.group(1); 
            String playerName = matcher.group(2);
            
            if (config.debugStatSpy) {
                WBUtilsClient.LOGGER.info("[StatSpy] Detected stat check from: {} (rank: {})", playerName, rank);
            }
            
            
            StatCheckEntry entry = new StatCheckEntry(playerName, rank, Instant.now().toEpochMilli());
            addEntry(entry);
            
            
            if (isPlayerInactive()) {
                
                pendingNotifications.add(entry);
                if (config.debugStatSpy) {
                    WBUtilsClient.LOGGER.info("[StatSpy] Player inactive, queued notification (total queued: {})", pendingNotifications.size());
                }
            } else {
                
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    String message = Messages.format("statspy.detected", "player", playerName);
                    client.player.sendMessage(Text.literal(message), false);
                }
            }
        } else {
            // Only warn about pattern mismatch when debugging
            if (config.debugStatSpy) {
                WBUtilsClient.LOGGER.warn("[StatSpy] Subtitle didn't match pattern: '{}'", cleanSubtitle);
            }
        }
    }

    private void addEntry(StatCheckEntry entry) {
        logEntries.add(entry);
        
        
        while (logEntries.size() > MAX_ENTRIES) {
            logEntries.remove(0);
        }
        
        save();
    }
    

    public List<StatCheckEntry> getRecentLogs(int count) {
        List<StatCheckEntry> result = new ArrayList<>();
        int start = Math.max(0, logEntries.size() - count);
        for (int i = logEntries.size() - 1; i >= start; i--) {
            result.add(logEntries.get(i));
        }
        return result;
    }

    public int getLogCount() {
        return logEntries.size();
    }

    public void clearLog() {
        logEntries.clear();
        save();
    }

    public int getPendingCount() {
        return pendingNotifications.size();
    }

    public boolean isAfk() {
        return isPlayerInactive();
    }

    private void load() {
        try {
            if (Files.exists(LOG_PATH)) {
                String json = Files.readString(LOG_PATH);
                Type listType = new TypeToken<ArrayList<StatCheckEntry>>(){}.getType();
                List<StatCheckEntry> loaded = GSON.fromJson(json, listType);
                if (loaded != null) {
                    logEntries = loaded;
                }
                WBUtilsClient.LOGGER.info("[StatSpy] Loaded {} log entries", logEntries.size());
            }
        } catch (IOException e) {
            WBUtilsClient.LOGGER.error("[StatSpy] Failed to load log", e);
        }
    }

    private void save() {
        try {
            String json = GSON.toJson(logEntries);
            Files.writeString(LOG_PATH, json);
        } catch (IOException e) {
            WBUtilsClient.LOGGER.error("[StatSpy] Failed to save log", e);
        }
    }

    public static class StatCheckEntry {
        public String playerName;
        public String rank; 
        public long timestamp;
        
        public StatCheckEntry() {}
        
        public StatCheckEntry(String playerName, String rank, long timestamp) {
            this.playerName = playerName;
            this.rank = rank;
            this.timestamp = timestamp;
        }

        public String getFormattedTime() {
            long now = System.currentTimeMillis();
            long diff = now - timestamp;
            
            
            if (diff < 60_000) {
                return "Just now";
            }
            
            if (diff < 3600_000) {
                long mins = diff / 60_000;
                return mins + "m ago";
            }
            
            if (diff < 86400_000) {
                long hours = diff / 3600_000;
                return hours + "h ago";
            }
            
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")
                .withZone(ZoneId.systemDefault());
            return formatter.format(Instant.ofEpochMilli(timestamp));
        }
    }
}

