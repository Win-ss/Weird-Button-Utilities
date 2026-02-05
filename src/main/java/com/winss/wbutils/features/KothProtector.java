package com.winss.wbutils.features;

import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.Messages;
import com.winss.wbutils.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import com.winss.wbutils.network.NetworkManager;

public class KothProtector {
    private static final Pattern KOTH_TITLE_PATTERN = Pattern.compile("\\d+\\s*[⛀-⛃]/[sS]\\s*\\|\\s*\\d+\\s*✌");
    private static final Pattern DEATH_BOUNTY_PATTERN = Pattern.compile("You earned [\\d,]+.? from your bounty", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEATH_BOUNTY_PATTERN_ALT = Pattern.compile("from your bounty", Pattern.CASE_INSENSITIVE);
    private static final long KOTH_PERSISTENCE_MS = 2000L;
    private static final long KOTH_MIN_DURATION_MS = 10_000L;
    private static final int TICK_DEBOUNCE = 10;
    private static final long INPUT_AFK_THRESHOLD_MS = 3000L;
    private static final long DAMAGE_ALERT_COOLDOWN_MS = 10_000L;

    private long lastKothTitleTime = 0L;
    private long lastDamageAlertTime = 0L;
    private long kothEntryTime = 0L;
    private boolean entryNotified = false;
    private boolean exitNotificationSent = false;
    private boolean missingConfigWarned = false;
    private boolean deathNotificationSent = false;
    private int tickCounter = 0;
    private int cachedGoldBlocks = -1;
    private long lastGoldCountTime = 0L;
    private long lastInputTime = 0L;

    /**
     * Check if player is currently in KOTH area
     */
    public boolean isInKoth() {
        long now = Util.getMeasuringTimeMs();
        return (now - lastKothTitleTime) <= KOTH_PERSISTENCE_MS;
    }

    public long getLastKothTitleTime() {
        return lastKothTitleTime;
    }

    public void onPlayerInput() {
        lastInputTime = Util.getMeasuringTimeMs();
    }

    public void onPlayerDamaged(float amount, String attackerName, String damageType) {
        long now = Util.getMeasuringTimeMs();
        
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.kothProtectorEnabled || !config.kothNotifyOnDamage) {
            return;
        }
        

        boolean inKothArea = (now - lastKothTitleTime) <= KOTH_PERSISTENCE_MS;
        if (!inKothArea) {
            return;
        }
        

        KillTracker killTracker = WBUtilsClient.getKillTracker();
        if (killTracker != null) {
            killTracker.onPlayerDamagedInKoth(amount, attackerName);
        }
        

        if ((now - lastDamageAlertTime) < DAMAGE_ALERT_COOLDOWN_MS) {
            return;
        }
        

        if (!canSendAlerts(config)) {
            return;
        }
        
        lastDamageAlertTime = now;
        
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        
        if (player == null) {
            return;
        }
        

        java.util.List<String> nearbyPlayers = new java.util.ArrayList<>();
        if (client.world != null) {
            for (AbstractClientPlayerEntity otherPlayer : client.world.getPlayers()) {
                if (otherPlayer == player) continue;
                double dist = player.distanceTo(otherPlayer);
                if (dist <= 5.0) {
                    nearbyPlayers.add(otherPlayer.getName().getString() + " (" + String.format("%.1f", dist) + "m)");
                }
            }
        }
        
        int goldBlocks = getCachedGoldCount(player, now);
        String description;
        if (!nearbyPlayers.isEmpty()) {
            description = Messages.format("webhook.attack.description.nearby", "nearby", String.join("**, **", nearbyPlayers), "damage", String.format("%.1f", amount), "gold", String.valueOf(goldBlocks));
        } else {
            description = Messages.format("webhook.attack.description.none", "damage", String.format("%.1f", amount), "gold", String.valueOf(goldBlocks));
        }
        
        String attackTitle = Messages.format("webhook.attack.title", "player", player.getGameProfile().getName());
        sendAlert(config, attackTitle, description, 0xE74C3C, true);
        
        if (config.kothDebugLogs) {
            sendDebug(player, Messages.getColorAccent() + Messages.format("debug.damage_alert", "nearby", (nearbyPlayers.isEmpty() ? "none" : String.join(", ", nearbyPlayers))));
        }
    }

