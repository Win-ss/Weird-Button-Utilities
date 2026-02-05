package com.winss.wbutils.features;

import com.winss.wbutils.WBUtilsClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

import java.util.Random;

/**
 * Quest helper utility - opens quest menu via hotbar slot 9 interaction.
 */
public class QuestHelper {
    private static final int MENU_SLOT = 8;
    private static final int QUEST_SLOT = 21;
    private static final int MAX_WAIT_TICKS = 40;

    private static final int CLICK_DELAY_BASE = 8;  
    private static final int CLICK_DELAY_JITTER = 12;
    
    private static final Random random = new Random();
    
    private static boolean waitingForMenu = false;
    private static boolean waitingToClick = false;
    private static int waitTicks = 0;
    private static int clickDelayTicks = 0;
    
    public static void execute() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        
        if (player == null || client.interactionManager == null) {
            return;
        }
        
        int previousSlot = player.getInventory().selectedSlot;
        player.getInventory().selectedSlot = MENU_SLOT;
        
        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        
        player.getInventory().selectedSlot = previousSlot;
        
        waitingForMenu = true;
        waitingToClick = false;
        waitTicks = 0;
        clickDelayTicks = 0;
        
        WBUtilsClient.LOGGER.debug("[QuestHelper] Initiated - waiting for menu to open");
    }

    public static void onClientTick() {
        MinecraftClient client = MinecraftClient.getInstance();
        
        if (waitingForMenu && !waitingToClick) {
            waitTicks++;
            
            if (client.currentScreen instanceof HandledScreen<?> handledScreen) {
                waitingForMenu = false;
                waitingToClick = true;
                clickDelayTicks = getRandomizedDelay();
                
                WBUtilsClient.LOGGER.debug("[QuestHelper] Menu opened - waiting {} ticks before clicking", clickDelayTicks);
                return;
            }
            
            if (waitTicks >= MAX_WAIT_TICKS) {
                waitingForMenu = false;
                WBUtilsClient.LOGGER.warn("[QuestHelper] Timeout waiting for menu to open");
                
                if (client.player != null) {
                    client.player.sendMessage(
                        net.minecraft.text.Text.literal("§c[WBUtils] Quest menu didn't open. Make sure you have the menu item in slot 9."),
                        false
                    );
                }
            }
            return;
        }
        
        if (waitingToClick) {
            if (clickDelayTicks > 0) {
                clickDelayTicks--;
                return;
            }
            
            waitingToClick = false;
            
            if (client.currentScreen instanceof HandledScreen<?>) {
                clickSlotSafe(client, QUEST_SLOT);
                WBUtilsClient.LOGGER.debug("[QuestHelper] Clicked slot {} after delay", QUEST_SLOT);
            } else {
                WBUtilsClient.LOGGER.warn("[QuestHelper] Menu closed before click could be performed");
            }
        }
    }

    private static int getRandomizedDelay() {
        return CLICK_DELAY_BASE + random.nextInt(CLICK_DELAY_JITTER + 1);
    }
    
    private static void clickSlotSafe(MinecraftClient client, int slotId) {
        if (!(client.currentScreen instanceof HandledScreen<?> handledScreen)) {
            return;
        }
        
        ClientPlayerInteractionManager interactionManager = client.interactionManager;
        ClientPlayerEntity player = client.player;
        
        if (interactionManager == null || player == null) {
            return;
        }
        
        int syncId = handledScreen.getScreenHandler().syncId;
        
        interactionManager.clickSlot(syncId, slotId, 0, SlotActionType.PICKUP, player);
        
        WBUtilsClient.LOGGER.debug("[QuestHelper] Clicked slot {} in screen with syncId {}", slotId, syncId);
    }

    public static boolean isWaiting() {
        return waitingForMenu || waitingToClick;
    }

    public static void cancel() {
        waitingForMenu = false;
        waitingToClick = false;
        waitTicks = 0;
        clickDelayTicks = 0;
    }
}
