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
import java.util.List;
import java.util.Map;
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
    
    private static final long SYNC_COOLDOWN_MS = 30 * 60 * 1000L;
    private static final int MAX_WAIT_TICKS = 60;
    private static final int PARSE_DELAY_TICKS = 10;
    
    private static final Pattern STORED_PATTERN = Pattern.compile("Stored:\\s*(\\d+)");
    
    private boolean waitingForGui = false;
    private boolean waitingToParse = false;
    private int waitTicks = 0;
    private int parseDelayTicks = 0;
    
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
            long remainingMin = remainingMs / 60000;
            if (config.debugBootlist) {
                WBUtilsClient.LOGGER.info("[BootlistTracker] Sync on cooldown, {} minutes remaining", remainingMin);
            }
            return;
        }
        
        waitingForGui = true;
        waitingToParse = false;
        waitTicks = 0;
        parseDelayTicks = 0;
        
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
            
            if (client.currentScreen instanceof HandledScreen<?>) {
                waitingForGui = false;
                waitingToParse = true;
                parseDelayTicks = PARSE_DELAY_TICKS;
                
                if (config.debugBootlist) {
                    WBUtilsClient.LOGGER.info("[BootlistTracker] GUI opened, waiting {} ticks before parsing", PARSE_DELAY_TICKS);
                }
                return;
            }
            
            if (waitTicks >= MAX_WAIT_TICKS) {
                if (config.debugBootlist) {
                    WBUtilsClient.LOGGER.warn("[BootlistTracker] Timeout waiting for GUI");
                }
                reset();
            }
            return;
        }
        
        if (waitingToParse) {
            if (parseDelayTicks > 0) {
                parseDelayTicks--;
                return;
            }
            
            if (client.currentScreen instanceof HandledScreen<?> handledScreen) {
                Map<String, Integer> boots = parseBootsGui(handledScreen);
                
                if (!boots.isEmpty()) {
                    syncToServer(boots);
                    lastBootsData.clear();
                    lastBootsData.putAll(boots);
                }
                
                if (config.debugBootlist) {
                    WBUtilsClient.LOGGER.info("[BootlistTracker] Parsed {} boot types from GUI", boots.size());
                }
            }
            
            reset();
        }
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
        waitingToParse = false;
        waitTicks = 0;
        parseDelayTicks = 0;
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
