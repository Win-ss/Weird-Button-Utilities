package com.winss.wbutils.features;

import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.Messages;
import com.winss.wbutils.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import com.winss.wbutils.network.NetworkManager;

public class AutoRejoin {
    
    private static final int TIMEOUT_MS = 10000;
    private static final long DISCONNECT_MESSAGES_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    
    private final Random random = new Random();
    
    private static final int RECONNECT_DELAY_BASE_TICKS = 5 * 20;
    private static final int RECONNECT_DELAY_JITTER_TICKS = 5 * 20;
    private static final int SPAWN_DELAY_BASE_TICKS = 20; 
    private static final int SPAWN_DELAY_JITTER_TICKS = 20; 
    private static final int HOUSING_LOAD_DELAY_BASE_TICKS = 3 * 20;
    private static final int HOUSING_LOAD_DELAY_JITTER_TICKS = 1 * 20;
    private static final int VISIT_DELAY_BASE_TICKS = 20;
    
    private final Set<String> disconnectIndicators = new HashSet<>();
    private long lastDisconnectMessagesFetch = 0L;
    private boolean isFetching = false;
    
    private boolean wasInHousing = false;
    private boolean intentionalDisconnect = false;
    private boolean rejoinInProgress = false;
    private boolean isLimboRejoin = false; 
    private RejoinState currentState = RejoinState.IDLE;
    private int stateDelayTicks = 0;
    private int stateTimeoutTicks = 0;
    private static final int STATE_TIMEOUT_MAX = 60 * 20;

    private boolean waitingForWorldLoad = false;
    
    public enum RejoinState {
        IDLE,
        // For Limbo 
        WAITING_FOR_LIMBO_ESCAPE,
        SENDING_LOBBY,
        WAITING_FOR_LOBBY,
        // For true disconnect
        WAITING_TO_RECONNECT,
        RECONNECTING,
        WAITING_FOR_SPAWN,
        // Common flow
        SENDING_HOUSING_COMMAND,
        WAITING_FOR_HOUSING_LOAD,
        SENDING_VISIT_COMMAND,
        COMPLETED,
        FAILED
    }
    
    public AutoRejoin() {
        WBUtilsClient.LOGGER.info("[AutoRejoin] Initialized");
        disconnectIndicators.add("/limbo for more information.");
        disconnectIndicators.add("You were spawned in Limbo");
        disconnectIndicators.add("You were kicked whilst connecting");
        disconnectIndicators.add("Connection throttled!");
        disconnectIndicators.add("You are already connecting to this server!");
        disconnectIndicators.add("Internal Exception:");
        disconnectIndicators.add("Timed out");
        disconnectIndicators.add("Connection Lost");
    }
    

    public void onClientTick() {
        MinecraftClient client = MinecraftClient.getInstance();
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        if (!config.autoRejoinEnabled) {
            if (rejoinInProgress) {
                cancelRejoin("Feature disabled");
            }
            return;
        }
        
        if (client.player != null && WBUtilsClient.getHousingDetector() != null && !rejoinInProgress) {
            wasInHousing = WBUtilsClient.getHousingDetector().isInDptb2Housing();
        }
        
        if (rejoinInProgress) {
            processRejoinState(client, config);
        }
    }
    

    public void captureHousingState(boolean inHousing) {
        if (inHousing && !rejoinInProgress) {
            wasInHousing = true;
            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
            if (config.debugAutoRejoin) {
                WBUtilsClient.LOGGER.info("[AutoRejoin] Housing state captured: wasInHousing = true");
            }
        }
    }
    

    public void onIntentionalDisconnect() {
        intentionalDisconnect = true;
        wasInHousing = false;
        if (rejoinInProgress) {
            cancelRejoin("Intentional disconnect");
        }
        WBUtilsClient.LOGGER.info("[AutoRejoin] Intentional disconnect detected");
    }
    

    public boolean onDisconnected(Text reason) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        if (!config.autoRejoinEnabled) {
            resetState();
            return false;
        }
        
        if (intentionalDisconnect) {
            intentionalDisconnect = false;
            wasInHousing = false;
            WBUtilsClient.LOGGER.info("[AutoRejoin] Skipping - intentional disconnect");
            return false;
        }
        
