package com.winss.wbutils.features;

import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.Messages;
import com.winss.wbutils.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.winss.wbutils.network.NetworkManager;

/**
 * 
 * Tracks RPS games played and sends data for analysis.
 * 
 * Game Flow:
 * Phase 1 - Initiation: "* [NPC] Rock: Hey! I have an idea... let's play Rock, Paper Scissors!"
 * Phase 2 - Start: "* [NPC] Rock: Rock, paper, scissors... SHOOT!"
 * Phase 3 - Player Choice: "* RPS! You selected ROCK/PAPER/SCISSORS!"
 * Phase 4 - NPC Choice: "* [NPC] Rock: ROCK/PAPER/SCISSORS!"
 * Phase 5 - Result: Win/Loss/Tie messages
 */
public class RPSTracker {
    
    private static final int TIMEOUT_MS = 10000;
    private static final long RESULT_COOLDOWN_MS = 500;
    
    private final Object gameLock = new Object();
    
    private volatile GamePhase currentPhase = GamePhase.IDLE;
    private volatile String playerChoice = null;
    private volatile String npcChoice = null;
    private volatile long gameStartTime = 0;
    private volatile int sessionGameCount = 0;
    private volatile long lastResultTime = 0;
    private String sessionId = null;
    
    private String lastNpcChoice = null;
    private String lastPlayerChoice = null;
    private GameResult lastResult = null;
    

