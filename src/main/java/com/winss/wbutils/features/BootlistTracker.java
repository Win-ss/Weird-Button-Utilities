package com.winss.wbutils.features;

import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.Messages;
import com.winss.wbutils.config.ModConfig;
import com.winss.wbutils.network.NetworkManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * Boot List Tracker - Tracks player boots storage from /boots GUI.
 * 
 * When enabled, monitors when the player opens the /boots menu,
 * parses the boot items for "Stored: X" lore, and syncs the data
 * to the server for Discord access via /boots [playername].
 */
public class BootlistTracker {
    
    private static final long SYNC_COOLDOWN_MS = 1 * 60 * 1000L;
    private static final int MAX_WAIT_TICKS = 60;
    private static final int MAX_TRACK_TICKS = 20 * 60;
    private static final int PARSE_DELAY_TICKS = 4;
    
    private static final Pattern STORED_PATTERN = Pattern.compile("Stored:\\s*(\\d+)");
    private static final Pattern BOOTS_TITLE_PATTERN = Pattern.compile("^Boots\\s+Catalog(?:\\s*\\[(\\d+)\\])?$", Pattern.CASE_INSENSITIVE);
    
    private boolean waitingForGui = false;
    private boolean trackingGui = false;
    private boolean waitingToParse = false;
    private int waitTicks = 0;
    private int trackTicks = 0;
    private int parseDelayTicks = 0;

    private String currentPageKey = null;
    private String pendingPageKey = null;
    private final Set<String> parsedPages = new HashSet<>();
    private final Map<String, Integer> pendingBootsData = new HashMap<>();
    
    private long lastSyncTime = 0;
    
    private final Map<String, Integer> lastBootsData = new ConcurrentHashMap<>();
    
    public BootlistTracker() {
        WBUtilsClient.LOGGER.info("[BootlistTracker] Initialized");
    }
    