        if (!wasInHousing) {
            WBUtilsClient.LOGGER.info("[AutoRejoin] Skipping - was not in housing");
            resetState();
            return false;
        }
        
        String reasonStr = reason != null ? reason.getString() : "";
        String strippedReason = reasonStr.replaceAll("§[0-9a-fk-or]", "").trim();
        
        boolean isNetworkDisconnect = isNetworkDisconnect(strippedReason);
        
        if (config.debugAutoRejoin) {
            WBUtilsClient.LOGGER.info("[AutoRejoin] Disconnect detected:");
            WBUtilsClient.LOGGER.info("[AutoRejoin]   Reason: {}", strippedReason);
            WBUtilsClient.LOGGER.info("[AutoRejoin]   Was in housing: {}", wasInHousing);
            WBUtilsClient.LOGGER.info("[AutoRejoin]   Is network disconnect: {}", isNetworkDisconnect);
        }
        
        if (!isNetworkDisconnect) {
            WBUtilsClient.LOGGER.info("[AutoRejoin] Skipping - not a network disconnect");
            resetState();
            return false;
        }
        
        startRejoin();
        return true;
    }

    private boolean isNetworkDisconnect(String reason) {
        if (reason == null || reason.isEmpty()) {
            return true;
        }
        
        String lowerReason = reason.toLowerCase();
        
        for (String indicator : disconnectIndicators) {
            if (lowerReason.contains(indicator.toLowerCase())) {
                return true;
            }
        }
        
        if (lowerReason.contains("timed out") ||
            lowerReason.contains("connection lost") ||
            lowerReason.contains("connection reset") ||
            lowerReason.contains("internal exception") ||
            lowerReason.contains("read timed out") ||
            lowerReason.contains("connection refused") ||
            lowerReason.contains("network") ||
            lowerReason.contains("io exception") ||
            lowerReason.contains("permission denied") ||
            lowerReason.contains("getsockopt") ||
            lowerReason.contains("limbo")) {
            return true;
        }
        
        return false;
    }
    

    public boolean isGenuineDisconnectMessage(Text message) {
        if (message == null) return false;
        
        String plain = message.getString();
        if (plain == null || plain.isEmpty()) return false;
        
        String stripped = plain.replaceAll("§[0-9a-fk-or]", "").trim();
        
        if (!isGenuineSystemMessage(message, stripped)) {
            return false;
        }
        
        for (String indicator : disconnectIndicators) {
            if (stripped.equalsIgnoreCase(indicator.trim())) {
                return true;
            }
        }
        
        return false;
    }
    private boolean isGenuineSystemMessage(Text message, String stripped) {
        if (message == null || stripped == null) return false;
        
        String plain = message.getString();
        if (plain == null) return false;

        boolean hasAquaColor = plain.startsWith("§b") || 
            plain.contains("§b") ||
            (message.getStyle() != null && 
             message.getStyle().getColor() != null && 
             "aqua".equals(message.getStyle().getColor().getName()));
        

        boolean hasPlayerPrefix = stripped.matches("^(\\[[^\\]]+\\]\\s*)*[A-Za-z0-9_]{1,16}:\\s.*");
        
        if (hasPlayerPrefix) {
            return false;
        }
        
        if (stripped.toLowerCase().contains("limbo")) {
            return hasAquaColor;
        }
        

        return true;
    }
    

    public boolean handleChatMessage(Text message) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        if (!config.autoRejoinEnabled) {
            return false;
        }
        
        if (rejoinInProgress) {
            return false;
        }
        
        if (message == null) return false;
        
        String plain = message.getString();
        if (plain == null || plain.isEmpty()) return false;
        
        String stripped = plain.replaceAll("§[0-9a-fk-or]", "").trim();
        
        String matchedIndicator = null;
        for (String indicator : disconnectIndicators) {
            if (stripped.equalsIgnoreCase(indicator.trim())) {
                matchedIndicator = indicator;
                break;
            }
        }
        
        if (matchedIndicator == null) {
            return false;
        }
        
        if (!isGenuineSystemMessage(message, stripped)) {
            if (config.debugAutoRejoin) {
                WBUtilsClient.LOGGER.info("[AutoRejoin] Indicator detected but failed system message check (possible troll): {}", plain);
            }
            return false;
        }
        
        if (!wasInHousing) {
            if (config.debugAutoRejoin) {
                WBUtilsClient.LOGGER.info("[AutoRejoin] Chat indicator detected but was not in housing: {}", stripped);
            }
            return false;
        }
        
        if (config.debugAutoRejoin) {
            WBUtilsClient.LOGGER.info("[AutoRejoin] Network disconnect indicator detected via chat!");
            WBUtilsClient.LOGGER.info("[AutoRejoin]   Message: {}", stripped);
            WBUtilsClient.LOGGER.info("[AutoRejoin]   Was in housing: {}", wasInHousing);
        }
        
        startLimboRejoin();
        return true;
    }
    

    private void startRejoin() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        rejoinInProgress = true;
        isLimboRejoin = false;
        currentState = RejoinState.WAITING_TO_RECONNECT;
        stateDelayTicks = getRandomizedDelay(RECONNECT_DELAY_BASE_TICKS, RECONNECT_DELAY_JITTER_TICKS);
        stateTimeoutTicks = 0;
        
        int delaySeconds = stateDelayTicks / 20;
        WBUtilsClient.LOGGER.info("[AutoRejoin] Starting DISCONNECT rejoin sequence in {} seconds", delaySeconds);
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(Messages.format("autorejoin.starting.delay", "seconds", String.valueOf(delaySeconds))), false);
        }
    }
    

    private void startLimboRejoin() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        rejoinInProgress = true;
        isLimboRejoin = true;
        currentState = RejoinState.WAITING_FOR_LIMBO_ESCAPE;
        stateDelayTicks = getRandomizedDelay(1 * 20, 1 * 20);
        stateTimeoutTicks = 0;
        
        int delaySeconds = stateDelayTicks / 20;
        WBUtilsClient.LOGGER.info("[AutoRejoin] Starting LIMBO rejoin sequence in {} seconds", delaySeconds);
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(Messages.format("autorejoin.limbo.starting", "seconds", String.valueOf(delaySeconds))), false);
        }
    }
    

    private int getRandomizedDelay(int baseTicks, int jitterTicks) {
        return baseTicks + random.nextInt(jitterTicks + 1);
    }
    

    public void cancelRejoin(String reason) {
        if (!rejoinInProgress) return;
        
        WBUtilsClient.LOGGER.info("[AutoRejoin] Rejoin cancelled: {}", reason);
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(Messages.format("autorejoin.cancelled", "reason", reason)), false);
        }
        
        resetState();
    }
    

    private void resetState() {
        rejoinInProgress = false;
        isLimboRejoin = false;
        currentState = RejoinState.IDLE;
        stateDelayTicks = 0;
        stateTimeoutTicks = 0;
        wasInHousing = false;
        intentionalDisconnect = false;
        waitingForWorldLoad = false;
    }
    
    private ModConfig config() {
        return WBUtilsClient.getConfigManager().getConfig();
    }

    private void processRejoinState(MinecraftClient client, ModConfig config) {
        if (stateDelayTicks > 0) {
            stateDelayTicks--;
            return;
        }
        
        stateTimeoutTicks++;
        if (stateTimeoutTicks > STATE_TIMEOUT_MAX) {
            cancelRejoin("State timeout: " + currentState);
            return;
        }
        
        switch (currentState) {
            case WAITING_FOR_LIMBO_ESCAPE:
                handleWaitingForLimboEscape(client);
                break;
            case SENDING_LOBBY:
                handleSendingLobby(client);
                break;
            case WAITING_FOR_LOBBY:
                handleWaitingForLobby(client);
                break;
            case WAITING_TO_RECONNECT:
                handleWaitingToReconnect(client);
                break;
            case RECONNECTING:
                handleReconnecting(client);
                break;
            case WAITING_FOR_SPAWN:
                handleWaitingForSpawn(client);
                break;
            case SENDING_HOUSING_COMMAND:
                handleSendingHousingCommand(client);
                break;
            case WAITING_FOR_HOUSING_LOAD:
                handleWaitingForHousingLoad(client);
                break;
            case SENDING_VISIT_COMMAND:
                handleSendingVisitCommand(client);
                break;
            case COMPLETED:
                handleCompleted(client);
                break;
            case FAILED:
                resetState();
                break;
            default:
                break;
        }
    }
    
    
    private void handleWaitingForLimboEscape(MinecraftClient client) {
        if (client.player != null) {
            currentState = RejoinState.SENDING_LOBBY;
            stateTimeoutTicks = 0;
        }
    }
    
    private void handleSendingLobby(MinecraftClient client) {
        if (client.player == null) return;
        
        if (config().debugAutoRejoin) {
            WBUtilsClient.LOGGER.info("[AutoRejoin] Sending /lobby to escape Limbo");
        }
        
        client.player.networkHandler.sendChatCommand("lobby");
        
        currentState = RejoinState.WAITING_FOR_LOBBY;
        stateDelayTicks = getRandomizedDelay(SPAWN_DELAY_BASE_TICKS, SPAWN_DELAY_JITTER_TICKS);
        stateTimeoutTicks = 0;
        
        if (config().debugAutoRejoin) {
            WBUtilsClient.LOGGER.info("[AutoRejoin] Waiting {} seconds for lobby", stateDelayTicks / 20);
        }
    }
    
    private void handleWaitingForLobby(MinecraftClient client) {
        if (client.player != null) {
            currentState = RejoinState.SENDING_HOUSING_COMMAND;
            stateTimeoutTicks = 0;
        }
    }
    
    
    private void handleWaitingToReconnect(MinecraftClient client) {
        if (config().debugAutoRejoin) {
            WBUtilsClient.LOGGER.info("[AutoRejoin] Initiating reconnect to Hypixel");
        }
        
        ServerAddress serverAddress = ServerAddress.parse("mc.hypixel.net");
        ServerInfo serverInfo = new ServerInfo("Hypixel", "mc.hypixel.net", ServerInfo.ServerType.OTHER);
        
        ConnectScreen.connect(client.currentScreen, client, serverAddress, serverInfo, false, null);
        
        currentState = RejoinState.RECONNECTING;
        stateDelayTicks = 0;
        stateTimeoutTicks = 0;
    }
    
    private void handleReconnecting(MinecraftClient client) {
        if (client.player != null && client.world != null) {
            currentState = RejoinState.WAITING_FOR_SPAWN;
            stateDelayTicks = getRandomizedDelay(SPAWN_DELAY_BASE_TICKS, SPAWN_DELAY_JITTER_TICKS);
            stateTimeoutTicks = 0;
            
            if (config().debugAutoRejoin) {
                WBUtilsClient.LOGGER.info("[AutoRejoin] Connected to server, waiting {} seconds before sending commands", 
                    stateDelayTicks / 20);
            }
        }
    }
    
    private void handleWaitingForSpawn(MinecraftClient client) {
        if (client.player != null) {
            currentState = RejoinState.SENDING_HOUSING_COMMAND;
            stateTimeoutTicks = 0;
        }
    }
    
    
    private void handleSendingHousingCommand(MinecraftClient client) {
        if (client.player == null) return;
        
        if (config().debugAutoRejoin) {
            WBUtilsClient.LOGGER.info("[AutoRejoin] Sending /l housing");
        }
        
        client.player.networkHandler.sendChatCommand("l housing");
        
        currentState = RejoinState.WAITING_FOR_HOUSING_LOAD;
        stateDelayTicks = getRandomizedDelay(HOUSING_LOAD_DELAY_BASE_TICKS, HOUSING_LOAD_DELAY_JITTER_TICKS);
        stateTimeoutTicks = 0;
        waitingForWorldLoad = true; 
        
        if (config().debugAutoRejoin) {
            WBUtilsClient.LOGGER.info("[AutoRejoin] Waiting {} seconds for housing world to load", stateDelayTicks / 20);
        }
    }
    
    private void handleWaitingForHousingLoad(MinecraftClient client) {
        if (client.player != null) {
            currentState = RejoinState.SENDING_VISIT_COMMAND;
            stateTimeoutTicks = 0;
        }
    }
    
    private void handleSendingVisitCommand(MinecraftClient client) {
        if (client.player == null) return;
        
        String command = "visit cyborg023 don't press the button";
        
        if (config().debugAutoRejoin) {
            WBUtilsClient.LOGGER.info("[AutoRejoin] Sending /{}", command);
        }
        
        client.player.networkHandler.sendChatCommand(command);
        
        currentState = RejoinState.COMPLETED;
        stateDelayTicks = 20; 
        stateTimeoutTicks = 0;
    }

    private void handleCompleted(MinecraftClient client) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(Messages.get("autorejoin.success")), false);
        }
        
        WBUtilsClient.LOGGER.info("[AutoRejoin] Rejoin sequence completed successfully");
        resetState();
    }

    public void fetchDisconnectMessages(Consumer<Boolean> callback) {
        if (isFetching) {
            callback.accept(false);
            return;
        }
        
        isFetching = true;
        CompletableFuture.runAsync(() -> {
            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
            
            if (config.authServerUrl == null || config.authServerUrl.isBlank()) {
                isFetching = false;
                runOnMainThread(() -> callback.accept(false));
                return;
            }
            
            String urlStr = config.authServerUrl + "/autorejoin/disconnect-messages";
            NetworkManager.get(urlStr, true, config.authToken)
                .thenAccept(response -> {
                    isFetching = false;
                    lastDisconnectMessagesFetch = System.currentTimeMillis();

                    if (!response.isSuccess()) {
                        if (config.debugAutoRejoin) {
                            WBUtilsClient.LOGGER.warn("[AutoRejoin] Failed to fetch disconnect messages: HTTP {}", response.statusCode());
                        }
                        runOnMainThread(() -> callback.accept(false));
                        return;
                    }
                    
                    String jsonStr = response.body().trim();
                if (jsonStr.startsWith("[") && jsonStr.endsWith("]")) {
                    Set<String> newIndicators = new HashSet<>();
                    
                    String content = jsonStr.substring(1, jsonStr.length() - 1);
                    if (!content.isEmpty()) {
                        String[] parts = content.split(",");
                        for (String part : parts) {
                            String cleaned = part.trim();
                            if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                                String msg = cleaned.substring(1, cleaned.length() - 1);
                                msg = msg.replace("\\\"", "\"").replace("\\\\", "\\");
                                if (!msg.isEmpty()) {
                                    newIndicators.add(msg);
                                }
                            }
                        }
                    }
                    
                    runOnMainThread(() -> {
                        disconnectIndicators.addAll(newIndicators);
                        if (config.debugAutoRejoin) {
                            WBUtilsClient.LOGGER.info("[AutoRejoin] Updated disconnect indicators. Total: {}", disconnectIndicators.size());
                        }
                        callback.accept(true);
                    });
                } else {
                    runOnMainThread(() -> callback.accept(false));
                }
            }).exceptionally(e -> {
                isFetching = false;
                runOnMainThread(() -> callback.accept(false));
                return null;
            });
        });
    }
    
    public boolean isRejoinInProgress() {
        return rejoinInProgress;
    }

    public RejoinState getCurrentState() {
        return currentState;
    }

    public boolean wasPlayerInHousing() {
        return wasInHousing;
    }

    public long getLastDisconnectMessagesFetch() {
        return lastDisconnectMessagesFetch;
    }

    public void forceRefreshDisconnectMessages(Consumer<Boolean> callback) {
        fetchDisconnectMessages(callback);
    }

    public int getDisconnectIndicatorCount() {
        return disconnectIndicators.size();
    }

    public void refreshDisconnectMessagesIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastDisconnectMessagesFetch > DISCONNECT_MESSAGES_REFRESH_INTERVAL_MS) {
            fetchDisconnectMessages(success -> {
                if (success) {
                }
            });
        }
    }

    private void runOnMainThread(Runnable action) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(action);
    }
}
