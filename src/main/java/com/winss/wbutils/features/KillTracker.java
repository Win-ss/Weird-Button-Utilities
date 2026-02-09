package com.winss.wbutils.features;

import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.Messages;
import com.winss.wbutils.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.Set;
import java.util.regex.Pattern;
import com.winss.wbutils.network.NetworkManager;

/**
 * KOTH Tracker KTrack - Tracks players who repeatedly kill/damage others at KOTH.
 */
public class KillTracker {
    private static final int TIMEOUT_MS = 10000;
    private static final long SYNC_INTERVAL_MS = 30_000L; // Hotlist sync interval
    private static final long PROXIMITY_CHECK_INTERVAL_MS = 1000L;
    

    private final Map<String, Float> damageAmountPerPlayer = new ConcurrentHashMap<>();
    private final Map<String, Long> damageTimePerPlayer = new ConcurrentHashMap<>();
    private static final long DAMAGE_TRACKING_WINDOW_MS = 60_000L;
    

    private final Map<String, Long> proximityAlertCooldowns = new ConcurrentHashMap<>();
    

    private final Map<String, KillerInfo> hotList = new ConcurrentHashMap<>();
    

    private final Map<String, Long> recentAttackers = new ConcurrentHashMap<>();
    private String primaryAttacker = null;
    private long primaryAttackerTime = 0L;
    private static final long ATTACKER_MEMORY_MS = 30_000L;
    

    private float lastKnownHealth = 20f;
    private boolean deathReportedThisCycle = false;
    private long lastDeathReportTime = 0L;
    private static final long DEATH_REPORT_COOLDOWN_MS = 5000L;
    

    private long lastStatusLogTime = 0L;
    private static final long STATUS_LOG_INTERVAL_MS = 3000L;
    private int ticksSinceHealthLog = 0;
    

    private long lastSyncTime = 0L;
    private long lastProximityCheckTime = 0L;
    private boolean authWarningShown = false;
    
    // Performance Optimization: Cache whitelist names in a HashSet for O(1) lookups
    private final Set<String> whitelistCache = ConcurrentHashMap.newKeySet();
    private boolean whitelistLoaded = false;
    
    
    /**
     * Information about a killer from the hot list
     */
    public static class KillerInfo {
        public final String playerName;
        public final int totalKills;
        public final int totalDamageEvents;
        public final long lastEventTimestamp;
        
        public KillerInfo(String playerName, int totalKills, int totalDamageEvents, long lastEventTimestamp) {
            this.playerName = playerName;
            this.totalKills = totalKills;
            this.totalDamageEvents = totalDamageEvents;
            this.lastEventTimestamp = lastEventTimestamp;
        }
        
        public int getTotalEvents() {
            return totalKills + totalDamageEvents;
        }
    }
    
