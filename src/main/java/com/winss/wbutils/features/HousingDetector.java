package com.winss.wbutils.features;

import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.Messages;
import com.winss.wbutils.mixin.PlayerListHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Step 1: Server Verification - Check if connected to Hypixel
 * Step 2: Chat Detection - Listen for "Sending to DON'T PRESS THE BUTTON" message
 * Step 3: Tab List Fallback - Read tab list header/footer for housing info
 * Step 4: Trigger Actions - Confirm detection and activate features
 */
public class HousingDetector {

    

    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("§[0-9a-fk-or]");
    

    private static final Pattern SENDING_TO_PATTERN = Pattern.compile(
        "Sending to DON'?T PRESS THE BUTTON", Pattern.CASE_INSENSITIVE
    );
    

    private static final Pattern TAB_HOUSING_PATTERN = Pattern.compile(
        "You\\s+are\\s+in.*?DON'?T\\s*PRESS\\s*THE\\s*BUTTON", Pattern.CASE_INSENSITIVE
    );
    


    private static final Pattern TAB_OWNER_PATTERN = Pattern.compile(
        "by\\s*(?:\\[[^\\]]*\\]\\s*)*Cyborg023", Pattern.CASE_INSENSITIVE
    );
    

    private volatile boolean inDptb2Housing = false;
    private volatile boolean announcedEntry = false;
    private volatile boolean detectedViaChat = false;
    private final AtomicBoolean pendingDetection = new AtomicBoolean(false);
    

    private long worldJoinTime = 0L;
    private long lastCheckTime = 0L;
    private String lastServerAddress = "";
    private String cachedTabContent = "";
    private RegistryKey<World> lastDimension = null;
    private int tabCheckRetryCount = 0;
    
    private String lastHypixelCheckAddress = "";
    private boolean cachedIsHypixel = false;
    

    private static final long WORLD_LOAD_DELAY_MS = 3000L;
    private static final long CHECK_INTERVAL_MS = 10000L;
    private static final int MAX_TAB_RETRIES = 3;
    private static final long TAB_RETRY_DELAY_MS = 1500L;
    

    private static String stripColorCodes(String text) {
        if (text == null || text.isEmpty()) return "";
        return COLOR_CODE_PATTERN.matcher(text).replaceAll("");
    }
    
    public boolean handleChatMessage(Text message) {
        if (message == null) return false;
        
        String plain = message.getString();
        if (plain == null || plain.isEmpty()) return false;
        

        String stripped = stripColorCodes(plain).trim();
        

        if (SENDING_TO_PATTERN.matcher(stripped).find()) {
            WBUtilsClient.LOGGER.info("[HousingDetector] Detected server transfer to DPTB housing via chat");
            detectedViaChat = true;
            inDptb2Housing = true;
            announceHousingEntry("chat");
            return true;
        }
        
        return false;
    }
    
    private void triggerPendingDetection() {
        pendingDetection.set(true);
        worldJoinTime = System.currentTimeMillis();
        tabCheckRetryCount = 0;
        WBUtilsClient.LOGGER.info("[HousingDetector] Pending detection triggered, waiting for world to load...");
    }
    