    public void onClientTick() {
        tickCounter++;
        if (tickCounter < TICK_DEBOUNCE) {
            return;
        }
        tickCounter = 0;
        
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            resetState();
            return;
        }

        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.kothProtectorEnabled) {
            resetState();
            return;
        }
        
        if (!canSendAlerts(config)) {
            if (config.kothDebugLogs && !missingConfigWarned) {
                sendDebug(player, Messages.getColorAccent() + Messages.get("debug.alerts_not_configured"));
                missingConfigWarned = true;
            }
            return;
        }
        missingConfigWarned = false;

        long now = Util.getMeasuringTimeMs();
        
        boolean inKothArea = (now - lastKothTitleTime) <= KOTH_PERSISTENCE_MS;

        if (inKothArea) {
            if (kothEntryTime == 0L) {
                kothEntryTime = now;
                entryNotified = false;
                exitNotificationSent = false;
                deathNotificationSent = false;
                if (config.kothDebugLogs) {
                    sendDebug(player, Messages.getColorAccent() + Messages.get("debug.entered"));
                }
            }
        } else {
            // Exited KOTH - send alert if we were in KOTH and got moved out (not from death)
            // Only ping if player was AFK (no input in last 3 seconds) - they didn't walk out themselves
            if (kothEntryTime != 0L && entryNotified && !exitNotificationSent && !deathNotificationSent && config.kothNotifyOnExit) {
                long timeSinceInput = now - lastInputTime;
                boolean wasAfk = timeSinceInput >= INPUT_AFK_THRESHOLD_MS;
                
                exitNotificationSent = true;
                
                if (wasAfk) {
                    String exitTitle = Messages.format("webhook.exit.title", "player", player.getGameProfile().getName());
                    sendAlert(config, exitTitle, Messages.get("webhook.exit.description"), 0xFFA500, true);
                    
                    if (config.kothDebugLogs) {
                        sendDebug(player, Messages.getColorAccent() + Messages.get("debug.left") + " while AFK! Alert sent.");
                    }
                } else if (config.kothDebugLogs) {
                    sendDebug(player, "§7Left KOTH with recent input (" + timeSinceInput + "ms ago) - no ping");
                }
            } else if (kothEntryTime != 0L && config.kothDebugLogs) {
                sendDebug(player, "§cLeft KOTH");
            }
            kothEntryTime = 0L;
            entryNotified = false;
        }
        
        if (!inKothArea) {
            return;
        }


        long timeInKoth = now - kothEntryTime;
        if (!entryNotified && timeInKoth >= KOTH_MIN_DURATION_MS && config.kothNotifyOnEntry) {
            entryNotified = true;
            int goldBlocks = getCachedGoldCount(player, now);
            String entryTitle = Messages.format("webhook.entry.title", "player", player.getGameProfile().getName());
            String entryDesc = Messages.format("webhook.entry.description", "gold", String.valueOf(goldBlocks));

            sendAlert(config, entryTitle, entryDesc, 0x2ECC71, false);
            if (config.kothDebugLogs) {
                sendDebug(player, Messages.getColorAccent() + Messages.get("debug.entered") + " (entry alert sent)");
            }
        }
    }

    public void handleTitle(Text title) {
        if (title == null) {
            return;
        }

        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.kothProtectorEnabled) {
            return;
        }

        String plain = title.getString();
        if (plain == null || plain.isEmpty()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        

        if (config.kothDebugLogs) {
            if (client.player != null) {
                String cleaned = plain.replaceAll("§[0-9a-fk-or]", "");
                if (!cleaned.trim().isEmpty()) {
                    sendDebug(client.player, "§7[Title received] " + truncate(cleaned, 70));
                }
            }
        }

        if (isKothIndicator(plain)) {
            lastKothTitleTime = Util.getMeasuringTimeMs();
            if (config.kothDebugLogs && client.player != null) {
                String cleaned = plain.replaceAll("§[0-9a-fk-or]", "");
                sendDebug(client.player, Messages.getColorAccent() + "✓ KOTH: " + truncate(cleaned, 40));
            }
        }
    }

    private boolean isKothIndicator(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }


        String stripped = text.replaceAll("§[0-9a-fk-or]", "").trim();
        

        if (stripped.contains("Timer:") || stripped.contains("⛏") || stripped.contains("combat")) {
            return false;
        }
        
        // Check for KOTH pattern: number + symbol/s | number + ✌
        return KOTH_TITLE_PATTERN.matcher(stripped).find();
    }

    public void handleChatMessage(Text message) {
        if (message == null) {
            return;
        }

        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.kothProtectorEnabled || !config.kothNotifyOnDeath) {
            return;
        }

        String plain = message.getString();
        if (plain == null || plain.isEmpty()) {
            return;
        }

        String stripped = plain.replaceAll("§[0-9a-fk-or]", "").trim();
        
        if (config.debugBounty && stripped.toLowerCase().contains("earned") && stripped.toLowerCase().contains("bounty")) {
            MinecraftClient client = MinecraftClient.getInstance();
            WBUtilsClient.LOGGER.info("[KothProtector] ========== BOUNTY KEYWORD FOUND ==========");
            WBUtilsClient.LOGGER.info("[KothProtector] Stripped message: {}", stripped);
            
            boolean pattern1Match = DEATH_BOUNTY_PATTERN.matcher(stripped).find();
            boolean pattern2Match = DEATH_BOUNTY_PATTERN_ALT.matcher(stripped).find();
            
            WBUtilsClient.LOGGER.info("[KothProtector] Pattern1 (full) match: {}", pattern1Match);
            WBUtilsClient.LOGGER.info("[KothProtector] Pattern2 (simple) match: {}", pattern2Match);
            WBUtilsClient.LOGGER.info("[KothProtector] deathNotificationSent: {}", deathNotificationSent);
            
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§d[DEBUG:Bounty] Checking: '" + stripped + "'"), false);
                client.player.sendMessage(Text.literal("§d[DEBUG:Bounty] Pattern match: " + pattern2Match), false);
            }
        }

        boolean bountyMatch = DEATH_BOUNTY_PATTERN_ALT.matcher(stripped).find();
        if (bountyMatch && !deathNotificationSent) {
            long now = Util.getMeasuringTimeMs();
            boolean recentlyInKoth = (now - lastKothTitleTime) <= 3000L;
            
            MinecraftClient client = MinecraftClient.getInstance();
            
            if (config.debugBounty) {
                WBUtilsClient.LOGGER.info("[KothProtector] Bounty message detected! recentlyInKoth={}", recentlyInKoth);
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("§d[DEBUG:Bounty] Detection:"), false);
                    client.player.sendMessage(Text.literal("§7- Message: \"" + stripped + "\""), false);
                    client.player.sendMessage(Text.literal("§7- recentlyInKoth: " + recentlyInKoth), false);
                    client.player.sendMessage(Text.literal("§7- In KOTH: " + isInKoth()), false);
                }
            }
            
            // Notify kill tracker when bounty is detected (death = bounty) - ONLY if recently in KOTH
            KillTracker killTracker = WBUtilsClient.getKillTracker();
            if (killTracker != null && recentlyInKoth) {
                if (config.debugBounty) {
                    WBUtilsClient.LOGGER.info("[KothProtector] Calling killTracker.onPlayerDiedInKoth()");
                    if (client.player != null) {
                        client.player.sendMessage(Text.literal("§d[DEBUG:Bounty] Calling killTracker.onPlayerDiedInKoth()"), false);
                    }
                }
                killTracker.onPlayerDiedInKoth();
            } else if (killTracker != null && config.debugBounty) {
                WBUtilsClient.LOGGER.info("[KothProtector] Bounty detected but NOT recently in KOTH - not calling killTracker");
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("§e[DEBUG:Bounty] Bounty detected but NOT recently in KOTH"), false);
                }
            } else if (killTracker == null) {
                WBUtilsClient.LOGGER.warn("[KothProtector] KillTracker is null!");
                if (config.debugBounty && client.player != null) {
                    client.player.sendMessage(Text.literal("§c[DEBUG:Bounty] ERROR: KillTracker is null!"), false);
                }
            }
            
            if (recentlyInKoth) {
                deathNotificationSent = true;
                
                ClientPlayerEntity player = client.player;
                
                if (player != null && canSendAlerts(config)) {
                    int goldBlocks = getCachedGoldCount(player, now);
                    String deathTitle = Messages.format("webhook.death.title", "player", player.getGameProfile().getName(), "gold", String.valueOf(goldBlocks));
                    String deathDesc = Messages.format("webhook.death.description", "gold", String.valueOf(goldBlocks));
                    sendAlert(config, deathTitle, deathDesc, 0xFF0000, true);
                    
                    if (config.debugKothState) {
                        sendDebug(player, Messages.getColorAccent() + Messages.get("debug.death_detected") + " (carrying " + goldBlocks + " gold)");
                    }
                }
            } else if (config.debugBounty) {
                if (client.player != null) {
                    sendDebug(client.player, "§7Death bounty detected but not in KOTH (" + (now - lastKothTitleTime) + "ms ago)");
                }
            }
        }
    }

    private void resetState() {
        kothEntryTime = 0L;
        entryNotified = false;
        exitNotificationSent = false;
        deathNotificationSent = false;
        cachedGoldBlocks = -1;
    }
    
    private int getCachedGoldCount(ClientPlayerEntity player, long now) {
        if (cachedGoldBlocks < 0 || (now - lastGoldCountTime) > 2000L) {
            cachedGoldBlocks = player.getInventory().count(Items.GOLD_BLOCK);
            lastGoldCountTime = now;
        }
        return cachedGoldBlocks;
    }
    
    /**
     * Check if alerts can be sent (either via auth system or legacy webhooks)
     */
    private boolean canSendAlerts(ModConfig config) {
        if (config.authServerUrl != null && !config.authServerUrl.isBlank()) {
            return true;
        }
        return config.webhookUrl != null && !config.webhookUrl.isBlank();
    }
    
    /**
     * Send an alert using the appropriate method (auth system or legacy webhooks)
     */
    private void sendAlert(ModConfig config, String title, String description, int color, boolean ping) {
        if (config.authServerUrl != null && !config.authServerUrl.isBlank()) {
            AuthService.sendAlert(title, description, color, ping, result -> {
                switch (result) {
                    case SUCCESS:
                        postDebug(Messages.get("debug.alert_sent_dm"));
                        break;
                    case NOT_LINKED:
                        postDebug(Messages.get("debug.not_linked_fallback"));
                        sendWebhookFallback(config, title, description, color, ping);
                        break;
                    case DM_DISABLED:
                        postDebug(Messages.get("debug.dm_disabled_fallback"));
                        sendWebhookFallback(config, title, description, color, ping);
                        break;
                    case ERROR:
                        postDebug(Messages.get("debug.auth_error_fallback"));
                        sendWebhookFallback(config, title, description, color, ping);
                        break;
                }
            });
        } else {
            sendWebhookFallback(config, title, description, color, ping);
        }
    }
    
    private void sendWebhookFallback(ModConfig config, String title, String description, int color, boolean ping) {
        if (config.webhookUrl == null || config.webhookUrl.isBlank()) {
            postDebug(Messages.get("debug.no_fallback_webhook"));
            return;
        }
        
        String pingStr = "";
        if (ping && config.discordUserId != null && !config.discordUserId.isEmpty()) {
            pingStr = "<@" + config.discordUserId + ">";
        }
        
        sendWebhook(config.webhookUrl, pingStr, title, description, null, color);
    }

    private void sendWebhook(String webhookUrl, String ping, String title, String description, String imageUrl, int color) {
        sendWebhook(webhookUrl, ping, title, description, imageUrl, color, false, null);
    }

    private void sendWebhook(String webhookUrl, String ping, String title, String description, String imageUrl, int color, boolean useEmbed) {
        sendWebhook(webhookUrl, ping, title, description, imageUrl, color, useEmbed, null);
    }

    private void sendWebhook(String webhookUrl, String ping, String title, String description, String imageUrl, int color, Runnable onComplete) {
        sendWebhook(webhookUrl, ping, title, description, imageUrl, color, false, onComplete);
    }

    private void sendWebhook(String webhookUrl, String ping, String title, String description, String imageUrl, int color, boolean useEmbed, Runnable onComplete) {
        StringBuilder payload = new StringBuilder();
        payload.append('{');
        payload.append("\"username\":\"").append(escapeJson(Messages.get("webhook.username"))).append("\",");
        if (useEmbed) {
            payload.append("\"embeds\":[{");
            payload.append("\"title\":\"").append(escapeJson(title == null ? "" : title)).append("\",");
            payload.append("\"description\":\"").append(escapeJson(description == null ? "" : description)).append("\",");
            payload.append("\"color\":").append(color);
            if (imageUrl != null) {
                payload.append(',');
                payload.append("\"thumbnail\":{\"url\":\"").append(escapeJson(imageUrl)).append("\"}");
            }
            payload.append("}]");
        } else {
            StringBuilder contentText = new StringBuilder();
            if (ping != null && !ping.isEmpty()) {
                contentText.append(ping).append(" ");
            }
            if (title != null && !title.isEmpty()) {
                contentText.append(title);
            }
            if (description != null && !description.isEmpty()) {
                if (contentText.length() > 0) contentText.append("\\n");
                contentText.append(description);
            }
            payload.append("\"content\":\"").append(escapeJson(contentText.toString())).append("\"");
        }
        payload.append('}');

        NetworkManager.post(webhookUrl, payload.toString()).thenAccept(response -> {
            int code = response.statusCode();
            postDebug(Messages.format("debug.webhook_post_completed", "code", String.valueOf(code)));
            if (onComplete != null) {
                onComplete.run();
            }
        }).exceptionally(ex -> {
            WBUtilsClient.LOGGER.error("Failed to send KOTH Protector webhook", ex);
            postDebug(Messages.format("debug.webhook_failed", "error", ex.getClass().getSimpleName() + " - " + truncate(ex.getMessage(), 120)));
            if (onComplete != null) {
                onComplete.run();
            }
            return null;
        });
    }

    private String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void sendDebug(ClientPlayerEntity player, String message) {
        if (player == null) {
            return;
        }
        MutableText text = Text.empty()
            .append(Text.literal("[KOTH Debug] ").formatted(Formatting.DARK_GRAY))
            .append(Text.literal(message).formatted(Formatting.GRAY));
        player.sendMessage(text, false);
        WBUtilsClient.LOGGER.debug("[KOTH Debug] {}", message);
    }

    private void postDebug(String message) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.kothDebugLogs) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            ClientPlayerEntity player = client.player;
            if (player != null) {
                sendDebug(player, message);
            }
        });
    }

    public void sendTestPing() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        MinecraftClient client = MinecraftClient.getInstance();
        
        // Try auth system first
        if (config.authServerUrl != null && !config.authServerUrl.isBlank()) {
            client.execute(() -> {
                ClientPlayerEntity player = client.player;
                if (player != null) {
                    sendDebug(player, "Sending test alert via Discord DM...");
                }
            });
            
            AuthService.sendTestAlert(result -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> {
                    ClientPlayerEntity player = mc.player;
                    if (player != null) {
                        switch (result) {
                            case SUCCESS:
                                sendDebug(player, Messages.getColorAccent() + Messages.get("debug.test_ping_completed"));
                                player.sendMessage(Text.literal(Messages.getColorAccent() + Messages.get("message.test_confirmed")), false);
                                break;
                            case NOT_LINKED:
                                player.sendMessage(Text.literal("§c" + Messages.get("command.auth.test_not_linked")), false);
                                sendWebhookTestFallback(config);
                                break;
                            case DM_DISABLED:
                                player.sendMessage(Text.literal("§c" + Messages.get("command.auth.test_dm_disabled")), false);
                                sendWebhookTestFallback(config);
                                break;
                            case ERROR:
                                player.sendMessage(Text.literal("§c" + Messages.get("command.auth.test_error")), false);
                                sendWebhookTestFallback(config);
                                break;
                        }
                    }
                });
            });
        } else if (config.webhookUrl != null && !config.webhookUrl.isBlank()) {
            sendWebhookTestFallback(config);
        } else {
            client.execute(() -> {
                ClientPlayerEntity player = client.player;
                if (player != null) {
                    player.sendMessage(Text.literal("§c" + Messages.get("debug.alerts_not_configured")), false);
                }
            });
        }
    }
    
    private void sendWebhookTestFallback(ModConfig config) {
        if (config.webhookUrl == null || config.webhookUrl.isBlank()) {
            return;
        }
        
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            ClientPlayerEntity player = client.player;
            if (player != null) {
                sendDebug(player, "Sending webhook test ping (fallback)...");
            }
        });

        sendWebhook(config.webhookUrl,
            null,
            Messages.get("webhook.test.title"),
            Messages.get("webhook.test.description"),
            null,
            0x1ABC9C,
            () -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> {
                    ClientPlayerEntity player = mc.player;
                    if (player != null) {
                        sendDebug(player, Messages.getColorAccent() + Messages.get("debug.test_ping_completed"));
                        player.sendMessage(Text.literal(Messages.getColorAccent() + Messages.get("message.test_confirmed") + " " + Messages.get("message.test_via_webhook")), false);
                    }
                });
            });
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