    /**
     * Tracks attacker globally when player takes damage.
     */
    public void onPlayerTookDamage(float amount) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.ktrackEnabled) {
            return;
        }
        
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return;
        }
        

        if (config.requireHousing && !WBUtilsClient.getHousingDetector().isInDptb2Housing()) {
            if (config.ktrackDebugLogs) {
                WBUtilsClient.LOGGER.info("[KillTracker] Damage tracking blocked: Not in housing (requireHousing=true)");
            }
            return;
        }
        

        String attacker = findNearestPlayer(player, 10.0);
        if (attacker == null || attacker.isEmpty()) {
            return;
        }
        

        // Skip mod users
        ModUserManager modUserManager = WBUtilsClient.getModUserManager();
        if (modUserManager != null && modUserManager.isModUser(attacker)) {
            return;
        }
        
        long now = Util.getMeasuringTimeMs();
        

        recentAttackers.put(attacker.toLowerCase(), now);
        primaryAttacker = attacker;
        primaryAttackerTime = now;
        

        // Suppress local message if whitelisted
        if (!isWhitelisted(attacker)) {
            sendDebugAlways(player, Messages.format("ktrack.debug.dmg_track", "player", attacker, "damage", String.format("%.1f", amount)));
        } else if (config.ktrackDebugLogs) {
            WBUtilsClient.LOGGER.info("[KillTracker] Suppressing local damage message for whitelisted attacker: {}", attacker);
        }

        if (config.ktrackDebugLogs) {
            WBUtilsClient.LOGGER.info("[KillTracker] Tracked attacker from damage: {} (total tracked: {})", attacker, recentAttackers.size());
        }
    }
    
    /**
     * Called when player takes damage in KOTH - tracks attacker for kill attribution
     * @param amount The damage amount
     * @param attackerName The attacker name (if known from damage source)
     */
    public void onPlayerDamagedInKoth(float amount, String attackerName) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.ktrackEnabled) {
            return;
        }
        

        if (config.requireHousing && !WBUtilsClient.getHousingDetector().isInDptb2Housing()) {
            if (config.ktrackDebugLogs) {
                WBUtilsClient.LOGGER.info("[KillTracker] Damage event ignored: Not in DPTB2 Housing");
            }
            return;
        }
        
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return;
        }
        

        String killer = attackerName;
        if (killer == null || killer.isEmpty()) {

            killer = findNearestPlayer(player, 10.0);
        }
        
        if (killer == null || killer.isEmpty()) {
            sendDebugAlways(player, Messages.get("ktrack.debug.no_attacker"));
            return;
        }
        

        // Skip mod users
        ModUserManager modUserManager = WBUtilsClient.getModUserManager();
        if (modUserManager != null && modUserManager.isModUser(killer)) {
            if (config.ktrackDebugLogs) {
                sendDebugAlways(player, Messages.format("ktrack.debug.skip_mod_user", "player", killer));
            }
            return;
        }
        
        long now = Util.getMeasuringTimeMs();
        

        recentAttackers.put(killer.toLowerCase(), now);
        primaryAttacker = killer;
        primaryAttackerTime = now;
        

        recentAttackers.entrySet().removeIf(e -> (now - e.getValue()) > ATTACKER_MEMORY_MS);
        

        // Use plain username to avoid color code issues
        String plainKiller = killer.replaceAll("§[0-9a-fk-or]", "").toLowerCase();

        Long lastTime = damageTimePerPlayer.get(plainKiller);
        if (lastTime == null || (now - lastTime) > DAMAGE_TRACKING_WINDOW_MS) {
            damageAmountPerPlayer.put(plainKiller, 0f);
        }
        
        float currentAccumulated = damageAmountPerPlayer.getOrDefault(plainKiller, 0f) + amount;
        damageAmountPerPlayer.put(plainKiller, currentAccumulated);
        damageTimePerPlayer.put(plainKiller, now);
        
        sendDebugAlways(player, Messages.format("ktrack.debug.dmg_event", 
            "player", killer, 
            "acc", String.format("%.1f", currentAccumulated), 
            "threshold", String.valueOf(config.ktrackDamageThreshold), 
            "hp", String.format("%.1f", player.getHealth())));
        
        if (currentAccumulated >= config.ktrackDamageThreshold) {
            sendDebugAlways(player, Messages.format("ktrack.debug.threshold_reached", "threshold", String.valueOf(config.ktrackDamageThreshold)));
            reportKTrackEvent(killer, player.getGameProfile().getName(), "damage");
            damageAmountPerPlayer.put(plainKiller, 0f);
        }
    }
    
    /**
     * Called every tick to check for health-based death detection
     */
    public void checkHealthForDeath() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.ktrackEnabled) {
            return;
        }
        
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            lastKnownHealth = 20f;
            return;
        }
        

        if (config.requireHousing && !WBUtilsClient.getHousingDetector().isInDptb2Housing()) {
            lastKnownHealth = player.getHealth();
            return;
        }
        

        KothProtector kothProtector = WBUtilsClient.getKothProtector();
        if (kothProtector == null || !kothProtector.isInKoth()) {
            lastKnownHealth = player.getHealth();
            deathReportedThisCycle = false;
            return;
        }
        
        float currentHealth = player.getHealth();
        long now = Util.getMeasuringTimeMs();
        

        ticksSinceHealthLog++;
        if ((now - lastStatusLogTime) >= STATUS_LOG_INTERVAL_MS) {
            lastStatusLogTime = now;
            logDeathDetectionStatus(player, currentHealth, now);
        }
        

        boolean possibleDeath = false;
        String deathMethod = null;
        
        if (currentHealth <= 2.0f && lastKnownHealth > 5.0f) {
            possibleDeath = true;
            deathMethod = "LOW_HEALTH";
            sendDebugAlways(player, Messages.format("ktrack.debug.death_low_hp", 
                "old", String.format("%.1f", lastKnownHealth), 
                "new", String.format("%.1f", currentHealth)));
        }
        
        if (currentHealth >= 15.0f && lastKnownHealth <= 5.0f && lastKnownHealth > 0) {
            possibleDeath = true;
            deathMethod = "RESPAWN";
            sendDebugAlways(player, Messages.format("ktrack.debug.death_respawn", 
                "old", String.format("%.1f", lastKnownHealth), 
                "new", String.format("%.1f", currentHealth)));
        }
        
        float healthDelta = lastKnownHealth - currentHealth;
        if (healthDelta >= 10.0f && currentHealth <= 10.0f) {
            possibleDeath = true;
            deathMethod = "BIG_HIT";
            sendDebugAlways(player, Messages.format("ktrack.debug.death_big_hit", 
                "old", String.format("%.1f", lastKnownHealth), 
                "new", String.format("%.1f", currentHealth), 
                "delta", String.format("%.1f", healthDelta)));
        }
        
        if (possibleDeath && !deathReportedThisCycle && (now - lastDeathReportTime) > DEATH_REPORT_COOLDOWN_MS) {
            deathReportedThisCycle = true;
            lastDeathReportTime = now;
            
            sendDebugAlways(player, Messages.format("ktrack.debug.death_confirmed", "method", deathMethod));
            

            reportDeathWithAttacker(player);
        }
        

        if (currentHealth > 10.0f) {
            deathReportedThisCycle = false;
        }
        
        lastKnownHealth = currentHealth;
    }
    
    private void logDeathDetectionStatus(ClientPlayerEntity player, float currentHealth, long now) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        
        StringBuilder sb = new StringBuilder();
        List<String> attackerList = new ArrayList<>();
        for (Map.Entry<String, Long> entry : recentAttackers.entrySet()) {
            long age = (now - entry.getValue()) / 1000;
            attackerList.add(entry.getKey() + "(" + age + "s ago)");
        }

        List<String> nearby = new ArrayList<>();
        for (AbstractClientPlayerEntity otherPlayer : client.world.getPlayers()) {
            if (otherPlayer == player) continue;
            double dist = player.distanceTo(otherPlayer);
            if (dist <= 20.0) {
                nearby.add(otherPlayer.getName().getString() + "(" + String.format("%.1f", dist) + "m)");
            }
        }

        String status = Messages.format("ktrack.debug.status", 
            "hp", String.format("%.1f", currentHealth), 
            "attackers", attackerList.isEmpty() ? "none" : String.join(", ", attackerList),
            "nearby", nearby.isEmpty() ? "none within 20m" : String.join(", ", nearby));
        sendDebugAlways(player, status);
    }

    private void reportDeathWithAttacker(ClientPlayerEntity player) {
        long now = Util.getMeasuringTimeMs();
        MinecraftClient client = MinecraftClient.getInstance();
        
        sendDebugAlways(player, Messages.get("ktrack.debug.attribution_start"));
        

        sendDebugAlways(player, Messages.format("ktrack.debug.tracked_attackers", "count", String.valueOf(recentAttackers.size())));
        for (Map.Entry<String, Long> entry : recentAttackers.entrySet()) {
            long ageMs = now - entry.getValue();
            boolean valid = ageMs <= ATTACKER_MEMORY_MS;
            sendDebugAlways(player, Messages.format("ktrack.debug.attacker_entry", 
                "player", entry.getKey(), 
                "age", String.valueOf(ageMs/1000), 
                "status", valid ? Messages.get("status.ok") : Messages.get("status.off")));
        }
        

        if (primaryAttacker != null) {
            long primaryAge = now - primaryAttackerTime;
            sendDebugAlways(player, Messages.format("ktrack.debug.primary_attacker", "player", primaryAttacker, "age", String.valueOf(primaryAge/1000)));
        } else {
            sendDebugAlways(player, Messages.get("ktrack.debug.primary_attacker_none"));
        }
        

        sendDebugAlways(player, Messages.get("ktrack.debug.nearby_players"));
        if (client.world != null) {
            List<PlayerCandidate> candidates = new ArrayList<>();
            for (AbstractClientPlayerEntity otherPlayer : client.world.getPlayers()) {
                if (otherPlayer == player) continue;
                double dist = player.distanceTo(otherPlayer);
                if (dist <= 30.0) {
                    String name = otherPlayer.getName().getString();
                    boolean wasAttacker = recentAttackers.containsKey(name.toLowerCase());
                    boolean isModUser = WBUtilsClient.getModUserManager() != null && 
                        WBUtilsClient.getModUserManager().isModUser(name);
                    candidates.add(new PlayerCandidate(name, dist, wasAttacker, isModUser));
                }
            }

            candidates.sort((a, b) -> Double.compare(a.distance, b.distance));
            for (PlayerCandidate c : candidates) {
                String color = c.wasAttacker ? "§c" : (c.isModUser ? "§9" : "§7");
                String tags = "";
                if (c.wasAttacker) tags += " " + Messages.get("ktrack.tag.attacker");
                if (c.isModUser) tags += " " + Messages.get("ktrack.tag.mod_user");
                sendDebugAlways(player, Messages.format("ktrack.debug.player_entry", 
                    "color", color, "player", c.name, "distance", String.format("%.1f", c.distance), "tags", tags));
            }
            if (candidates.isEmpty()) {
                sendDebugAlways(player, Messages.get("ktrack.debug.none_nearby"));
            }
        }
        

        String killer = null;
        String source = null;
        
        if (primaryAttacker != null && (now - primaryAttackerTime) <= ATTACKER_MEMORY_MS) {
            killer = primaryAttacker;
            source = "primary attacker";
        }
        
        if (killer == null) {
            String mostRecent = null;
            long mostRecentTime = 0;
            for (Map.Entry<String, Long> entry : recentAttackers.entrySet()) {
                if (entry.getValue() > mostRecentTime && (now - entry.getValue()) <= ATTACKER_MEMORY_MS) {
                    mostRecent = entry.getKey();
                    mostRecentTime = entry.getValue();
                }
            }
            if (mostRecent != null) {
                killer = mostRecent;
                source = "most recent attacker";
            }
        }
        


        
        sendDebugAlways(player, Messages.get("ktrack.debug.attribution_header"));
        
        if (killer == null) {

            sendDebugAlways(player, Messages.get("ktrack.debug.no_killer_found"));
            sendDebugAlways(player, Messages.format("ktrack.debug.tracked_attackers_label", "count", String.valueOf(recentAttackers.size())));
            sendDebugAlways(player, Messages.format("ktrack.debug.primary_attacker_label", "player", (primaryAttacker != null ? primaryAttacker : Messages.get("status.off"))));
            sendDebugAlways(player, Messages.get("ktrack.debug.no_killer_hint"));
            return;
        }
        

        ModUserManager modUserManager = WBUtilsClient.getModUserManager();
        if (modUserManager != null && modUserManager.isModUser(killer)) {
            sendDebugAlways(player, Messages.format("ktrack.debug.killer_mod_user", "player", killer));
            return;
        }
        
        sendDebugAlways(player, Messages.format("ktrack.debug.killer_final", "player", killer, "source", source));
        sendDebugAlways(player, Messages.get("ktrack.debug.reporting"));
        
        reportKTrackEvent(killer, player.getGameProfile().getName(), "kill");
        

        recentAttackers.clear();
        primaryAttacker = null;
    }

    private static class PlayerCandidate {
        final String name;
        final double distance;
        final boolean wasAttacker;
        final boolean isModUser;
        
        PlayerCandidate(String name, double distance, boolean wasAttacker, boolean isModUser) {
            this.name = name;
            this.distance = distance;
            this.wasAttacker = wasAttacker;
            this.isModUser = isModUser;
        }
    }

    public void onPlayerDiedInKoth() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        

        if (config.ktrackDebugLogs) {
            WBUtilsClient.LOGGER.info("[KillTracker] onPlayerDiedInKoth (bounty method) called!");
        }
        if (player != null) {
            sendDebugAlways(player, Messages.get("ktrack.debug.death_bounty"));
        }
        
        if (!config.ktrackEnabled) {
            if (player != null) sendDebugAlways(player, Messages.get("ktrack.debug.disabled"));
            return;
        }
        

        if (config.requireHousing && !WBUtilsClient.getHousingDetector().isInDptb2Housing()) {
            if (player != null) sendDebugAlways(player, Messages.get("ktrack.debug.not_in_housing"));
            return;
        }
        
        if (player == null) {
            WBUtilsClient.LOGGER.warn("[KillTracker] Player is null!");
            return;
        }
        

        KothProtector kothProtector = WBUtilsClient.getKothProtector();
        if (kothProtector == null) {
            if (player != null) sendDebugAlways(player, Messages.get("ktrack.debug.no_koth_protector"));
            return;
        }

        if (!kothProtector.isInKoth()) {
            long now = Util.getMeasuringTimeMs();
            if ((now - kothProtector.getLastKothTitleTime()) > 3000L) {
                if (player != null) sendDebugAlways(player, Messages.format("ktrack.debug.not_in_koth", 
                    "age", String.valueOf((now - kothProtector.getLastKothTitleTime())/1000)));
                return;
            }
        }
        
        long now = Util.getMeasuringTimeMs();
        if ((now - lastDeathReportTime) < DEATH_REPORT_COOLDOWN_MS) {
            sendDebugAlways(player, Messages.format("ktrack.debug.cooldown", "age", String.valueOf(now - lastDeathReportTime)));
            return;
        }
        
        lastDeathReportTime = now;
        

        reportDeathWithAttacker(player);
    }
    
    private static final Pattern VALID_PLAYER_NAME = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    private boolean isRealPlayer(AbstractClientPlayerEntity otherPlayer) {
        if (otherPlayer == null) return false;
        
        String name = otherPlayer.getName().getString();
        if (name == null || name.isEmpty()) return false;
        

        String cleanName = name.replaceAll("§[0-9a-fk-or]", "");
        if (!VALID_PLAYER_NAME.matcher(cleanName).matches()) {
            return false;
        }
        


        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            if (client.getNetworkHandler().getPlayerListEntry(otherPlayer.getUuid()) == null) {
                return false;
            }
        }
        

        if (cleanName.length() >= 8 && cleanName.equals(cleanName.toLowerCase()) && !cleanName.contains("_")) {

            int vowels = 0;
            for (char c : cleanName.toCharArray()) {
                if ("aeiou".indexOf(c) >= 0) vowels++;
            }
            double vowelRatio = (double) vowels / cleanName.length();
            if (vowelRatio < 0.1 || vowelRatio > 0.6) {
                return false; 
            }
        }
        
        return true;
    }
    
    private String findNearestPlayer(ClientPlayerEntity player, double maxDistance) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;
        
        String nearest = null;
        double nearestDist = maxDistance;
        
        for (AbstractClientPlayerEntity otherPlayer : client.world.getPlayers()) {
            if (otherPlayer == player) continue;
            

            if (!isRealPlayer(otherPlayer)) {
                continue;
            }
            
            double dist = player.distanceTo(otherPlayer);
            if (dist <= nearestDist) {
                nearestDist = dist;
                nearest = otherPlayer.getName().getString();
            }
        }
        
        return nearest;
    }

    private List<String> getNearbyPlayers(ClientPlayerEntity player, double maxDistance) {
        List<String> nearby = new ArrayList<>();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return nearby;
        
        for (AbstractClientPlayerEntity otherPlayer : client.world.getPlayers()) {
            if (otherPlayer == player) continue;
            

            if (!isRealPlayer(otherPlayer)) {
                continue;
            }
            
            double dist = player.distanceTo(otherPlayer);
            if (dist <= maxDistance) {
                nearby.add(otherPlayer.getName().getString());
            }
        }
        
        return nearby;
    }

    private void reportKTrackEvent(String killer, String victim, String eventType) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        if (config.debugKtrack) {
            WBUtilsClient.LOGGER.info("[KillTracker] reportKTrackEvent called: {} by {} (victim: {})", eventType, killer, victim);
        }
        
        if (config.authServerUrl == null || config.authServerUrl.isBlank()) {
            WBUtilsClient.LOGGER.warn("[KillTracker] No auth server URL configured!");
            postDebugAlways(Messages.get("ktrack.error.no_url"));
            return;
        }

        if (config.authToken == null || config.authToken.isBlank()) {
            if (!authWarningShown) {
                authWarningShown = true;
                postDebugAlways(Messages.get("ktrack.error.no_auth"));
            }
            if (config.debugKtrack) {
                WBUtilsClient.LOGGER.info("[KillTracker] Skipping report (no authToken found)");
            }
            return;
        }
        
        postDebugAlways(Messages.format("ktrack.debug.sending_event", "type", eventType, "player", killer));
        
        CompletableFuture.runAsync(() -> {
            String urlStr = config.authServerUrl + "/ktrack/report";
            if (config.debugHttp) {
                WBUtilsClient.LOGGER.info("[KillTracker] POST to: {}", urlStr);
                postHttpDebug("POST " + urlStr);
            }
            
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            String reporterUuid = player != null ? player.getUuid().toString() : "";
            
            String json = String.format(
                "{\"killer\":\"%s\",\"victim\":\"%s\",\"event_type\":\"%s\",\"reporter_uuid\":\"%s\",\"timestamp\":%d,\"whitelisted\":%b}",
                escapeJson(killer),
                escapeJson(victim),
                escapeJson(eventType),
                escapeJson(reporterUuid),
                System.currentTimeMillis(),
                isWhitelisted(killer)
            );
            
            if (config.debugHttp) {
                String maskedToken = config.authToken != null && config.authToken.length() > 4 
                    ? config.authToken.substring(0, 4) + "****" 
                    : "****";
                WBUtilsClient.LOGGER.info("[KillTracker] Reporting with UUID: {} and token: {}", reporterUuid, maskedToken);
                postHttpDebug("Token: " + maskedToken + " | UUID: " + reporterUuid);
                WBUtilsClient.LOGGER.info("[KillTracker] JSON payload: {}", json);
                postHttpDebug("Payload: " + (json.length() > 80 ? json.substring(0, 80) + "..." : json));
            }

            NetworkManager.post(urlStr, json, config.authToken)
                .thenAccept(response -> {
                    if (config.debugHttp) {
                        WBUtilsClient.LOGGER.info("[KillTracker] Server response code: {}", response.statusCode());
                        postHttpDebug("Response: " + response.statusCode());
                    }
                    
                    if (response.isSuccess()) {
                        postDebugAlways(Messages.format("ktrack.debug.report_success", "type", eventType, "player", killer));
                    } else {
                        postDebugAlways(Messages.format("ktrack.error.server_code", "code", String.valueOf(response.statusCode())));
                        WBUtilsClient.LOGGER.warn("KTrack event report failed with status {}", response.statusCode());
                    }
                })
                .exceptionally(e -> {
                    postDebugAlways(Messages.format("ktrack.error.send_failed", "error", e.getMessage()));
                    WBUtilsClient.LOGGER.error("Failed to report KTrack event", e);
                    return null;
                });
        });
    }

    public void onClientTick() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.ktrackEnabled) return;
        
        checkHealthForDeath();
        
        KothProtector kothProtector = WBUtilsClient.getKothProtector();
        if (kothProtector == null || !kothProtector.isInKoth()) {
            return;
        }

        if (config.requireHousing && !WBUtilsClient.getHousingDetector().isInDptb2Housing()) {
            return;
        }
        
        long now = Util.getMeasuringTimeMs();
        

        if ((now - lastSyncTime) >= SYNC_INTERVAL_MS) {
            lastSyncTime = now;
            syncHotList();
        }

        if ((now - lastProximityCheckTime) >= PROXIMITY_CHECK_INTERVAL_MS) {
            lastProximityCheckTime = now;
            checkProximity();
        }
    }

    private void syncHotList() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (config.authServerUrl == null || config.authServerUrl.isBlank()) {
            return;
        }


        KothProtector kothProtector = WBUtilsClient.getKothProtector();
        if (kothProtector == null || !kothProtector.isInKoth()) {
            return;
        }
        
        CompletableFuture.runAsync(() -> {
            String urlStr = config.authServerUrl + "/ktrack/hotlist?time_window_hours=" + config.ktrackTimeWindowHours + "&threshold=" + config.ktrackEventThreshold;
            NetworkManager.get(urlStr, false, config.authToken)
                .thenAccept(response -> {
                    if (response.isSuccess()) {
                        parseHotListResponse(response.body());
                    }
                })
                .exceptionally(err -> {
                    WBUtilsClient.LOGGER.debug("Failed to sync hot list", err);
                    return null;
                });
        });
    }
    

    private void parseHotListResponse(String json) {
        try {
            hotList.clear();
            
            if (!json.contains("\"hotlist\"")) return;
            
            int start = json.indexOf("[");
            int end = json.lastIndexOf("]");
            if (start < 0 || end < 0 || start >= end) return;
            
            String arrayStr = json.substring(start + 1, end);
            if (arrayStr.isBlank()) return;
            
            int depth = 0;
            int objStart = -1;
            for (int i = 0; i < arrayStr.length(); i++) {
                char c = arrayStr.charAt(i);
                if (c == '{') {
                    if (depth == 0) objStart = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        String obj = arrayStr.substring(objStart, i + 1);
                        parsekillerObject(obj);
                        objStart = -1;
                    }
                }
            }
            
            postDebug(Messages.format("ktrack.debug.hotlist_parsed", "count", String.valueOf(hotList.size())));
        } catch (Exception e) {
            WBUtilsClient.LOGGER.debug("Failed to parse hot list", e);
        }
    }
    
    private void parsekillerObject(String obj) {
        try {
            String name = extractJsonString(obj, "name");
            int kills = extractJsonInt(obj, "kills");
            int damageEvents = extractJsonInt(obj, "damage_events");
            long lastEvent = extractJsonLong(obj, "last_event");
            
            if (name != null && !name.isEmpty()) {
                hotList.put(name.toLowerCase(), new KillerInfo(name, kills, damageEvents, lastEvent));
            }
        } catch (Exception e) {
        }
    }
    
    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }
    
    private int extractJsonInt(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return 0;
        start += search.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) return 0;
        return Integer.parseInt(json.substring(start, end));
    }
    
    private long extractJsonLong(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return 0;
        start += search.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) return 0;
        return Long.parseLong(json.substring(start, end));
    }

    private void checkProximity() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;
        
        double alertDistance = config.ktrackProximityDistance;
        long cooldownMs = config.ktrackAlertCooldownMinutes * 60 * 1000L;
        long now = Util.getMeasuringTimeMs();
        
        List<String> nearbyPlayers = getNearbyPlayers(player, alertDistance);
        
        for (String nearbyPlayer : nearbyPlayers) {
            String key = nearbyPlayer.toLowerCase();
            KillerInfo info = hotList.get(key);
            
            if (info != null) {

                Long lastAlert = proximityAlertCooldowns.get(key);
                if (lastAlert != null && (now - lastAlert) < cooldownMs) {
                    continue;
                }
                

                proximityAlertCooldowns.put(key, now);
                

                if (!isWhitelisted(nearbyPlayer)) {
                    showProximityAlert(info);
                } else if (config.ktrackDebugLogs) {
                    WBUtilsClient.LOGGER.info("[KillTracker] Suppressing local proximity alert for whitelisted player: {}", nearbyPlayer);
                }
                

                sendProximityAlert(info, player.getGameProfile().getName());
            }
        }
    }
    
    /**
     * Show local proximity alert to user
     */
    private void showProximityAlert(KillerInfo info) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            ClientPlayerEntity player = client.player;
            if (player == null) return;
            
            MutableText alert = Text.empty()
                .append(Text.literal(Messages.get("ktrack.alert.header")))
                .append(Text.literal("\n" + Messages.format("ktrack.alert.nearby", "player", info.playerName)))
                .append(Text.literal("\n" + Messages.getColorText()))
                .append(Text.literal(Messages.format("ktrack.alert.stats", "kills", String.valueOf(info.totalKills), "damages", String.valueOf(info.totalDamageEvents))));
            
            player.sendMessage(alert, false);
            

            player.playSound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.0f, 1.0f);
        });
    }
    
    /**
     * Send proximity alert to server for Discord notification
     */
    private void sendProximityAlert(KillerInfo info, String detectingUser) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (config.authServerUrl == null || config.authServerUrl.isBlank()) {
            return;
        }
        
        CompletableFuture.runAsync(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            String reporterUuid = player != null ? player.getUuid().toString() : "";
            
            String urlStr = config.authServerUrl + "/ktrack/proximity-alert";
            String json = String.format(
                "{\"killer\":\"%s\",\"detecting_user\":\"%s\",\"reporter_uuid\":\"%s\",\"kills\":%d,\"damage_events\":%d,\"whitelisted\":%b}",
                escapeJson(info.playerName),
                escapeJson(detectingUser),
                escapeJson(reporterUuid),
                info.totalKills,
                info.totalDamageEvents,
                isWhitelisted(info.playerName)
            );

            NetworkManager.post(urlStr, json, config.authToken)
                .thenAccept(response -> {
                    if (config.ktrackDebugLogs && response.isSuccess()) {
                        postDebug(Messages.format("ktrack.debug.proximity_sent", "player", info.playerName));
                    }
                })
                .exceptionally(e -> {
                    WBUtilsClient.LOGGER.debug("Failed to send proximity alert", e);
                    return null;
                });
        });
    }
    

    public Map<String, KillerInfo> getHotList() {
        return new HashMap<>(hotList);
    }

    public List<KillerInfo> getTopKillers(int limit) {
        List<KillerInfo> list = new ArrayList<>(hotList.values());
        list.sort((a, b) -> Integer.compare(b.getTotalEvents(), a.getTotalEvents()));
        return list.subList(0, Math.min(limit, list.size()));
    }

    public void fetchHotList(Consumer<Boolean> callback) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (config.authServerUrl == null || config.authServerUrl.isBlank()) {
            postDebugAlways(Messages.get("ktrack.error.no_url"));
            callback.accept(false);
            return;
        }
        
        postDebugAlways(Messages.get("ktrack.debug.fetching_hotlist"));
        
        CompletableFuture.runAsync(() -> {
            String urlStr = config.authServerUrl + "/ktrack/hotlist?time_window_hours=" + config.ktrackTimeWindowHours + "&threshold=" + config.ktrackEventThreshold;
            WBUtilsClient.LOGGER.debug("[KillTracker] Fetching: {}", urlStr);
            
            NetworkManager.get(urlStr, false, config.authToken)
                .thenAccept(response -> {
                    WBUtilsClient.LOGGER.debug("[KillTracker] Hot list response code: {}", response.statusCode());
                    
                    if (response.isSuccess()) {
                        String responseStr = response.body();
                        WBUtilsClient.LOGGER.debug("[KillTracker] Server response: {}", responseStr);
                        postDebugAlways(Messages.format("ktrack.debug.server_response", "response", (responseStr.length() > 100 ? responseStr.substring(0, 100) + "..." : responseStr)));
                        
                        parseHotListResponse(responseStr);
                        postDebugAlways(Messages.format("ktrack.debug.hotlist_parsed", "count", String.valueOf(hotList.size())));
                        runOnMainThread(() -> callback.accept(true));
                    } else {
                        postDebugAlways(Messages.format("ktrack.error.server_code", "code", String.valueOf(response.statusCode())));
                        runOnMainThread(() -> callback.accept(false));
                    }
                })
                .exceptionally(e -> {
                    WBUtilsClient.LOGGER.error("Failed to fetch hot list", e);
                    postDebugAlways(Messages.format("ktrack.error.send_failed", "error", e.getMessage()));
                    runOnMainThread(() -> callback.accept(false));
                    return null;
                });
        });
    }
    
    private void sendDebug(ClientPlayerEntity player, String message) {
        if (player == null) return;
        MutableText text = Text.empty()
            .append(Text.literal(message));
        player.sendMessage(text, false);
        WBUtilsClient.LOGGER.debug("[KTrack Debug] {}", message);
    }
    
    private void sendDebugAlways(ClientPlayerEntity player, String message) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (player == null || !config.debugKtrack) return;
        player.sendMessage(Text.literal(message), false);
        WBUtilsClient.LOGGER.debug("[KTrack] {}", message);
    }
    
    private void postDebug(String message) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.debugKtrack) return;
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            ClientPlayerEntity player = client.player;
            if (player != null) {
                sendDebug(player, message);
            }
        });
    }
    
    private void postDebugAlways(String message) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.debugKtrack) return;
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            ClientPlayerEntity player = client.player;
            if (player != null) {
                sendDebugAlways(player, message);
            }
        });
    }
    
    private void postHttpDebug(String message) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.debugHttp) return;
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            ClientPlayerEntity player = client.player;
            if (player != null) {
                player.sendMessage(Text.literal(Messages.format("ktrack.debug.http", "msg", message)), false);
            }
        });
    }
    
    private static void runOnMainThread(Runnable runnable) {
        MinecraftClient.getInstance().execute(runnable);
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
    /**
     * Refreshes the local whitelist cache from the mod configuration.
     * This is an O(N) operation that enables O(1) lookups later.
     */
    public void refreshWhitelistCache() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        whitelistCache.clear();
        if (config.ktrackWhitelist != null) {
            for (String name : config.ktrackWhitelist) {
                if (name != null && !name.isBlank()) {
                    whitelistCache.add(name.toLowerCase().trim());
                }
            }
        }
        whitelistLoaded = true;
        WBUtilsClient.LOGGER.debug("[KillTracker] Whitelist cache refreshed ({} entries)", whitelistCache.size());
    }

    public boolean isWhitelisted(String playerName) {
        if (playerName == null) return false;
        
        // Lazy load the cache if it hasn't been loaded yet
        if (!whitelistLoaded) {
            refreshWhitelistCache();
        }
        
        return whitelistCache.contains(playerName.toLowerCase().trim());
    }
}
