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
 * Shop helper utility - opens shop menu via hotbar slot 9 interaction,
 * then clicks slot 20 and then slot 3.
 */
public class ShopHelper {
    private static final int MENU_SLOT = 8;
    private static final int SHOP_SLOT_1 = 20;
    private static final int SHOP_SLOT_2 = 3;
    private static final int MAX_WAIT_TICKS = 20;

    private static final int CLICK_DELAY_BASE = 1;
    private static final int CLICK_DELAY_JITTER = 2;

    private static final Random random = new Random();

    private enum State {
        IDLE,
        WAITING_FOR_MENU,
        WAITING_FOR_CLICK_1,
        WAITING_FOR_SUB_MENU,
        WAITING_FOR_CLICK_2
    }

    private static State currentState = State.IDLE;
    private static int waitTicks = 0;
    private static int clickDelayTicks = 0;
    private static int lastSyncId = -1;

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

        currentState = State.WAITING_FOR_MENU;
        waitTicks = 0;
        clickDelayTicks = 0;
        lastSyncId = -1;

        WBUtilsClient.LOGGER.debug("[ShopHelper] Initiated - waiting for menu to open");
    }

    public static void onClientTick() {
        MinecraftClient client = MinecraftClient.getInstance();

        switch (currentState) {
            case WAITING_FOR_MENU -> {
                waitTicks++;
                if (client.currentScreen instanceof HandledScreen<?> handledScreen) {
                    lastSyncId = handledScreen.getScreenHandler().syncId;
                    currentState = State.WAITING_FOR_CLICK_1;
                    clickDelayTicks = getRandomizedDelay();
                    WBUtilsClient.LOGGER.debug("[ShopHelper] Menu opened (syncId: {}) - waiting {} ticks before first click", lastSyncId, clickDelayTicks);
                    return;
                }

                if (waitTicks >= MAX_WAIT_TICKS) {
                    currentState = State.IDLE;
                    WBUtilsClient.LOGGER.warn("[ShopHelper] Timeout waiting for menu to open");

                    if (client.player != null) {
                        client.player.sendMessage(
                            net.minecraft.text.Text.literal("§c[WBUtils] Shop menu didn't open. Make sure you have the menu item in slot 9."),
                            false
                        );
                    }
                }
            }
            case WAITING_FOR_CLICK_1 -> {
                if (clickDelayTicks > 0) {
                    clickDelayTicks--;
                    return;
                }

                if (client.currentScreen instanceof HandledScreen<?> handledScreen) {
                    lastSyncId = handledScreen.getScreenHandler().syncId;
                    clickSlotSafe(client, SHOP_SLOT_1);
                    currentState = State.WAITING_FOR_SUB_MENU;
                    waitTicks = 0; // Reset wait ticks for sub-menu timeout
                    WBUtilsClient.LOGGER.debug("[ShopHelper] Clicked slot {} - waiting for sub-menu screen update", SHOP_SLOT_1);
                } else {
                    currentState = State.IDLE;
                    WBUtilsClient.LOGGER.warn("[ShopHelper] Menu closed before first click could be performed");
                }
            }
            case WAITING_FOR_SUB_MENU -> {
                waitTicks++;
                if (client.currentScreen instanceof HandledScreen<?> handledScreen) {
                    int currentSyncId = handledScreen.getScreenHandler().syncId;
                    if (currentSyncId != lastSyncId) {
                        lastSyncId = currentSyncId;
                        currentState = State.WAITING_FOR_CLICK_2;
                        clickDelayTicks = getRandomizedDelay();
                        WBUtilsClient.LOGGER.debug("[ShopHelper] Sub-menu opened (new syncId: {}) - waiting {} ticks before second click", lastSyncId, clickDelayTicks);
                        return;
                    }
                }

                if (waitTicks >= MAX_WAIT_TICKS) {
                    currentState = State.IDLE;
                    WBUtilsClient.LOGGER.warn("[ShopHelper] Timeout waiting for sub-menu to open (stayed on syncId: {})", lastSyncId);
                }
            }
            case WAITING_FOR_CLICK_2 -> {
                if (clickDelayTicks > 0) {
                    clickDelayTicks--;
                    return;
                }

                if (client.currentScreen instanceof HandledScreen<?>) {
                    clickSlotSafe(client, SHOP_SLOT_2);
                    currentState = State.IDLE;
                    WBUtilsClient.LOGGER.debug("[ShopHelper] Clicked slot {} - Task complete", SHOP_SLOT_2);
                } else {
                    currentState = State.IDLE;
                    WBUtilsClient.LOGGER.warn("[ShopHelper] Menu closed before second click could be performed");
                }
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

        WBUtilsClient.LOGGER.debug("[ShopHelper] Clicked slot {} in screen with syncId {}", slotId, syncId);
    }

    public static boolean isWaiting() {
        return currentState != State.IDLE;
    }

    public static void cancel() {
        currentState = State.IDLE;
        waitTicks = 0;
        clickDelayTicks = 0;
        lastSyncId = -1;
    }
}