    public void onClientTick() {
        MinecraftClient client = MinecraftClient.getInstance();
        

        if (client.player == null || client.world == null) {
            return;
        }
        

        if (!isOnHypixel()) {
            if (inDptb2Housing) {
                resetHousingStatus("Not on Hypixel");
            }
            return;
        }
        

        RegistryKey<World> currentDimension = client.world.getRegistryKey();
        if (lastDimension != null && !currentDimension.equals(lastDimension)) {
            WBUtilsClient.LOGGER.debug("[HousingDetector] Dimension changed from {} to {}", 
                lastDimension.getValue(), currentDimension.getValue());
            if (inDptb2Housing && !detectedViaChat) {

                resetHousingStatus("Dimension changed");
                triggerPendingDetection();
            }
        }
        lastDimension = currentDimension;
        
        long now = System.currentTimeMillis();
        

        if (pendingDetection.get()) {
            long elapsed = now - worldJoinTime;
            long requiredDelay = WORLD_LOAD_DELAY_MS + (tabCheckRetryCount * TAB_RETRY_DELAY_MS);
            
            if (elapsed >= requiredDelay) {
                WBUtilsClient.LOGGER.debug("[HousingDetector] Processing world load detection (attempt {})", 
                    tabCheckRetryCount + 1);
                
                boolean detected = handlePeriodicCheck();
                
                if (!detected && tabCheckRetryCount < MAX_TAB_RETRIES) {

                    tabCheckRetryCount++;
                    WBUtilsClient.LOGGER.debug("[HousingDetector] Tab detection failed, scheduling retry {}/{}", 
                        tabCheckRetryCount, MAX_TAB_RETRIES);
                } else {
                    pendingDetection.set(false);
                    tabCheckRetryCount = 0;
                }
            }
            return;
        }


        if (detectedViaChat && inDptb2Housing) {
            return;
        }


        if ((now - lastCheckTime) < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheckTime = now;
        
        handlePeriodicCheck();
    }
    

    private boolean handlePeriodicCheck() {
        MinecraftClient client = MinecraftClient.getInstance();
        
        if (client.player == null || client.world == null) {
            if (inDptb2Housing) resetHousingStatus("Not connected to server");
            return false;
        }
        

        if (!isOnHypixel()) {
            if (inDptb2Housing) resetHousingStatus("Not on Hypixel");
            pendingDetection.set(false);
            detectedViaChat = false;
            return false;
        }
        

        String currentServer = getCurrentServerAddress();
        if (!currentServer.equals(lastServerAddress)) {
            lastServerAddress = currentServer;
            cachedTabContent = "";
            if (inDptb2Housing && !detectedViaChat) {
                resetHousingStatus("Server changed");
                triggerPendingDetection();
                return false;
            }
        }
        

        if (detectedViaChat && inDptb2Housing) {
            return true;
        }
        

        boolean tabDetected = checkTabListForHousing();
        if (tabDetected) {
            if (!inDptb2Housing) {
                inDptb2Housing = true;
                announceHousingEntry("tab list");
            }
            return true;
        } else if (inDptb2Housing && !detectedViaChat) {

            resetHousingStatus("Housing info no longer in tab list");
        }
        
        return false;
    }
    
    /**
     * Check the tab list header/footer for housing identifiers
     * Looks for: "You are in DON'T PRESS THE BUTTON, by [MVP+] Cyborg023"
     * @return true if housing identifiers are found
     */
    private boolean checkTabListForHousing() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return false;
        if (client.inGameHud == null) return false;
        
        PlayerListHud playerListHud = client.inGameHud.getPlayerListHud();
        if (playerListHud == null) return false;
        

        PlayerListHudAccessor accessor = (PlayerListHudAccessor) playerListHud;
        Text header = accessor.getHeader();
        Text footer = accessor.getFooter();
        

        StringBuilder tabContentBuilder = new StringBuilder();
        
        if (header != null) {
            tabContentBuilder.append(stripColorCodes(header.getString())).append(" ");
        }
        
        if (footer != null) {
            tabContentBuilder.append(stripColorCodes(footer.getString()));
        }
        
        String content = tabContentBuilder.toString();
        

        cachedTabContent = content;
        
        if (content.isEmpty()) {
            WBUtilsClient.LOGGER.debug("[HousingDetector] Tab list content is empty");
            return false;
        }
        

        boolean hasHousingName = TAB_HOUSING_PATTERN.matcher(content).find();
        

        boolean hasOwner = TAB_OWNER_PATTERN.matcher(content).find();
        
        return hasHousingName && hasOwner;
    }
    
    private void announceHousingEntry(String method) {
        if (announcedEntry) return;
        announcedEntry = true;
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        
        client.player.sendMessage(Text.literal(Messages.get("housing.detected.success")), false);
        

        ModUserManager modUserManager = WBUtilsClient.getModUserManager();
        if (modUserManager != null) {
            modUserManager.notifyOnline();
        }
        
        WBUtilsClient.LOGGER.info("[HousingDetector] Entered DPTB2 housing via {} - features activated", method);
        

        AuthService.checkLinkStatusSafe(result -> {
            if (result == AuthService.LinkCheckResult.NOT_LINKED) {

                if (!inDptb2Housing) return;
                
                client.execute(() -> {
                    if (client.player != null) {
                        client.player.sendMessage(Text.literal(Messages.get("auth.not_authenticated")), false);
                        

                        for (int i = 0; i < 5; i++) {
                            client.player.playSound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.0f, 1.0f);
                        }
                    }
                });
            }
        });
        