    private static final Pattern GAME_INIT_PATTERN = Pattern.compile(
        "^\\*\\s*\\[NPC\\]\\s*Rock:\\s*Hey!\\s*I have an idea.*let's play Rock,\\s*Paper Scissors!",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern GAME_START_PATTERN = Pattern.compile(
        "^\\*\\s*\\[NPC\\]\\s*Rock:\\s*Rock,\\s*paper,\\s*scissors.*SHOOT!",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern PLAYER_CHOICE_PATTERN = Pattern.compile(
        "^\\*\\s*RPS!\\s*You selected\\s+(ROCK|PAPER|SCISSORS)!",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern NPC_CHOICE_PATTERN = Pattern.compile(
        "^\\*\\s*\\[NPC\\]\\s*Rock:\\s*(ROCK|PAPER|SCISSORS)!",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern WIN_PATTERN = Pattern.compile(
        "^\\*\\s*\\[NPC\\]\\s*Rock:\\s*Oh\\?\\s*I LOSE!\\s*Which means you WON!",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern LOSS_PATTERN = Pattern.compile(
        "^\\*\\s*\\[NPC\\]\\s*Rock:\\s*Oh\\?\\s*I WIN!\\s*Better luck next time!",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern TIE_PATTERN = Pattern.compile(
        "^\\*\\s*\\[NPC\\]\\s*Rock:\\s*Oh\\?\\s*It's a Tie!",
        Pattern.CASE_INSENSITIVE
    );
    
    private RPSStats cachedStats = null;
    private long lastStatsFetchTime = 0;
    private static final long STATS_CACHE_DURATION_MS = 30000;
    
    public enum GamePhase {
        IDLE,
        INITIATED,
        STARTED,
        PLAYER_CHOSE,
        NPC_CHOSE,
        COMPLETED
    }
    
    public enum GameResult {
        WIN,
        LOSS,
        TIE
    }
    
    public RPSTracker() {
        this.sessionId = java.util.UUID.randomUUID().toString().substring(0, 8);
        WBUtilsClient.LOGGER.info("[RPSTracker] Initialized with session ID: {}", sessionId);
    }

    private boolean isRPSRelatedMessage(String stripped) {
        if (stripped.contains("[NPC] Rock:")) {
            return true;
        }
        if (stripped.contains("RPS! You selected")) {
            return true;
        }
        return false;
    }

    public void handleChatMessage(Text message) {
        if (message == null) return;
        
        String plain = message.getString();
        if (plain == null) return;
        
        String stripped = plain.replaceAll("§[0-9a-fk-or]", "").trim();
        
        if (!stripped.startsWith("*")) {
            return;
        }

        if (!isRPSRelatedMessage(stripped)) {
            return;
        }
        
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        if (config.debugRPS) {
            WBUtilsClient.LOGGER.info("[RPSTracker] Processing message: {}", stripped);
        }
        
        Matcher matcher;
        
        if (GAME_INIT_PATTERN.matcher(stripped).find()) {
            handleGameInit();
            return;
        }
        
        if (GAME_START_PATTERN.matcher(stripped).find()) {
            handleGameStart();
            return;
        }
        
        matcher = PLAYER_CHOICE_PATTERN.matcher(stripped);
        if (matcher.find()) {
            handlePlayerChoice(matcher.group(1).toUpperCase());
            return;
        }
        
        matcher = NPC_CHOICE_PATTERN.matcher(stripped);
        if (matcher.find()) {
            handleNPCChoice(matcher.group(1).toUpperCase());
            return;
        }
        
        if (WIN_PATTERN.matcher(stripped).find()) {
            handleGameResult(GameResult.WIN);
            return;
        }
        
        if (LOSS_PATTERN.matcher(stripped).find()) {
            handleGameResult(GameResult.LOSS);
            return;
        }
        
        if (TIE_PATTERN.matcher(stripped).find()) {
            handleGameResult(GameResult.TIE);
            return;
        }
    }
    
    private void handleGameInit() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        currentPhase = GamePhase.INITIATED;
        playerChoice = null;
        npcChoice = null;
        gameStartTime = System.currentTimeMillis();
        
        if (config.debugRPS) {
            WBUtilsClient.LOGGER.info("[RPSTracker] Game initiated!");
        }
    }
    
    private void handleGameStart() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        if (currentPhase != GamePhase.INITIATED && currentPhase != GamePhase.IDLE) {
            if (config.debugRPS) {
                WBUtilsClient.LOGGER.info("[RPSTracker] Game start detected but phase was: {}", currentPhase);
            }
        }
        
        currentPhase = GamePhase.STARTED;
        
        if (config.debugRPS) {
            WBUtilsClient.LOGGER.info("[RPSTracker] Game started");
        }
    }
    
    private void handlePlayerChoice(String choice) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        playerChoice = choice;
        currentPhase = GamePhase.PLAYER_CHOSE;
        
        if (config.debugRPS) {
            WBUtilsClient.LOGGER.info("[RPSTracker] Player selected: {}", choice);
        }
    }
    
    private void handleNPCChoice(String choice) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        npcChoice = choice;
        currentPhase = GamePhase.NPC_CHOSE;
        
        if (config.debugRPS) {
            WBUtilsClient.LOGGER.info("[RPSTracker] NPC selected: {}", choice);
        }
    }
    
    private void handleGameResult(GameResult result) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        
        if (player == null) {
            resetGame();
            return;
        }
        
        synchronized (gameLock) {
            long now = System.currentTimeMillis();
            if (now - lastResultTime < RESULT_COOLDOWN_MS) {
                if (config.debugRPS) {
                    WBUtilsClient.LOGGER.info("[RPSTracker] Ignoring duplicate result - cooldown active ({} ms since last)", 
                        now - lastResultTime);
                }
                return;
            }
            
            if (currentPhase != GamePhase.PLAYER_CHOSE && currentPhase != GamePhase.NPC_CHOSE) {
                if (config.debugRPS) {
                    WBUtilsClient.LOGGER.info("[RPSTracker] Ignoring duplicate result - phase was: {}", currentPhase);
                }
                return;
            }
            
            String capturedPlayerChoice = playerChoice;
            String capturedNpcChoice = npcChoice;
            
            if (capturedPlayerChoice == null && capturedNpcChoice == null) {
                if (config.debugRPS) {
                    WBUtilsClient.LOGGER.info("[RPSTracker] Ignoring result - no choices captured");
                }
                resetGame();
                return;
            }
            
            lastResultTime = now;
            
            sessionGameCount++;
            currentPhase = GamePhase.COMPLETED;
            
            if (config.debugRPS) {
                WBUtilsClient.LOGGER.info("[RPSTracker] Game result: {} (Player: {}, NPC: {})", 
                    result, capturedPlayerChoice, capturedNpcChoice);
            }
            
            sendGameData(player, result, capturedPlayerChoice, capturedNpcChoice);
            
            this.lastNpcChoice = capturedNpcChoice;
            this.lastPlayerChoice = capturedPlayerChoice;
            this.lastResult = result;
            
            if (config.rpsShowFeedback) {
                String resultText = switch (result) {
                    case WIN -> Messages.get("rps.feedback.win");
                    case LOSS -> Messages.get("rps.feedback.loss");
                    case TIE -> Messages.get("rps.feedback.tie");
                };
                player.sendMessage(Text.literal(Messages.format("rps.feedback.session", "result", resultText, "count", String.valueOf(sessionGameCount))), false);
            }
            
            resetGame();
        }
    }
    
    private void resetGame() {
        currentPhase = GamePhase.IDLE;
        playerChoice = null;
        npcChoice = null;
        gameStartTime = 0;
    }
    
    /**
     * Send game data to the server for storage and analysis.
     * @param player The player entity
     * @param result The game result (WIN, LOSS, TIE)
     * @param pChoice The player's choice (captured before state reset)
     * @param nChoice The NPC's choice (captured before state reset)
     */
    private void sendGameData(ClientPlayerEntity player, GameResult result, String pChoice, String nChoice) {
        CompletableFuture.runAsync(() -> {
            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
            
            String uuid = player.getUuid().toString();
            String name = player.getGameProfile().getName();
            
            String urlStr = config.authServerUrl + "/rps/record";
            String json = String.format(
                "{\"minecraft_uuid\":\"%s\",\"minecraft_name\":\"%s\",\"session_id\":\"%s\"," +
                "\"player_choice\":\"%s\",\"npc_choice\":\"%s\",\"result\":\"%s\"," +
                "\"session_game_number\":%d,\"timestamp\":%d}",
                escapeJson(uuid),
                escapeJson(name),
                escapeJson(sessionId),
                escapeJson(pChoice != null ? pChoice : "UNKNOWN"),
                escapeJson(nChoice != null ? nChoice : "UNKNOWN"),
                result.name(),
                sessionGameCount,
                System.currentTimeMillis()
            );
            
            if (config.debugRPS || config.debugHttp) {
                WBUtilsClient.LOGGER.info("[RPSTracker] Sending game data: {}", json);
            }

            NetworkManager.post(urlStr, json, config.authToken)
                .thenAccept(response -> {
                    if (config.debugRPS || config.debugHttp) {
                        WBUtilsClient.LOGGER.info("[RPSTracker] Server response: {}", response.statusCode());
                    }
                    if (!response.isSuccess()) {
                        WBUtilsClient.LOGGER.warn("[RPSTracker] Failed to record game data: HTTP {}", response.statusCode());
                    }
                })
                .exceptionally(e -> {
                    WBUtilsClient.LOGGER.error("[RPSTracker] Failed to send game data", e);
                    return null;
                });
        });
    }
    

    public void fetchStats(Consumer<RPSStats> callback) {
        if (cachedStats != null && (System.currentTimeMillis() - lastStatsFetchTime) < STATS_CACHE_DURATION_MS) {
            callback.accept(cachedStats);
            return;
        }
        
        CompletableFuture.runAsync(() -> {
            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            
            if (player == null) {
                runOnMainThread(() -> callback.accept(null));
                return;
            }
            
            String uuid = player.getUuid().toString();
            
            String urlStr = config.authServerUrl + "/rps/stats/" + uuid;
            
            if (lastNpcChoice != null && lastPlayerChoice != null && lastResult != null) {
                urlStr += "?prev_npc=" + lastNpcChoice + 
                          "&prev_player=" + lastPlayerChoice + 
                          "&prev_result=" + lastResult.name();
            }

            NetworkManager.get(urlStr, true, config.authToken)
                .thenAccept(response -> {
                    if (!response.isSuccess()) {
                        runOnMainThread(() -> callback.accept(null));
                        return;
                    }
                    RPSStats stats = parseStatsJson(response.body());
                    cachedStats = stats;
                    lastStatsFetchTime = System.currentTimeMillis();
                    
                    runOnMainThread(() -> callback.accept(stats));
                })
                .exceptionally(e -> {
                    WBUtilsClient.LOGGER.error("[RPSTracker] Failed to fetch stats", e);
                    runOnMainThread(() -> callback.accept(null));
                    return null;
                });
        });
    }
    

    private RPSStats parseStatsJson(String json) {
        RPSStats stats = new RPSStats();
        
        try {
            stats.totalGames = extractInt(json, "total_games");
            stats.wins = extractInt(json, "wins");
            stats.losses = extractInt(json, "losses");
            stats.ties = extractInt(json, "ties");
            stats.globalTotalGames = extractInt(json, "global_total_games");
            stats.globalWins = extractInt(json, "global_wins");
            stats.globalLosses = extractInt(json, "global_losses");
            stats.globalTies = extractInt(json, "global_ties");
            stats.gamesUntilAnalytics = extractInt(json, "games_until_analytics");
            stats.analyticsAvailable = json.contains("\"analytics_available\":true");
            
            if (stats.analyticsAvailable) {
                stats.rockWinRate = extractDouble(json, "rock_win_rate");
                stats.paperWinRate = extractDouble(json, "paper_win_rate");
                stats.scissorsWinRate = extractDouble(json, "scissors_win_rate");
                stats.recommendedMove = extractString(json, "recommended_move");
                stats.npcRockRate = extractDouble(json, "npc_rock_rate");
                stats.npcPaperRate = extractDouble(json, "npc_paper_rate");
                stats.npcScissorsRate = extractDouble(json, "npc_scissors_rate");
                
                stats.situationalRecommendation = extractString(json, "situational_recommendation");
                stats.situationalConfidence = extractDouble(json, "situational_confidence");
                stats.situationalReasoning = extractString(json, "situational_reasoning");
                stats.predictedNpcMove = extractString(json, "predicted_npc_move");
            }
        } catch (Exception e) {
            WBUtilsClient.LOGGER.error("[RPSTracker] Failed to parse stats JSON", e);
        }
        
        return stats;
    }
    
    private int extractInt(String json, String key) {
        try {
            String pattern = "\"" + key + "\":\\s*(\\d+)";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        } catch (Exception ignored) {}
        return 0;
    }
    
    private double extractDouble(String json, String key) {
        try {
            String pattern = "\"" + key + "\":\\s*([\\d.]+)";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
            if (m.find()) {
                return Double.parseDouble(m.group(1));
            }
        } catch (Exception ignored) {}
        return 0.0;
    }
    
    private String extractString(String json, String key) {
        try {
            String pattern = "\"" + key + "\":\\s*\"([^\"]+)\"";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception ignored) {}
        return null;
    }
    
    private static String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
    
    private void runOnMainThread(Runnable runnable) {
        MinecraftClient.getInstance().execute(runnable);
    }
    
    public int getSessionGameCount() {
        return sessionGameCount;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public GamePhase getCurrentPhase() {
        return currentPhase;
    }
    

    public static class RPSStats {
        public int totalGames = 0;
        public int wins = 0;
        public int losses = 0;
        public int ties = 0;
        
        public int globalTotalGames = 0;
        public int globalWins = 0;
        public int globalLosses = 0;
        public int globalTies = 0;
        
        public int gamesUntilAnalytics = 100;
        public boolean analyticsAvailable = false;
        
        public double rockWinRate = 0.0;
        public double paperWinRate = 0.0;
        public double scissorsWinRate = 0.0;
        
        public double npcRockRate = 0.0;
        public double npcPaperRate = 0.0;
        public double npcScissorsRate = 0.0;
        
        public String recommendedMove = null;
        
        public String situationalRecommendation = null;
        public double situationalConfidence = 0.0;
        public String situationalReasoning = null;
        public String predictedNpcMove = null;
        
        public double getWinRate() {
            return totalGames > 0 ? (double) wins / totalGames * 100 : 0;
        }
        
        public double getLossRate() {
            return totalGames > 0 ? (double) losses / totalGames * 100 : 0;
        }
        
        public double getTieRate() {
            return totalGames > 0 ? (double) ties / totalGames * 100 : 0;
        }
        
        public double getGlobalWinRate() {
            return globalTotalGames > 0 ? (double) globalWins / globalTotalGames * 100 : 0;
        }
    }
}
