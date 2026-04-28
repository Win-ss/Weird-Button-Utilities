package com.winss.wbutils.features;

import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

import java.util.Random;

/**
 * AutoBuy - Automated shop purchasing to avoid the 30% bulk-buy tax.
 * Usage: /wbutils auto buy <item> <quantity>
 * Alias: /abuy <item> <quantity>
 */
public class AutoBuy {

    // ─── Buyable Items ──────────────────────────────────────────────

    public enum BuyableItem {
        IMMUNITY_APPLE("immunity_apple", Category.ITEMS,
            new String[]{"Immune apple"}, "Immune Apple"),
        REMOTE("remote", Category.ITEMS,
            new String[]{"Remote Activation"}, "Remote"),
        JUMP_4("jump_4", Category.POWERUPS,
            new String[]{"Jump boost", "Jump boost II", "Jump boost III", "Jump boost IV"},
            "Jump Boost IV"),
        SPEED_4("speed_4", Category.POWERUPS,
            new String[]{"Speed boost", "Speed boost II", "Speed boost III", "Speed boost IV"},
            "Speed Boost IV");

        public final String id;
        public final Category category;
        public final String[] tierNames;
        public final String displayName;

        BuyableItem(String id, Category category, String[] tierNames, String displayName) {
            this.id = id;
            this.category = category;
            this.tierNames = tierNames;
            this.displayName = displayName;
        }

        public int getTierCount() { return tierNames.length; }
        public boolean isTiered() { return tierNames.length > 1; }

        public static BuyableItem fromId(String id) {
            for (BuyableItem item : values()) {
                if (item.id.equalsIgnoreCase(id)) return item;
            }
            return null;
        }
    }

    public enum Category {
        ITEMS("ITEMS"),
        POWERUPS("POWER-UPS");

        public final String displayName;
        Category(String displayName) { this.displayName = displayName; }
    }

    private enum State {
        IDLE,
        OPENING_MENU,
        WAIT_MENU,
        CLICK_SHOP_1,
        WAIT_SHOP_SUBMENU,
        CLICK_SHOP_2,
        WAIT_SHOP,
        NAVIGATE_CATEGORY,
        WAIT_CATEGORY,
        WAIT_ITEMS_LOAD,
        CLICKING,
        TIER_TRANSITION      // Pausing between tiers
    }

    private static final int MENU_SLOT = 8;     
    private static final int SHOP_SLOT_1 = 20;   
    private static final int SHOP_SLOT_2 = 3;    
    private static final int NAV_DELAY_BASE = 6;
    private static final int NAV_DELAY_JITTER = 4;

    private State state = State.IDLE;
    private BuyableItem currentItem;
    private int requestedQuantity;
    private int currentTierIndex;
    private int clicksRemainingInTier;
    private int totalClicksPerformed;
    private int totalClicksNeeded;
    private int ticksWaiting;
    private int clickCooldown;
    private int lastSyncId = -1;
    private int tierTransitionTarget;

    private int itemSearchRetries;
    private static final int MAX_ITEM_SEARCH_RETRIES = 30;

    private final Random random = new Random();
    private int burstRemaining;
    private int clicksSinceLastRest;
    private int nextFatigueThreshold;

    private static final int MIN_EMPTY_SLOTS = 4;
    private static final int MAX_WAIT_TICKS = 40;
    private static final int ITEMS_LOAD_TICKS = 10;
    private static final int TIER_TRANSITION_BASE = 12;
    private static final int TIER_TRANSITION_JITTER = 9;


    public AutoBuy() {
        WBUtilsClient.LOGGER.info("[AutoBuy] Initialized");
        nextFatigueThreshold = 25 + random.nextInt(11);
    }