        AuthService.checkBlacklistStatus(isBlacklisted -> {
            if (isBlacklisted) {

                if (!inDptb2Housing) return;
                
                client.execute(() -> {
                    if (client.player != null) {
                        client.player.sendMessage(Text.literal(Messages.get("auth.blacklisted")), false);
                        

                        for (int i = 0; i < 3; i++) {
                            client.player.playSound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 1.0f, 0.5f);
                        }
                    }
                });
            }
        });
    }

    private void resetHousingStatus(String reason) {
        if (!inDptb2Housing) return;
        
        inDptb2Housing = false;
        announcedEntry = false;
        detectedViaChat = false;
        cachedTabContent = "";
        

        ModUserManager modUserManager = WBUtilsClient.getModUserManager();
        if (modUserManager != null) {
            modUserManager.reset();
        }
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(Messages.format("housing.left", "reason", reason)), false);
        }
        
        WBUtilsClient.LOGGER.info("[HousingDetector] Left housing: {}", reason);
    }

    private String getCurrentServerAddress() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
        if (networkHandler == null) return "";
        
        ServerInfo serverInfo = networkHandler.getServerInfo();
        if (serverInfo == null) return "";
        
        return serverInfo.address;
    }

    private boolean isOnHypixel() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
        if (networkHandler == null) return false;
        
        ServerInfo serverInfo = networkHandler.getServerInfo();
        if (serverInfo == null) return false;
        
        // Optim: Cache result to avoid toLowerCase() every tick
        if (!serverInfo.address.equals(lastHypixelCheckAddress)) {
            lastHypixelCheckAddress = serverInfo.address;
            String lower = lastHypixelCheckAddress.toLowerCase();
            cachedIsHypixel = lower.contains("hypixel.net") || lower.contains("hypixel.io");
        }
        
        return cachedIsHypixel;
    }

    public boolean isConnectedToHypixel() {
        return isOnHypixel();
    }

    public void onDisconnect() {
        inDptb2Housing = false;
        announcedEntry = false;
        detectedViaChat = false;
        pendingDetection.set(false);
        worldJoinTime = 0L;
        lastCheckTime = 0L;
        lastServerAddress = "";
        cachedTabContent = "";
        lastDimension = null;
        tabCheckRetryCount = 0;
        lastHypixelCheckAddress = "";
        cachedIsHypixel = false;
        WBUtilsClient.LOGGER.debug("[HousingDetector] Disconnected - all state reset");
    }

    public void reset() {
        onDisconnect();
    }
 
    public void onWorldJoin() {
        if (isOnHypixel()) {
            triggerPendingDetection();
        }
    }

    public boolean isInDptb2Housing() {
        return inDptb2Housing;
    }

    public void showScoreboardDebug() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }
        

        client.player.sendMessage(Text.literal(Messages.get("housing.debug.header")), false);
        

        boolean onHypixel = isOnHypixel();
        client.player.sendMessage(Text.literal(Messages.format("housing.debug.hypixel", 
            "status", onHypixel ? Messages.get("common.yes") : Messages.get("common.no"))), false);
        
        if (!onHypixel) {
            client.player.sendMessage(Text.literal(Messages.get("housing.debug.not_hypixel")), false);
            return;
        }
        

        if (client.inGameHud == null) {
            client.player.sendMessage(Text.literal(Messages.get("housing.debug.no_network")), false);
            return;
        }
        
        PlayerListHud playerListHud = client.inGameHud.getPlayerListHud();
        if (playerListHud == null) {
            client.player.sendMessage(Text.literal(Messages.get("housing.debug.no_network")), false);
            return;
        }
        

        PlayerListHudAccessor accessor = (PlayerListHudAccessor) playerListHud;
        Text header = accessor.getHeader();
        Text footer = accessor.getFooter();
        

        if (header != null && !header.getString().isEmpty()) {
            String headerStr = stripColorCodes(header.getString());
            client.player.sendMessage(Text.literal(Messages.get("housing.debug.tab_header")), false);
            String displayHeader = headerStr.length() > 80 ? headerStr.substring(0, 80) + "..." : headerStr;
            client.player.sendMessage(Text.literal(Messages.getColorText() + displayHeader), false);
        } else {
            client.player.sendMessage(Text.literal(Messages.get("housing.debug.no_tab_header")), false);
        }
        

        if (footer != null && !footer.getString().isEmpty()) {
            String footerStr = stripColorCodes(footer.getString());
            client.player.sendMessage(Text.literal(Messages.get("housing.debug.tab_footer")), false);
            String displayFooter = footerStr.length() > 80 ? footerStr.substring(0, 80) + "..." : footerStr;
            client.player.sendMessage(Text.literal(Messages.getColorText() + displayFooter), false);
        } else {
            client.player.sendMessage(Text.literal(Messages.get("housing.debug.no_tab_footer")), false);
        }
        

        StringBuilder tabContent = new StringBuilder();
        if (header != null) tabContent.append(stripColorCodes(header.getString())).append(" ");
        if (footer != null) tabContent.append(stripColorCodes(footer.getString()));
        String content = tabContent.toString();
        

        boolean hasHousingName = TAB_HOUSING_PATTERN.matcher(content).find();
        boolean hasOwner = TAB_OWNER_PATTERN.matcher(content).find();
        
        if (hasHousingName) {
            client.player.sendMessage(Text.literal(Messages.get("housing.debug.game_found_tab")), false);
        } else {
            client.player.sendMessage(Text.literal(Messages.get("housing.debug.game_not_found_tab")), false);
        }
        
        if (hasOwner) {
            client.player.sendMessage(Text.literal(Messages.format("housing.debug.owner_found", "owner", "Cyborg023")), false);
        } else {
            client.player.sendMessage(Text.literal(Messages.format("housing.debug.owner_not_found", "owner", "Cyborg023")), false);
        }
        

        if (detectedViaChat) {
            client.player.sendMessage(Text.literal(Messages.get("housing.debug.method_chat")), false);
        } else if (inDptb2Housing) {
            client.player.sendMessage(Text.literal(Messages.get("housing.debug.method_tab")), false);
        }
        

        client.player.sendMessage(Text.literal(Messages.format("housing.debug.status", 
            "status", inDptb2Housing ? Messages.get("housing.status.in") : Messages.get("housing.status.out"))), false);
    }
}
