package com.winss.wbutils.features;

import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.Messages;
import com.winss.wbutils.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.winss.wbutils.network.NetworkManager;

public class AuthService {
    private static final int TOKEN_LENGTH = 32;
    private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();
    

    public static String generateAuthToken() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            token.append(chars.charAt(SECURE_RANDOM.nextInt(chars.length())));
        }
        return token.toString();
    }

    public static void registerAuthToken(String token, Consumer<Boolean> callback) {
        CompletableFuture.runAsync(() -> {
            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            
            if (player == null) {
                runOnMainThread(() -> callback.accept(false));
                return;
            }
            
            String uuid = player.getUuid().toString();
            String name = player.getGameProfile().getName();
            
            config.authToken = token;
            config.minecraftUuid = uuid;
            config.minecraftName = name;
            WBUtilsClient.getConfigManager().save();
            
            String urlStr = config.authServerUrl + "/auth/register";
            String json = String.format(
                "{\"token\":\"%s\",\"minecraft_uuid\":\"%s\",\"minecraft_name\":\"%s\"}",
                escapeJson(token),
                escapeJson(uuid),
                escapeJson(name)
            );

            NetworkManager.post(urlStr, json)
                .thenAccept(response -> runOnMainThread(() -> callback.accept(response.isSuccess())))
                .exceptionally(e -> {
                    WBUtilsClient.LOGGER.warn("Auth token registration failed: {}", e.getMessage());
                    runOnMainThread(() -> callback.accept(false));
                    return null;
                });
        });
    }

    public static void checkLinkStatus(Consumer<Boolean> callback) {
        CompletableFuture.runAsync(() -> {
            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            
            if (player == null) {
                runOnMainThread(() -> callback.accept(false));
                return;
            }
            
            String uuid = player.getUuid().toString();
            String urlStr = config.authServerUrl + "/auth/check/" + uuid;

            NetworkManager.get(urlStr, false)
                .thenAccept(response -> {
                    boolean linked = response.isSuccess() && parseJsonBoolean(response.body(), "linked");
                    runOnMainThread(() -> callback.accept(linked));
                })
                .exceptionally(e -> {
                    WBUtilsClient.LOGGER.error("Failed to check link status: {}", e.getMessage());
                    runOnMainThread(() -> callback.accept(false));
                    return null;
                });
        });
    }
    
     
    public enum LinkCheckResult {
        LINKED,
        NOT_LINKED,
        ERROR
    }
    

    public static void checkLinkStatusSafe(Consumer<LinkCheckResult> callback) {
        CompletableFuture.runAsync(() -> {
            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            
            if (player == null) {
                runOnMainThread(() -> callback.accept(LinkCheckResult.ERROR));
                return;
            }
            
            if (config.authServerUrl == null || config.authServerUrl.isBlank()) {
                runOnMainThread(() -> callback.accept(LinkCheckResult.ERROR));
                return;
            }
            
            String uuid = player.getUuid().toString();
            
            String urlStr = config.authServerUrl + "/auth/check/" + uuid;

            NetworkManager.get(urlStr, false)
                .thenAccept(response -> {
                    if (!response.isSuccess()) {
                        runOnMainThread(() -> callback.accept(LinkCheckResult.NOT_LINKED));
                        return;
                    }
                    boolean linked = parseJsonBoolean(response.body(), "linked");
                    runOnMainThread(() -> callback.accept(linked ? LinkCheckResult.LINKED : LinkCheckResult.NOT_LINKED));
                })
                .exceptionally(e -> {
                    WBUtilsClient.LOGGER.debug("Link status check failed (server may be unreachable): {}", e.getMessage());
                    runOnMainThread(() -> callback.accept(LinkCheckResult.ERROR));
                    return null;
                });
        });
    }
    

    public static void sendAlert(String title, String description, int color, boolean ping, Consumer<AlertResult> callback) {
        CompletableFuture.runAsync(() -> {
            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            
            if (player == null) {
                runOnMainThread(() -> callback.accept(AlertResult.ERROR));
                return;
            }
            
            String uuid = player.getUuid().toString();
            
            String urlStr = config.authServerUrl + "/alert/send";
            String json = String.format(
                "{\"minecraft_uuid\":\"%s\",\"title\":\"%s\",\"description\":\"%s\",\"color\":%d,\"ping\":%s}",
                escapeJson(uuid),
                escapeJson(title),
                escapeJson(description),
                color,
                ping ? "true" : "false"
            );

            NetworkManager.post(urlStr, json, config.authToken)
                .thenAccept(response -> {
                    AlertResult result;
                    if (response.isSuccess()) {
                        result = AlertResult.SUCCESS;
                    } else if (response.statusCode() == 404) {
                        result = AlertResult.NOT_LINKED;
                    } else if (response.statusCode() == 403) {
                        result = AlertResult.DM_DISABLED;
                    } else {
                        WBUtilsClient.LOGGER.warn("Alert send failed with status {}", response.statusCode());
                        result = AlertResult.ERROR;
                    }
                    runOnMainThread(() -> callback.accept(result));
                })
                .exceptionally(e -> {
                    WBUtilsClient.LOGGER.error("Failed to send alert", e);
                    runOnMainThread(() -> callback.accept(AlertResult.ERROR));
                    return null;
                });
        });
    }
    

    public static void sendTestAlert(Consumer<AlertResult> callback) {
        CompletableFuture.runAsync(() -> {
            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            
            if (player == null) {
                runOnMainThread(() -> callback.accept(AlertResult.ERROR));
                return;
            }
            
            String uuid = player.getUuid().toString();
            String name = player.getGameProfile().getName();
            
            String urlStr = config.authServerUrl + "/alert/test";
            String json = String.format(
                "{\"minecraft_uuid\":\"%s\",\"minecraft_name\":\"%s\"}",
                escapeJson(uuid),
                escapeJson(name)
            );

            NetworkManager.post(urlStr, json, config.authToken)
                .thenAccept(response -> {
                    AlertResult result;
                    if (response.isSuccess()) {
                        result = AlertResult.SUCCESS;
                    } else if (response.statusCode() == 404) {
                        result = AlertResult.NOT_LINKED;
                    } else if (response.statusCode() == 403) {
                        result = AlertResult.DM_DISABLED;
                    } else {
                        WBUtilsClient.LOGGER.warn("Test alert failed with status {}", response.statusCode());
                        result = AlertResult.ERROR;
                    }
                    runOnMainThread(() -> callback.accept(result));
                })
                .exceptionally(e -> {
                    WBUtilsClient.LOGGER.error("Failed to send test alert", e);
                    runOnMainThread(() -> callback.accept(AlertResult.ERROR));
                    return null;
                });
        });
    }

    public static void unlinkAuth(Consumer<Boolean> callback) {
        CompletableFuture.runAsync(() -> {
            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            
            if (player == null || config.authServerUrl == null || config.authServerUrl.isBlank()) {
                runOnMainThread(() -> callback.accept(false));
                return;
            }
            
            String uuid = player.getUuid().toString();
            String urlStr = config.authServerUrl + "/auth/unlink";
            String json = String.format("{\"minecraft_uuid\":\"%s\"}", escapeJson(uuid));

            NetworkManager.post(urlStr, json, config.authToken)
                .thenAccept(response -> {
                    boolean success = response.isSuccess();
                    if (success) {
                        config.authToken = "";
                        WBUtilsClient.getConfigManager().save();
                    } else {
                        WBUtilsClient.LOGGER.warn("Server failed to unlink account: {}", response.statusCode());
                    }
                    runOnMainThread(() -> callback.accept(success));
                })
                .exceptionally(e -> {
                    WBUtilsClient.LOGGER.error("Failed to unlink account", e);
                    runOnMainThread(() -> callback.accept(false));
                    return null;
                });
        });
    }
    
     
    public enum AlertResult {
        SUCCESS,
        NOT_LINKED,
        DM_DISABLED,
        ERROR
    }
    
    private static final AtomicBoolean lastServerStatus = new AtomicBoolean(true);
    private static final AtomicLong lastStatusCheckTime = new AtomicLong(0);

    public static void testEndpoints(Consumer<String> logger) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        String baseUrl = config.authServerUrl;
        
        if (baseUrl == null || baseUrl.isBlank()) {
            logger.accept(Messages.get("auth.error.no_url"));
            return;
        }
        
        String[] getEndpoints = {
            "/health",
            "/autorejoin/disconnect-messages",
            "/ktrack/hotlist",
            "/ktrack/modusers",
            "/modusers/online",
            "/door/current",
            "/rps/stats"
        };
        
        logger.accept(Messages.format("auth.diag.testing", "url", baseUrl));
        
        CompletableFuture.runAsync(() -> {
            for (String endpoint : getEndpoints) {
                String urlStr = baseUrl + endpoint;
                long start = System.currentTimeMillis();
                
                NetworkManager.get(urlStr, false, config.authToken).thenAccept(response -> {
                    long duration = System.currentTimeMillis() - start;
                    int code = response.statusCode();
                    String status = (code >= 200 && code < 400) ? Messages.get("status.ok") : Messages.format("status.error", "code", String.valueOf(code));
                    logger.accept(Messages.format("auth.diag.endpoint", 
                        "endpoint", endpoint, "status", status, "duration", String.valueOf(duration)));
                }).exceptionally(e -> {
                    logger.accept(Messages.format("auth.diag.failed", 
                        "endpoint", endpoint, "error", e.getMessage()));
                    return null;
                }).join();
            }
            logger.accept(Messages.get("auth.diag.complete"));
        });
    }

    public static void checkConnectivityTick() {
        long now = System.currentTimeMillis();
        if (now - lastStatusCheckTime.get() < 60000) return;
        lastStatusCheckTime.set(now);
        
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (config.authServerUrl == null || config.authServerUrl.isBlank()) return;
        
        NetworkManager.get(config.authServerUrl + "/health", false)
            .thenAccept(response -> {
                boolean currentStatus = response.isSuccess();
                boolean previousStatus = lastServerStatus.getAndSet(currentStatus);
                
                if (previousStatus && !currentStatus) {
                    handleConnectionLoss();
                } else if (!previousStatus && currentStatus) {
                    handleConnectionRestored();
                }
            })
            .exceptionally(e -> {
                if (lastServerStatus.getAndSet(false)) {
                    handleConnectionLoss();
                }
                return null;
            });
    }
    
    private static void handleConnectionLoss() {
        runOnMainThread(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal(Messages.get("auth.connection.lost")), false);
            }
        });
    }
    
    private static void handleConnectionRestored() {
        runOnMainThread(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal(Messages.get("auth.connection.restored")), false);
            }
        });
    }

    private static void runOnMainThread(Runnable runnable) {
        MinecraftClient.getInstance().execute(runnable);
    }
    
    private static String escapeJson(String input) {
        if (input == null) return "";
        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
    
    /**
     * Check if the current player is blacklisted.
     * Called on housing join to verify account status.
     * 
     * @param callback Called with true if blacklisted, false otherwise
     */
    public static void checkBlacklistStatus(Consumer<Boolean> callback) {
        CompletableFuture.runAsync(() -> {
            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            
            if (player == null) {
                runOnMainThread(() -> callback.accept(false));
                return;
            }
            
            if (config.authServerUrl == null || config.authServerUrl.isBlank()) {
                runOnMainThread(() -> callback.accept(false));
                return;
            }
            
            String uuid = player.getUuid().toString();
            String urlStr = config.authServerUrl + "/auth/blacklist-check/" + uuid;

            NetworkManager.get(urlStr, false)
                .thenAccept(response -> {
                    boolean blacklisted = response.isSuccess() && parseJsonBoolean(response.body(), "blacklisted");
                    runOnMainThread(() -> callback.accept(blacklisted));
                })
                .exceptionally(e -> {
                    WBUtilsClient.LOGGER.debug("Blacklist check failed: {}", e.getMessage());
                    runOnMainThread(() -> callback.accept(false));
                    return null;
                });
        });
    }


    private static boolean parseJsonBoolean(String json, String field) {
        if (json == null || json.isBlank()) return false;
        try {
            Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(json);
            if (matcher.find()) {
                return "true".equalsIgnoreCase(matcher.group(1));
            }
        } catch (Exception e) {
            WBUtilsClient.LOGGER.error("Failed to parse JSON boolean field {}: {}", field, e.getMessage());
        }
        return false;
    }
}
// hi 