    public void onBootsCommand() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.bootlistEnabled) {
            return;
        }
        
        long now = System.currentTimeMillis();
        if (now - lastSyncTime < SYNC_COOLDOWN_MS) {
            long remainingMs = SYNC_COOLDOWN_MS - (now - lastSyncTime);
            long remainingMin = Math.max(1, (remainingMs + 59999) / 60000);
            if (config.debugBootlist) {
                WBUtilsClient.LOGGER.info("[BootlistTracker] Sync on cooldown, {} minutes remaining", remainingMin);
            }
            return;
        }
        
        waitingForGui = true;
        trackingGui = false;
        waitingToParse = false;
        waitTicks = 0;
        trackTicks = 0;
        parseDelayTicks = 0;
        currentPageKey = null;
        pendingPageKey = null;
        parsedPages.clear();
        pendingBootsData.clear();
        
        if (config.debugBootlist) {
            WBUtilsClient.LOGGER.info("[BootlistTracker] Started monitoring for /boots GUI");
        }
    }

    public void onClientTick() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.bootlistEnabled) {
            reset();
            return;
        }
        
        MinecraftClient client = MinecraftClient.getInstance();
        
        if (waitingForGui && !waitingToParse) {
            waitTicks++;
            
            if (client.currentScreen instanceof HandledScreen<?> handledScreen) {
                String pageKey = getBootsPageKey(handledScreen);
                if (pageKey != null) {
                    waitingForGui = false;
                    trackingGui = true;
                    trackTicks = 0;
                    currentPageKey = pageKey;
                    queuePageParse(pageKey);

                    if (config.debugBootlist) {
                        WBUtilsClient.LOGGER.info("[BootlistTracker] Boots GUI opened on page {}, waiting {} ticks before parsing", pageKey, PARSE_DELAY_TICKS);
                    }
                    return;
                }
            }
            
            if (waitTicks >= MAX_WAIT_TICKS) {
                if (config.debugBootlist) {
                    WBUtilsClient.LOGGER.warn("[BootlistTracker] Timeout waiting for GUI");
                }
                reset();
            }
            return;
        }

        if (trackingGui) {
            trackTicks++;

            if (trackTicks >= MAX_TRACK_TICKS) {
                if (config.debugBootlist) {
                    WBUtilsClient.LOGGER.warn("[BootlistTracker] Tracking timeout reached, finalizing current data");
                }
                finalizeTracking(config);
                return;
            }

            if (!(client.currentScreen instanceof HandledScreen<?> handledScreen)) {
                finalizeTracking(config);
                return;
            }

            String pageKey = getBootsPageKey(handledScreen);
            if (pageKey == null) {
                finalizeTracking(config);
                return;
            }

            if (!pageKey.equals(currentPageKey)) {
                currentPageKey = pageKey;
                if (!parsedPages.contains(pageKey)) {
                    queuePageParse(pageKey);
                    if (config.debugBootlist) {
                        WBUtilsClient.LOGGER.info("[BootlistTracker] Detected Boots Catalog page change to {}", pageKey);
                    }
                }
            }

            if (!waitingToParse) {
                return;
            }

            if (parseDelayTicks > 0) {
                parseDelayTicks--;
                return;
            }

            Map<String, Integer> boots = parseBootsGui(handledScreen);
            mergePendingBoots(boots);

            String parsedPage = pendingPageKey != null ? pendingPageKey : pageKey;
            parsedPages.add(parsedPage);
            waitingToParse = false;
            pendingPageKey = null;

            if (config.debugBootlist) {
                WBUtilsClient.LOGGER.info("[BootlistTracker] Parsed {} boot types from page {}", boots.size(), parsedPage);
            }
            return;
        }
        
    }

    private void queuePageParse(String pageKey) {
        waitingToParse = true;
        pendingPageKey = pageKey;
        parseDelayTicks = PARSE_DELAY_TICKS;
    }

    private void mergePendingBoots(Map<String, Integer> pageBoots) {
        for (Map.Entry<String, Integer> entry : pageBoots.entrySet()) {
            pendingBootsData.merge(entry.getKey(), entry.getValue(), Math::max);
        }
    }

    private void finalizeTracking(ModConfig config) {
        if (!pendingBootsData.isEmpty()) {
            Map<String, Integer> payload = new HashMap<>(pendingBootsData);
            syncToServer(payload);
            lastBootsData.clear();
            lastBootsData.putAll(payload);

            if (config.debugBootlist) {
                WBUtilsClient.LOGGER.info("[BootlistTracker] Finalized sync with {} boot types across {} page(s)", payload.size(), parsedPages.size());
            }
        } else if (config.debugBootlist) {
            WBUtilsClient.LOGGER.info("[BootlistTracker] No boot data collected before GUI closed");
        }

        reset();
    }

    private String getBootsPageKey(HandledScreen<?> screen) {
        String title = screen.getTitle().getString();
        if (title == null) {
            return null;
        }

        String cleanTitle = title.replaceAll("§[0-9a-fk-or]", "").trim();
        Matcher matcher = BOOTS_TITLE_PATTERN.matcher(cleanTitle);
        if (!matcher.matches()) {
            return null;
        }

        String page = matcher.group(1);
        return (page == null || page.isBlank()) ? "1" : page;
    }
    

    private Map<String, Integer> parseBootsGui(HandledScreen<?> screen) {
        Map<String, Integer> boots = new HashMap<>();
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        try {
            var handler = screen.getScreenHandler();
            List<Slot> slots = handler.slots;
            
            for (Slot slot : slots) {
                ItemStack stack = slot.getStack();
                if (stack.isEmpty()) continue;
                
                String itemName = stack.getName().getString();
                if (itemName == null || itemName.isEmpty()) continue;
                
                int storedCount = getStoredCount(stack);
                
                if (storedCount > 0) {
                    String cleanName = itemName.replaceAll("§[0-9a-fk-or]", "").trim();
                    boots.put(cleanName, storedCount);
                    
                    if (config.debugBootlist) {
                        WBUtilsClient.LOGGER.debug("[BootlistTracker] Found boot: {} x{}", cleanName, storedCount);
                    }
                }
            }
        } catch (Exception e) {
            WBUtilsClient.LOGGER.error("[BootlistTracker] Error parsing boots GUI", e);
        }
        
        return boots;
    }

    private int getStoredCount(ItemStack stack) {
        try {
            var loreComponent = stack.get(net.minecraft.component.DataComponentTypes.LORE);
            
            if (loreComponent != null) {
                for (Text line : loreComponent.lines()) {
                    String lineStr = line.getString();
                    if (lineStr == null) continue;
                    
                    String cleanLine = lineStr.replaceAll("§[0-9a-fk-or]", "");
                    
                    Matcher matcher = STORED_PATTERN.matcher(cleanLine);
                    if (matcher.find()) {
                        return Integer.parseInt(matcher.group(1));
                    }
                }
            }
        } catch (Exception e) {
            WBUtilsClient.LOGGER.debug("[BootlistTracker] Error extracting stored count", e);
        }
        
        return 0;
    }

    private void syncToServer(Map<String, Integer> boots) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        MinecraftClient client = MinecraftClient.getInstance();
        
        if (client.player == null) return;
        
        String authToken = config.authToken;
        if (authToken == null || authToken.isBlank()) {
            if (config.debugBootlist) {
                WBUtilsClient.LOGGER.warn("[BootlistTracker] No auth token, cannot sync");
            }
            return;
        }
        
        String uuid = config.minecraftUuid;
        String name = config.minecraftName;
        
        if (uuid == null || uuid.isBlank()) {
            uuid = client.player.getUuidAsString();
            name = client.player.getName().getString();
        }
        
        StringBuilder bootsJson = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : boots.entrySet()) {
            if (!first) bootsJson.append(",");
            bootsJson.append("\"").append(escapeJson(entry.getKey())).append("\":").append(entry.getValue());
            first = false;
        }
        bootsJson.append("}");
        
        String json = String.format(
            "{\"minecraft_uuid\":\"%s\",\"minecraft_name\":\"%s\",\"boots\":%s}",
            escapeJson(uuid),
            escapeJson(name),
            bootsJson
        );
        
        String url = config.authServerUrl + "/boots/update";
        
        NetworkManager.post(url, json, authToken)
            .thenAccept(response -> {
                if (response.isSuccess()) {
                    lastSyncTime = System.currentTimeMillis();
                    WBUtilsClient.LOGGER.info("[BootlistTracker] Synced {} boot types to server", boots.size());
                    
                    MinecraftClient.getInstance().execute(() -> {
                        if (client.player != null) {
                            client.player.sendMessage(Text.literal(
                                Messages.format("command.bootlist.sync.success", "count", String.valueOf(boots.size()))
                            ), false);
                        }
                    });
                } else {
                    WBUtilsClient.LOGGER.warn("[BootlistTracker] Failed to sync: {}", response.body());
                }
            })
            .exceptionally(e -> {
                WBUtilsClient.LOGGER.error("[BootlistTracker] Sync error", e);
                return null;
            });
    }
    
    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }
    
    private void reset() {
        waitingForGui = false;
        trackingGui = false;
        waitingToParse = false;
        waitTicks = 0;
        trackTicks = 0;
        parseDelayTicks = 0;
        currentPageKey = null;
        pendingPageKey = null;
        parsedPages.clear();
        pendingBootsData.clear();
    }

    public boolean isWaiting() {
        return waitingForGui || waitingToParse;
    }
    
    public long getLastSyncTime() {
        return lastSyncTime;
    }

    public long getRemainingCooldownMs() {
        long elapsed = System.currentTimeMillis() - lastSyncTime;
        return Math.max(0, SYNC_COOLDOWN_MS - elapsed);
    }

    public Map<String, Integer> getLastBootsData() {
        return new HashMap<>(lastBootsData);
    }

    public void resetCooldown() {
        lastSyncTime = 0;
    }
}
