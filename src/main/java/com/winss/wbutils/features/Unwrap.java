package com.winss.wbutils.features;

import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.Messages;
import com.winss.wbutils.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/*
 * Unwrap Feature
 * 
 * Automatically teleports the player from "road lock" when trying to teleport.
 * This feature detects when this message appears after executing a teleport command,
 * then automatically sends /trade to "unwrap" and re-executes the original command.
 */
public class Unwrap {
    
    private static final String TELEPORT_BLOCKED_MESSAGE_MAP = "* You cannot teleport while you're in the Map!";
    private static final String TELEPORT_BLOCKED_MESSAGE_AFK = "* You were not teleported as you're in AFK!";
    private static final String TELEPORT_BLOCKED_MESSAGE_ROAD = "* You cannot be on the road when you want to do this!";
    
    private static final Set<String> DEFAULT_UNWRAP_COMMANDS = new HashSet<>(Arrays.asList(
        "spawn", "math", "leaderboard", "goafk", "rps", "trivia"
    ));
    
    private String lastCommand = null;
    private long lastCommandTime = 0;
    
    private static final long COMMAND_MEMORY_MS = 2000;
    
    private boolean unwrapPending = false;
    private String commandToRetry = null;
    private int ticksUntilRetry = 0;
    
    private static final int RETRY_DELAY_TICKS = 3;
    
    public Unwrap() {
        WBUtilsClient.LOGGER.info("[Unwrap] Initialized");
    }
    public void onCommandSent(String command) {
        if (command == null || command.isEmpty()) return;
        
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.unwrapEnabled) return;
        
        String baseCommand = command.split("\\s+")[0].toLowerCase();
        
        if (DEFAULT_UNWRAP_COMMANDS.contains(baseCommand)) {
            lastCommand = command;
            lastCommandTime = System.currentTimeMillis();
            
            if (config.debugUnwrap) {
                WBUtilsClient.LOGGER.info("[Unwrap] Tracked command: /{}", command);
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.sendMessage(Text.literal(Messages.format("unwrap.debug.command_tracked", "command", command)), false);
                }
            }
        }
    }

    public void handleChatMessage(Text message) {
        if (message == null) return;
        
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.unwrapEnabled) return;
        
        String plain = message.getString();
        if (plain == null) return;
        
        String stripped = plain.replaceAll("§[0-9a-fk-or]", "").trim();
        
        if (!stripped.equals(TELEPORT_BLOCKED_MESSAGE_MAP) && 
            !stripped.equals(TELEPORT_BLOCKED_MESSAGE_AFK) && 
            !stripped.equals(TELEPORT_BLOCKED_MESSAGE_ROAD)) {
            return;
        }
        
        if (lastCommand == null) {
            if (config.debugUnwrap) {
                WBUtilsClient.LOGGER.info("[Unwrap] Teleport blocked message received but no tracked command");
            }
            return;
        }
        
        long timeSinceCommand = System.currentTimeMillis() - lastCommandTime;
        if (timeSinceCommand > COMMAND_MEMORY_MS) {
            if (config.debugUnwrap) {
                WBUtilsClient.LOGGER.info("[Unwrap] Teleport blocked message received but command too old ({}ms)", timeSinceCommand);
            }
            lastCommand = null;
            return;
        }
        
        triggerUnwrap(lastCommand);
        lastCommand = null;
    }

    private void triggerUnwrap(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.player.networkHandler == null) return;
        
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        if (config.debugUnwrap) {
            WBUtilsClient.LOGGER.info("[Unwrap] Triggering unwrap for command: /{}", command);
            client.player.sendMessage(Text.literal(Messages.format("unwrap.triggered", "command", "/" + command)), false);
        }
        
        client.player.networkHandler.sendChatCommand("trade");
        
        if (config.debugUnwrap) {
            WBUtilsClient.LOGGER.info("[Unwrap] Sent /trade, will retry /{} in {} ticks", command, RETRY_DELAY_TICKS);
            client.player.sendMessage(Text.literal(Messages.format("unwrap.debug.trade_sent", "ticks", String.valueOf(RETRY_DELAY_TICKS))), false);
        }
        
        unwrapPending = true;
        commandToRetry = command;
        ticksUntilRetry = RETRY_DELAY_TICKS;
    }

    public void onClientTick() {
        if (!unwrapPending) return;
        
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.unwrapEnabled) {
            unwrapPending = false;
            commandToRetry = null;
            return;
        }
        
        ticksUntilRetry--;
        
        if (ticksUntilRetry <= 0) {
            executeRetry();
        }
    }

    private void executeRetry() {
        MinecraftClient client = MinecraftClient.getInstance();
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        if (client.player == null || client.player.networkHandler == null || commandToRetry == null) {
            unwrapPending = false;
            commandToRetry = null;
            return;
        }
        
        if (config.debugUnwrap) {
            WBUtilsClient.LOGGER.info("[Unwrap] Executing retry command: /{}", commandToRetry);
            client.player.sendMessage(Text.literal(Messages.format("unwrap.debug.retrying", "command", commandToRetry)), false);
        }
        
        client.player.networkHandler.sendChatCommand(commandToRetry);
        
        unwrapPending = false;
        commandToRetry = null;
    }

    public boolean isUnwrapPending() {
        return unwrapPending;
    }

    public void cancelUnwrap() {
        if (unwrapPending) {
            unwrapPending = false;
            commandToRetry = null;
            WBUtilsClient.LOGGER.info("[Unwrap] Unwrap sequence cancelled");
        }
    }

    public static Set<String> getUnwrapCommands() {
        return new HashSet<>(DEFAULT_UNWRAP_COMMANDS);
    }
}
