package com.winss.wbutils.features;

import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.Messages;
import com.winss.wbutils.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * AutoRPS
 * 
 * Modes:
 * - RANDOM
 * - ANALYTICAL: Uses statistics to make an informed choice (requires 100+ games played)
 * 
 * The feature detects when the RPS GUI is opened,
 * automatically clicks the appropriate slot after a small delay to appear natural.
 * 
 * Item detection:
 * - Stone (cobblestone) = Rock
 * - Paper = Paper  
 * - Shears = Scissors
 */
public class AutoRPS {
    
    public enum Mode {
        OFF,
        RANDOM,
        ANALYTICAL
    }
    
    private static final String RPS_SCREEN_TITLE = "Rock, Paper, Scissors";
    private static final Random random = new Random();
    
    private static final int MIN_DELAY_TICKS = 5;
    private static final int MAX_DELAY_TICKS = 15;
    
    private boolean rpsScreenOpen = false;
    private int delayTicks = 0;
    private int currentDelayTarget = 0;
    private String pendingChoice = null;
    private boolean hasClicked = false;
    
    private RPSTracker.RPSStats cachedStats = null;
    private boolean fetchingStats = false;
    
    public AutoRPS() {
        WBUtilsClient.LOGGER.info("[AutoRPS] Initialized");
    }

    public void onClientTick() {
        MinecraftClient client = MinecraftClient.getInstance();
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        if (!config.autoRPSEnabled || config.autoRPSMode == Mode.OFF) {
            resetState();
            return;
        }
        
        if (client.currentScreen instanceof HandledScreen<?> handledScreen) {
            String title = handledScreen.getTitle().getString();
            
            if (title.equals(RPS_SCREEN_TITLE)) {
                handleRPSScreen(client, handledScreen, config);
            } else {
                if (rpsScreenOpen) {
                    resetState();
                }
            }
        } else {
            if (rpsScreenOpen) {
                resetState();
            }
        }
    }