    public void start(BuyableItem item, int quantity) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) return;

        if (state != State.IDLE) {
            player.sendMessage(Text.literal(
                "§9[WBUtils] §cAn auto-buy operation is already running. Use §e/wbutils auto buy cancel §cto stop it."), false);
            return;
        }

        if (!hasEnoughInventorySpace(player)) {
            player.sendMessage(Text.literal(
                "§9[WBUtils] §cNot enough inventory space! Need at least §e" + MIN_EMPTY_SLOTS + " §cempty slots."), false);
            return;
        }

        this.currentItem = item;
        this.requestedQuantity = quantity;
        this.currentTierIndex = 0;
        this.totalClicksPerformed = 0;
        this.totalClicksNeeded = calculateTotalClicks(item, quantity);
        this.clicksRemainingInTier = calculateClicksForTier(item, quantity, 0);
        this.clicksSinceLastRest = 0;
        this.nextFatigueThreshold = 25 + random.nextInt(11);
        this.ticksWaiting = 0;
        this.clickCooldown = 0;
        this.burstRemaining = 0;
        this.lastSyncId = -1;
        this.itemSearchRetries = 0;

        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        String tierInfo = item.isTiered()
            ? " §8(tiered: " + totalClicksNeeded + " total clicks across " + item.getTierCount() + " tiers)"
            : "";
        player.sendMessage(Text.literal(
            "§9[WBUtils] §7Auto-buy started: §b" + item.displayName + " §7x§a" + quantity + tierInfo), false);

        int previousSlot = player.getInventory().selectedSlot;
        player.getInventory().selectedSlot = MENU_SLOT;
        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        player.getInventory().selectedSlot = previousSlot;

        state = State.WAIT_MENU;
        ticksWaiting = 0;

        if (config.debugAutoBuy) {
            player.sendMessage(Text.literal(
                "§9[WBUtils] §8[DEBUG] Opening menu via hotbar slot " + MENU_SLOT + "..."), false);
        }
    }

    public void cancel(String reason) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (state == State.IDLE) return;

        State oldState = state;
        state = State.IDLE;

        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (config.debugAutoBuy) {
            WBUtilsClient.LOGGER.info("[AutoBuy] Cancelled: {} (was in state {})", reason, oldState);
        }

        if (player != null) {
            String progressStr = getProgressString();
            player.sendMessage(Text.literal(
                "§9[WBUtils] §cAuto-buy cancelled: " + reason + ". " + progressStr), false);
        }

        resetState();
    }

    public void onPlayerInterference() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.autoBuySafetyEnabled) return;
        if (state == State.IDLE) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;

        State oldState = state;
        state = State.IDLE;

        if (player != null) {
            String progressStr = getProgressString();
            player.sendMessage(Text.literal(
                "§9[WBUtils] §cOperation cancelled because you interfered. " + progressStr
                + " §7Turn off safety? §e/wbutils auto buy safety off"), false);
        }

        if (config.debugAutoBuy) {
            WBUtilsClient.LOGGER.info("[AutoBuy] Cancelled by player interference (was in state {})", oldState);
        }

        resetState();
    }

    public boolean isActive() {
        return state != State.IDLE;
    }

    public String getStatusInfo() {
        if (state == State.IDLE) return "§7Idle";
        String itemName = currentItem != null ? currentItem.displayName : "unknown";
        int tiers = currentItem != null ? currentItem.getTierCount() : 0;
        return "§7Buying §b" + itemName + " §7x§a" + requestedQuantity
            + " §7| State: §e" + state.name()
            + " §7| Clicks: §b" + totalClicksPerformed + "§7/§a" + totalClicksNeeded
            + " §7| Tier: §b" + (currentTierIndex + 1) + "§7/§a" + tiers;
    }

    public void onClientTick() {
        if (state == State.IDLE) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) { cancel("Player disconnected"); return; }

        ModConfig config = WBUtilsClient.getConfigManager().getConfig();

        switch (state) {
            case WAIT_MENU         -> handleWaitMenu(client, player, config);
            case CLICK_SHOP_1      -> handleClickShop1(client, player, config);
            case WAIT_SHOP_SUBMENU -> handleWaitShopSubmenu(client, player, config);
            case CLICK_SHOP_2      -> handleClickShop2(client, player, config);
            case WAIT_SHOP         -> handleWaitShop(client, player, config);
            case NAVIGATE_CATEGORY -> handleNavigateCategory(client, player, config);
            case WAIT_CATEGORY     -> handleWaitCategory(client, player, config);
            case WAIT_ITEMS_LOAD   -> handleWaitItemsLoad(client, player, config);
            case CLICKING          -> handleClicking(client, player, config);
            case TIER_TRANSITION   -> handleTierTransition(player, config);
            default -> {}
        }
    }

    // ─── Shop Opening Handlers ──────────────────────────────────────

    private void handleWaitMenu(MinecraftClient client, ClientPlayerEntity player, ModConfig config) {
        ticksWaiting++;

        if (client.currentScreen instanceof HandledScreen<?> screen) {
            lastSyncId = screen.getScreenHandler().syncId;
            clickCooldown = getNavDelay();
            state = State.CLICK_SHOP_1;
            if (config.debugAutoBuy) {
                player.sendMessage(Text.literal(
                    "§9[WBUtils] §8[DEBUG] Menu opened (syncId: " + lastSyncId
                    + ") — clicking shop slot " + SHOP_SLOT_1 + " in " + clickCooldown + " ticks"), false);
            }
            return;
        }

        if (ticksWaiting >= MAX_WAIT_TICKS) {
            cancel("Timeout waiting for menu to open. Make sure you're in the housing");
        }
    }

    private void handleClickShop1(MinecraftClient client, ClientPlayerEntity player, ModConfig config) {
        if (clickCooldown > 0) { clickCooldown--; return; }

        if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
            cancel("Menu closed before shop could be opened");
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        lastSyncId = handler.syncId;
        clickSlot(client, handler, SHOP_SLOT_1);
        state = State.WAIT_SHOP_SUBMENU;
        ticksWaiting = 0;

        if (config.debugAutoBuy) {
            player.sendMessage(Text.literal(
                "§9[WBUtils] §8[DEBUG] Clicked slot " + SHOP_SLOT_1 + " — waiting for submenu"), false);
        }
    }

    private void handleWaitShopSubmenu(MinecraftClient client, ClientPlayerEntity player, ModConfig config) {
        ticksWaiting++;

        if (client.currentScreen instanceof HandledScreen<?> screen) {
            int currentSyncId = screen.getScreenHandler().syncId;
            if (currentSyncId != lastSyncId) {
                lastSyncId = currentSyncId;
                clickCooldown = getNavDelay();
                state = State.CLICK_SHOP_2;
                if (config.debugAutoBuy) {
                    player.sendMessage(Text.literal(
                        "§9[WBUtils] §8[DEBUG] Submenu opened (syncId: " + currentSyncId
                        + ") — clicking shop slot " + SHOP_SLOT_2 + " in " + clickCooldown + " ticks"), false);
                }
                return;
            }
        }

        if (ticksWaiting >= MAX_WAIT_TICKS) {
            cancel("Timeout waiting for shop submenu");
        }
    }

    private void handleClickShop2(MinecraftClient client, ClientPlayerEntity player, ModConfig config) {
        if (clickCooldown > 0) { clickCooldown--; return; }

        if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
            cancel("Menu closed before shop navigation completed");
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        lastSyncId = handler.syncId;
        clickSlot(client, handler, SHOP_SLOT_2);
        state = State.WAIT_SHOP;
        ticksWaiting = 0;

        if (config.debugAutoBuy) {
            player.sendMessage(Text.literal(
                "§9[WBUtils] §8[DEBUG] Clicked slot " + SHOP_SLOT_2 + " — waiting for shop screen"), false);
        }
    }

    private void handleWaitShop(MinecraftClient client, ClientPlayerEntity player, ModConfig config) {
        ticksWaiting++;

        if (client.currentScreen instanceof HandledScreen<?> screen) {
            int currentSyncId = screen.getScreenHandler().syncId;
            if (currentSyncId != lastSyncId) {
                lastSyncId = currentSyncId;
                clickCooldown = getNavDelay();
                state = State.NAVIGATE_CATEGORY;
                if (config.debugAutoBuy) {
                    player.sendMessage(Text.literal(
                        "§9[WBUtils] §8[DEBUG] Shop screen loaded (syncId: " + currentSyncId
                        + ") — navigating to category: " + currentItem.category.displayName), false);
                }
                return;
            }
        }

        if (ticksWaiting >= MAX_WAIT_TICKS) {
            cancel("Timeout waiting for shop screen to load");
        }
    }


    private void handleNavigateCategory(MinecraftClient client, ClientPlayerEntity player, ModConfig config) {
        if (clickCooldown > 0) { clickCooldown--; return; }

        if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
            cancel("Shop GUI was closed");
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        int categorySlot = findCategorySlot(handler, currentItem.category);

        if (categorySlot == -1) {
            String targetName = currentItem.tierNames[0];
            int targetSlot = findSlotByName(handler, targetName);
            if (targetSlot != -1) {
                if (config.debugAutoBuy) {
                    player.sendMessage(Text.literal(
                        "§9[WBUtils] §8[DEBUG] Already in submenu, target '" + targetName
                        + "' found at slot " + targetSlot), false);
                }
                lastSyncId = handler.syncId;
                itemSearchRetries = 0;
                state = State.CLICKING;
                clickCooldown = getInitialDelay();
                return;
            }

            cancel("Could not find category '" + currentItem.category.displayName
                + "' in the shop. Is the shop layout correct?");
            return;
        }

        lastSyncId = handler.syncId;
        clickSlot(client, handler, categorySlot);
        state = State.WAIT_CATEGORY;
        ticksWaiting = 0;

        if (config.debugAutoBuy) {
            player.sendMessage(Text.literal(
                "§9[WBUtils] §8[DEBUG] Clicked category slot " + categorySlot
                + " (syncId: " + lastSyncId + ")"), false);
        }
    }

    private void handleWaitCategory(MinecraftClient client, ClientPlayerEntity player, ModConfig config) {
        ticksWaiting++;

        if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
            cancel("Shop GUI was closed");
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        int currentSyncId = handler.syncId;
        if (currentSyncId != lastSyncId) {
            lastSyncId = currentSyncId;
            state = State.WAIT_ITEMS_LOAD;
            ticksWaiting = 0;

            if (config.debugAutoBuy) {
                player.sendMessage(Text.literal(
                    "§9[WBUtils] §8[DEBUG] Category screen changed (syncId: " + currentSyncId
                    + ") — waiting " + ITEMS_LOAD_TICKS + " ticks for items to load..."), false);
            }
            return;
        }

        if (ticksWaiting >= MAX_WAIT_TICKS) {
            cancel("Timeout waiting for category submenu to load");
        }
    }

    private void handleWaitItemsLoad(MinecraftClient client, ClientPlayerEntity player, ModConfig config) {
        ticksWaiting++;

        if (!(client.currentScreen instanceof HandledScreen<?>)) {
            cancel("Shop GUI was closed while waiting for items to load");
            return;
        }

        if (ticksWaiting >= ITEMS_LOAD_TICKS) {
            itemSearchRetries = 0;
            state = State.CLICKING;
            clickCooldown = getInitialDelay();

            if (config.debugAutoBuy) {
                player.sendMessage(Text.literal(
                    "§9[WBUtils] §8[DEBUG] Items load wait complete — starting clicks. Tier 1: '"
                    + currentItem.tierNames[0] + "' x" + clicksRemainingInTier), false);

                ScreenHandler handler = ((HandledScreen<?>) client.currentScreen).getScreenHandler();
                dumpSlotNames(handler, player);
            }
        }
    }

    private void handleClicking(MinecraftClient client, ClientPlayerEntity player, ModConfig config) {
        if (clickCooldown > 0) { clickCooldown--; return; }

        if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
            cancel("Shop GUI was closed");
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();

        String targetName = currentItem.tierNames[currentTierIndex];
        int targetSlot = findSlotByName(handler, targetName);

        if (targetSlot == -1) {
            itemSearchRetries++;
            if (itemSearchRetries >= MAX_ITEM_SEARCH_RETRIES) {
                if (config.debugAutoBuy) {
                    dumpSlotNames(handler, player);
                }
                cancel("Could not find item '" + targetName + "' after "
                    + MAX_ITEM_SEARCH_RETRIES + " ticks of retrying");
            } else if (config.debugAutoBuy && itemSearchRetries == 1) {
                player.sendMessage(Text.literal(
                    "§9[WBUtils] §8[DEBUG] Item '" + targetName + "' not found yet, retrying..."), false);
            }
            return; 
        }

        itemSearchRetries = 0;

        clickSlot(client, handler, targetSlot);
        totalClicksPerformed++;
        clicksRemainingInTier--;

        if (config.debugAutoBuy) {
            if (totalClicksPerformed % 10 == 0 || clicksRemainingInTier == 0) {
                player.sendMessage(Text.literal(
                    "§9[WBUtils] §8[DEBUG] Click " + totalClicksPerformed + "/" + totalClicksNeeded
                    + " | Tier " + (currentTierIndex + 1) + " remaining: "
                    + clicksRemainingInTier), false);
            }
        }

        if (!config.debugAutoBuy && totalClicksPerformed > 0 && totalClicksPerformed % 50 == 0) {
            int percent = (int) ((double) totalClicksPerformed / totalClicksNeeded * 100);
            player.sendMessage(Text.literal(
                "§9[WBUtils] §7Auto-buy progress: §b" + percent + "% §8("
                + totalClicksPerformed + "/" + totalClicksNeeded + " clicks)"), false);
        }

        if (clicksRemainingInTier <= 0) {
            if (currentTierIndex + 1 < currentItem.getTierCount()) {
                state = State.TIER_TRANSITION;
                ticksWaiting = 0;
                tierTransitionTarget = TIER_TRANSITION_BASE + random.nextInt(TIER_TRANSITION_JITTER + 1);

                player.sendMessage(Text.literal(
                    "§9[WBUtils] §7Tier §b" + (currentTierIndex + 1) + "§7/§a"
                    + currentItem.getTierCount() + " §7complete. Progressing..."), false);

                if (config.debugAutoBuy) {
                    player.sendMessage(Text.literal(
                        "§9[WBUtils] §8[DEBUG] Tier transition pause: " + tierTransitionTarget + " ticks"), false);
                }
            } else {
                state = State.IDLE;
                String msg = currentItem.isTiered()
                    ? "§9[WBUtils] §aAuto-buy complete! §7Bought §b" + requestedQuantity + "x "
                        + currentItem.displayName + " §8(" + totalClicksPerformed + " total clicks)"
                    : "§9[WBUtils] §aAuto-buy complete! §7Bought §b" + requestedQuantity + "x "
                        + currentItem.displayName;
                player.sendMessage(Text.literal(msg), false);

                if (config.debugAutoBuy) {
                    WBUtilsClient.LOGGER.info("[AutoBuy] Operation complete: {} x{} ({} clicks)",
                        currentItem.displayName, requestedQuantity, totalClicksPerformed);
                }
                resetState();
            }
            return;
        }

        clickCooldown = getNextClickDelay();
    }

    private void handleTierTransition(ClientPlayerEntity player, ModConfig config) {
        ticksWaiting++;

        if (ticksWaiting >= tierTransitionTarget) {
            currentTierIndex++;
            clicksRemainingInTier = calculateClicksForTier(currentItem, requestedQuantity, currentTierIndex);
            clickCooldown = getInitialDelay();
            burstRemaining = 0;
            clicksSinceLastRest = 0;
            itemSearchRetries = 0;
            nextFatigueThreshold = 25 + random.nextInt(11);
            state = State.CLICKING;

            if (config.debugAutoBuy) {
                player.sendMessage(Text.literal(
                    "§9[WBUtils] §8[DEBUG] Starting tier " + (currentTierIndex + 1) + ": '"
                    + currentItem.tierNames[currentTierIndex] + "' x"
                    + clicksRemainingInTier), false);
            }
        }
    }

    // ─── Click Timing ───────────────────────────────────────────────

    private int getNavDelay() {
        return NAV_DELAY_BASE + random.nextInt(NAV_DELAY_JITTER + 1);
    }

    private int getInitialDelay() {
        return 3 + random.nextInt(4);
    }

    private int getNextClickDelay() {
        if (clicksSinceLastRest < 3) {
            clicksSinceLastRest++;
            return 5 + random.nextInt(4);
        }

        clicksSinceLastRest++;

        if (clicksSinceLastRest >= nextFatigueThreshold) {
            clicksSinceLastRest = 0;
            burstRemaining = 0;
            nextFatigueThreshold = 25 + random.nextInt(11);
            return 20 + random.nextInt(21);
        }

        if (burstRemaining <= 0) {
            burstRemaining = 4 + random.nextInt(4);
            return 7 + random.nextInt(9);
        }
        burstRemaining--;

        if (random.nextInt(100) < 8) {
            return 6 + random.nextInt(6);
        }

        int base = 2 + random.nextInt(4);
        int variation = (int) Math.round(random.nextGaussian() * 0.7);
        return Math.max(2, base + variation);
    }

    // ─── Slot Finding ───────────────────────────────────────────────

    private int findCategorySlot(ScreenHandler handler, Category category) {
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.slots.get(i);
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;
            if (slot.inventory instanceof PlayerInventory) continue;

            String name = stripFormatting(stack.getName().getString());

            boolean typeMatch = switch (category) {
                case ITEMS -> stack.getItem() == Items.CRAFTING_TABLE;
                case POWERUPS -> stack.getItem() == Items.ITEM_FRAME;
            };

            if (typeMatch && name.equalsIgnoreCase(category.displayName)) {
                return i;
            }
        }
        return -1;
    }

    private int findSlotByName(ScreenHandler handler, String targetName) {
        int caseInsensitiveMatch = -1;

        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.slots.get(i);
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;
            if (slot.inventory instanceof PlayerInventory) continue;

            String name = stripFormatting(stack.getName().getString());

            if (name.equals(targetName)) {
                return i;
            }

            if (caseInsensitiveMatch == -1 && name.equalsIgnoreCase(targetName)) {
                caseInsensitiveMatch = i;
            }
        }

        return caseInsensitiveMatch;
    }

    private void dumpSlotNames(ScreenHandler handler, ClientPlayerEntity player) {
        player.sendMessage(Text.literal("§9[WBUtils] §8[DEBUG] ═══ Slot dump ═══"), false);
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.slots.get(i);
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;
            if (slot.inventory instanceof PlayerInventory) continue;

            String itemType = Registries.ITEM.getId(stack.getItem()).toString();
            String rawName = stack.getName().getString();
            String stripped = stripFormatting(rawName);
            String textRepr = stack.getName().toString().replace('§', '&');

            player.sendMessage(Text.literal(
                "§9[WBUtils] §8[DEBUG]   Slot " + i + ": §e" + itemType
                + " §7| stripped=§f'" + stripped + "' §7| raw=§f'" + rawName.replace('§', '&')
                + "' §7| repr=§f'" + textRepr + "'"), false);
        }
        player.sendMessage(Text.literal("§9[WBUtils] §8[DEBUG] ═══════════════"), false);
    }


    private void clickSlot(MinecraftClient client, ScreenHandler handler, int slotId) {
        ClientPlayerInteractionManager im = client.interactionManager;
        ClientPlayerEntity player = client.player;
        if (im == null || player == null) return;

        im.clickSlot(handler.syncId, slotId, 0, SlotActionType.PICKUP, player);

        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (config.debugAutoBuy) {
            WBUtilsClient.LOGGER.debug("[AutoBuy] Clicked slot {} (syncId: {})", slotId, handler.syncId);
        }
    }

    private boolean hasEnoughInventorySpace(ClientPlayerEntity player) {
        int emptySlots = 0;
        for (int i = 0; i < player.getInventory().main.size(); i++) {
            if (player.getInventory().main.get(i).isEmpty()) {
                emptySlots++;
            }
        }
        return emptySlots >= MIN_EMPTY_SLOTS;
    }

    private int calculateClicksForTier(BuyableItem item, int quantity, int tierIndex) {
        int totalTiers = item.getTierCount();
        return quantity * (int) Math.pow(2, totalTiers - 1 - tierIndex);
    }

    private int calculateTotalClicks(BuyableItem item, int quantity) {
        int total = 0;
        for (int t = 0; t < item.getTierCount(); t++) {
            total += calculateClicksForTier(item, quantity, t);
        }
        return total;
    }

    private String getProgressString() {
        if (currentItem == null) return "§7No progress";
        if (currentItem.isTiered()) {
            return "§7Progress: §b" + totalClicksPerformed + "§7/§a" + totalClicksNeeded
                + " §7clicks (tier §b" + (currentTierIndex + 1) + "§7/§a" + currentItem.getTierCount() + "§7).";
        } else {
            return "§7Bought: §b" + totalClicksPerformed + "§7/§a" + requestedQuantity + "§7.";
        }
    }

    private String stripFormatting(String text) {
        if (text == null) return "";
        return text.replaceAll("§.", "").trim();
    }

    private void resetState() {
        currentItem = null;
        requestedQuantity = 0;
        currentTierIndex = 0;
        clicksRemainingInTier = 0;
        totalClicksPerformed = 0;
        totalClicksNeeded = 0;
        ticksWaiting = 0;
        clickCooldown = 0;
        lastSyncId = -1;
        tierTransitionTarget = 0;
        burstRemaining = 0;
        clicksSinceLastRest = 0;
        itemSearchRetries = 0;
    }
}