    private void handleRPSScreen(MinecraftClient client, HandledScreen<?> handledScreen, ModConfig config) {
        if (!rpsScreenOpen) {
            rpsScreenOpen = true;
            hasClicked = false;
            delayTicks = 0;
            
            currentDelayTarget = MIN_DELAY_TICKS + random.nextInt(MAX_DELAY_TICKS - MIN_DELAY_TICKS + 1);
            
            if (config.debugAutoRPS) {
                WBUtilsClient.LOGGER.info("[AutoRPS] RPS screen detected! Waiting {} ticks before action", currentDelayTarget);
            }
            
            determineChoice(config);
        }
        
        if (hasClicked) {
            return;
        }
        
        delayTicks++;
        if (delayTicks < currentDelayTarget) {
            return;
        }
        
        if (pendingChoice == null) {
            if (config.debugAutoRPS) {
                WBUtilsClient.LOGGER.info("[AutoRPS] No choice determined yet, waiting...");
            }
            return;
        }
        
        performClick(client, handledScreen, config);
    }
    private void determineChoice(ModConfig config) {
        if (config.autoRPSMode == Mode.RANDOM) {
            String[] choices = {"ROCK", "PAPER", "SCISSORS"};
            pendingChoice = choices[random.nextInt(3)];
            
            if (config.debugAutoRPS) {
                WBUtilsClient.LOGGER.info("[AutoRPS] Random mode selected: {}", pendingChoice);
            }
        } else if (config.autoRPSMode == Mode.ANALYTICAL) {
            if (!fetchingStats) {
                fetchingStats = true;
                
                WBUtilsClient.getRPSTracker().fetchStats(stats -> {
                    fetchingStats = false;
                    cachedStats = stats;
                    
                    if (stats != null && stats.analyticsAvailable && stats.totalGames >= 10) {
                        if (stats.situationalRecommendation != null && !stats.situationalRecommendation.isEmpty()) {
                            pendingChoice = stats.situationalRecommendation.toUpperCase();
                            if (config.debugAutoRPS) {
                                WBUtilsClient.LOGGER.info("[AutoRPS] Level 2 Situational - Using: {} (Reason: {})", pendingChoice, stats.situationalReasoning);
                            }
                        } else if (stats.recommendedMove != null && !stats.recommendedMove.isEmpty()) {
                            pendingChoice = stats.recommendedMove.toUpperCase();
                            if (config.debugAutoRPS) {
                                WBUtilsClient.LOGGER.info("[AutoRPS] Level 1 Global - Using recommended move: {}", pendingChoice);
                            }
                        } else {
                            pendingChoice = calculateBestMove(stats);
                            if (config.debugAutoRPS) {
                                WBUtilsClient.LOGGER.info("[AutoRPS] Level 1 Global - Calculated best move: {}", pendingChoice);
                            }
                        }
                    } else {
                        String[] choices = {"ROCK", "PAPER", "SCISSORS"};
                        pendingChoice = choices[random.nextInt(3)];
                        
                        int gamesNeeded = stats != null ? stats.gamesUntilAnalytics : 100;
                        if (config.debugAutoRPS) {
                            WBUtilsClient.LOGGER.info("[AutoRPS] Analytical mode unavailable (need {} more games), using random: {}", 
                                gamesNeeded, pendingChoice);
                        }
                        
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client.player != null && config.autoRPSShowFeedback) {
                            client.player.sendMessage(Text.literal(Messages.get("autorps.feedback.no_analytics")), false);
                        }
                    }
                });
            }
        }
    }

    private String calculateBestMove(RPSTracker.RPSStats stats) {
        double npcRock = stats.npcRockRate;
        double npcPaper = stats.npcPaperRate;
        double npcScissors = stats.npcScissorsRate;
        
        if (npcRock >= npcPaper && npcRock >= npcScissors) {
            return "PAPER";
        } else if (npcPaper >= npcRock && npcPaper >= npcScissors) {
            return "SCISSORS";
        } else {
            return "ROCK";
        }
    }

    private void performClick(MinecraftClient client, HandledScreen<?> handledScreen, ModConfig config) {
        ScreenHandler handler = handledScreen.getScreenHandler();
        ClientPlayerInteractionManager interactionManager = client.interactionManager;
        ClientPlayerEntity player = client.player;
        
        if (interactionManager == null || player == null) {
            return;
        }
        
        int targetSlot = findSlotForChoice(handler, pendingChoice);
        
        if (targetSlot == -1) {
            if (config.debugAutoRPS) {
                WBUtilsClient.LOGGER.warn("[AutoRPS] Could not find slot for choice: {}", pendingChoice);
            }
            return;
        }
        
        int syncId = handler.syncId;
        interactionManager.clickSlot(syncId, targetSlot, 0, SlotActionType.PICKUP, player);
        hasClicked = true;
        
        if (config.debugAutoRPS) {
            WBUtilsClient.LOGGER.info("[AutoRPS] Clicked slot {} for choice {}", targetSlot, pendingChoice);
        }
        
        if (config.autoRPSShowFeedback) {
            String choiceColor = switch (pendingChoice) {
                case "ROCK" -> "§7";
                case "PAPER" -> "§f";
                case "SCISSORS" -> "§c";
                default -> "§b";
            };
            String modeStr = config.autoRPSMode == Mode.ANALYTICAL ? "Analytical" : "Random";
            
            player.sendMessage(Text.literal(Messages.format("autorps.feedback.selected", 
                "color", choiceColor, "choice", pendingChoice, "mode", modeStr)), false);
            
            if (config.autoRPSMode == Mode.ANALYTICAL && cachedStats != null && cachedStats.situationalRecommendation != null) {
                if (cachedStats.situationalReasoning != null) {
                    player.sendMessage(Text.literal(Messages.format("autorps.feedback.reason", "reason", cachedStats.situationalReasoning)), false);
                }
            }
        }
    }
    
    /**
     * Find the slot index containing the item for the given choice.
     * 
     * @param handler The screen handler
     * @param choice ROCK, PAPER, or SCISSORS
     * @return The slot index, or -1 if not found
     */
    private int findSlotForChoice(ScreenHandler handler, String choice) {
        List<Slot> slots = handler.slots;
        
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            ItemStack stack = slot.getStack();
            
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            
            boolean matches = switch (choice) {
                case "ROCK" -> stack.getItem() == Items.COBBLESTONE || stack.getItem() == Items.STONE;
                case "PAPER" -> stack.getItem() == Items.PAPER;
                case "SCISSORS" -> stack.getItem() == Items.SHEARS;
                default -> false;
            };
            
            if (matches) {
                return i;
            }
        }
        
        return -1;
    }
    

    private void resetState() {
        rpsScreenOpen = false;
        delayTicks = 0;
        currentDelayTarget = 0;
        pendingChoice = null;
        hasClicked = false;
    }
    
    public static String getModeDisplayString(Mode mode) {
        return switch (mode) {
            case OFF -> Messages.get("status.off");
            case RANDOM -> "§aRANDOM";
            case ANALYTICAL -> "§bANALYTICAL";
        };
    }

    public static Mode cycleMode(Mode current) {
        return switch (current) {
            case OFF -> Mode.RANDOM;
            case RANDOM -> Mode.ANALYTICAL;
            case ANALYTICAL -> Mode.OFF;
        };
    }
}
