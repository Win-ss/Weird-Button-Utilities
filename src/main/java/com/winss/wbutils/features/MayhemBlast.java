package com.winss.wbutils.features;

import com.winss.wbutils.Messages;
import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.config.ModConfig;
import com.winss.wbutils.network.NetworkManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.lwjgl.glfw.GLFW;

/**
 * MayhemBlast Feature
 * Detects the mayhem button event from server system chat and plays alert.wav.
 * 
 * Triggers when:
 * - Window is active (always)
 * - Window is inactive but has been inactive for less than 1 minute
 */
public class MayhemBlast {

    private static final String MAYHEM_MESSAGE = "* [!] MAYHEM! The BUTTON has no cooldown for 10s!";

    private static final long ALERT_COOLDOWN_MS = 5_000L; 
    private static final long REPORT_DELAY_MS = 4_000L; 
    private static final long FULLSCREEN_RESTORE_DELAY_S = 15L;

    private long lastFocusLostTime = 0L;
    private boolean wasFocused = true;
    private long lastAlertTime = 0L;

    // Tracks whether the user was already fullscreen before its forced.
    private boolean wasFullscreenBeforeBlast = false;
    private boolean fullscreenForcedByUs = false;
    private final ScheduledExecutorService restoreScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MayhemBlast-FSRestore");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pendingRestore = null;

    public MayhemBlast() {
        WBUtilsClient.LOGGER.info("[MayhemBlast] Initialized");
    }

    // track window time  
    public void onClientTick() {
        MinecraftClient client = MinecraftClient.getInstance();
        boolean currentlyFocused = client.isWindowFocused();

        if (wasFocused && !currentlyFocused) {
            lastFocusLostTime = System.currentTimeMillis();
        }

        wasFocused = currentlyFocused;
    }

    public void handleChatMessage(Text message) {
        if (message == null) return;

        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.mayhemBlastEnabled) return;

        String plain = message.getString();
        if (plain == null) return;

        String stripped = plain.replaceAll("§[0-9a-fk-or]", "").trim();

        if (config.debugMayhemBlast) {
            WBUtilsClient.LOGGER.info("[MayhemBlast] Checking message: {}", stripped);
        }

        if (!stripped.equals(MAYHEM_MESSAGE)) {
            return;
        }

        if (!isAuthenticMayhemMessage(message, plain, stripped)) {
            if (config.debugMayhemBlast) {
                WBUtilsClient.LOGGER.info("[MayhemBlast] Message failed authenticity check, ignoring");
            }
            return;
        }

        if (config.debugMayhemBlast) {
            WBUtilsClient.LOGGER.info("[MayhemBlast] Authentic mayhem message detected!");
        }

        long now = System.currentTimeMillis();
        if ((now - lastAlertTime) < ALERT_COOLDOWN_MS) {
            if (config.debugMayhemBlast) {
                WBUtilsClient.LOGGER.info("[MayhemBlast] Alert on cooldown, skipping");
            }
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        boolean focused = client.isWindowFocused();

        if (config.debugMayhemBlast) {
            long inactiveMs = focused ? 0 : (now - lastFocusLostTime);
            debugMsg(Messages.format("mayhem.debug.window",
                "focused", String.valueOf(focused),
                "ms", String.valueOf(inactiveMs)));
        }

        if (!focused) {
            long inactiveDuration = now - lastFocusLostTime;
            long inactiveLimit = config.mayhemInactivitySeconds * 1000L;
            if (inactiveDuration > inactiveLimit) {
                if (config.debugMayhemBlast) {
                    WBUtilsClient.LOGGER.info("[MayhemBlast] Window inactive for {}ms (>{}s), ignoring", inactiveDuration, config.mayhemInactivitySeconds);
                }
                notifyPlayer(Messages.format("mayhem.ignored_inactive", "seconds", String.valueOf(config.mayhemInactivitySeconds)));

                final long inactiveMsForReport = inactiveDuration;
                CompletableFuture.runAsync(() -> sendMayhemReport(false, true, inactiveMsForReport));

                return;
            }
        }

        lastAlertTime = now;

        if (focused) {
            notifyPlayer(Messages.get("mayhem.detected"));
        } else {
            notifyPlayer(Messages.get("mayhem.detected_inactive"));
        }

        playAlertSound();

        final boolean focusedAtDetection = focused;
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(REPORT_DELAY_MS);
            } catch (InterruptedException ignored) {}
            sendMayhemReport(focusedAtDetection, false, 0L);
        });

        if (config.mayhemDedicationMode) {
            if (!focused) {
                forceFullscreen(client);
            } else if (config.debugMayhemBlast) {
                WBUtilsClient.LOGGER.info("[MayhemBlast] Dedication mode enabled but window already focused; skipping fullscreen");
            }
        }
    }


    private boolean isAuthenticMayhemMessage(Text message, String plain, String stripped) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        boolean hasFormattingCodes = plain.contains("§");

        List<Text> siblings = message.getSiblings();
        boolean hasStyledSiblings = false;
        if (siblings != null && !siblings.isEmpty()) {
            for (Text sibling : siblings) {
                if (sibling.getStyle() != null && (
                    sibling.getStyle().getColor() != null ||
                    sibling.getStyle().isBold() ||
                    sibling.getStyle().isItalic())) {
                    hasStyledSiblings = true;
                    break;
                }
            }
        }

        boolean hasRootStyle = message.getStyle() != null && (
            message.getStyle().getColor() != null ||
            message.getStyle().isBold());

        boolean looksLikePlayerChat = stripped.matches("^(\\[[^\\]]+\\]\\s*)*[A-Za-z0-9_]{1,16}:\\s.*");
        if (looksLikePlayerChat) {
            if (config.debugMayhemBlast) {
                WBUtilsClient.LOGGER.info("[MayhemBlast] Rejected: looks like player chat");
            }
            return false;
        }

        boolean authentic = hasFormattingCodes || hasStyledSiblings || hasRootStyle;

        if (config.debugMayhemBlast) {
            WBUtilsClient.LOGGER.info("[MayhemBlast] Auth check - formattingCodes={}, styledSiblings={}, rootStyle={}, result={}",
                hasFormattingCodes, hasStyledSiblings, hasRootStyle, authentic);
            debugMsg(Messages.format("mayhem.debug.authentic", "status", String.valueOf(authentic)));
        }

        return authentic;
    }

    private void forceFullscreen(MinecraftClient client) {
        client.execute(() -> {
            long handle = client.getWindow().getHandle();

            // remember the current fullscreen state before the mod touches things
            wasFullscreenBeforeBlast = client.getWindow().isFullscreen();

            if (!wasFullscreenBeforeBlast) {
                client.getWindow().toggleFullscreen();
                client.options.getFullscreen().setValue(true);
                fullscreenForcedByUs = true;
                WBUtilsClient.LOGGER.info("[MayhemBlast] Dedication mode: forced fullscreen (was windowed)");
            } else {
                fullscreenForcedByUs = false;
                WBUtilsClient.LOGGER.info("[MayhemBlast] Dedication mode: user already fullscreen, no change needed");
            }

            GLFW.glfwRestoreWindow(handle);
            GLFW.glfwFocusWindow(handle);
            GLFW.glfwRequestWindowAttention(handle);

            // schedule fullscreen restore only if the mod forced it on
            if (fullscreenForcedByUs) {
                if (pendingRestore != null && !pendingRestore.isDone()) {
                    pendingRestore.cancel(false);
                }
                pendingRestore = restoreScheduler.schedule(() -> restoreFullscreenState(client), FULLSCREEN_RESTORE_DELAY_S, TimeUnit.SECONDS);
            }
        });
    }

    private void restoreFullscreenState(MinecraftClient client) {
        client.execute(() -> {
            if (!fullscreenForcedByUs) {
                return;
            }

            if (client.getWindow().isFullscreen()) {
                client.getWindow().toggleFullscreen();
                client.options.getFullscreen().setValue(false);
                WBUtilsClient.LOGGER.info("[MayhemBlast] Restored windowed mode after {}s", FULLSCREEN_RESTORE_DELAY_S);
            } else {
                WBUtilsClient.LOGGER.info("[MayhemBlast] User already exited fullscreen, no restore needed");
            }

            fullscreenForcedByUs = false;
        });
    }

    private void playAlertSound() {
        new Thread(() -> {
            try (InputStream is = MayhemBlast.class.getResourceAsStream("/assets/wbutils/alert.wav")) {
                if (is == null) {
                    WBUtilsClient.LOGGER.warn("[MayhemBlast] alert.wav not found in resources!");
                    return;
                }

                BufferedInputStream bis = new BufferedInputStream(is);
                AudioInputStream audioIn = AudioSystem.getAudioInputStream(bis);
                Clip clip = AudioSystem.getClip();
                clip.open(audioIn);
                clip.start();

                clip.addLineListener(event -> {
                    if (event.getType() == LineEvent.Type.STOP) {
                        clip.close();
                    }
                });
            } catch (Exception e) {
                WBUtilsClient.LOGGER.error("[MayhemBlast] Failed to play alert sound: {}", e.getMessage());
            }
        }, "MayhemBlast-Sound").start();
    }

    private void notifyPlayer(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.execute(() -> {
                ClientPlayerEntity player = client.player;
                if (player != null) {
                    player.sendMessage(Text.literal(message), false);
                }
            });
        }
    }

    private void debugMsg(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.execute(() -> {
                ClientPlayerEntity player = client.player;
                if (player != null) {
                    player.sendMessage(Text.literal(message), false);
                }
            });
        }
    }

    /* --- Network reporting --- */
    private void sendMayhemReport(boolean focusedAtDetection, boolean ignored, long inactiveDurationMs) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (config.authServerUrl == null || config.authServerUrl.isBlank()) {
            if (config.debugMayhemBlast) WBUtilsClient.LOGGER.warn("[MayhemBlast] No auth server URL configured, skipping report");
            return;
        }

        if (config.authToken == null || config.authToken.isBlank()) {
            if (config.debugMayhemBlast) WBUtilsClient.LOGGER.warn("[MayhemBlast] No auth token configured, skipping report");
            return;
        }

        CompletableFuture.runAsync(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            String reporterUuid = player != null ? player.getUuid().toString() : "";
            String reporterName = player != null ? player.getName().getString() : "";

            boolean responded = client.isWindowFocused();

            long ts = System.currentTimeMillis();

            String json = String.format(
                "{\"type\":\"mayhem\",\"reporter_uuid\":\"%s\",\"reporter_name\":\"%s\",\"focused_at_detection\":%b,\"responded\":%b,\"ignored\":%b,\"inactive_duration_ms\":%d,\"timestamp\":%d}",
                escapeJson(reporterUuid), escapeJson(reporterName), focusedAtDetection, responded, ignored, inactiveDurationMs, ts
            );

            String urlStr = config.authServerUrl + "/mayhem/report";
            if (config.debugMayhemBlast || config.debugHttp) {
                WBUtilsClient.LOGGER.info("[MayhemBlast] POST {} (token: {})", urlStr, config.authToken == null ? "(none)" : (config.authToken.length() > 4 ? config.authToken.substring(0,4) + "****" : "****"));
                WBUtilsClient.LOGGER.info("[MayhemBlast] Payload: {}", json);
            }

            NetworkManager.post(urlStr, json, config.authToken)
                .thenAccept(response -> {
                    if (config.debugHttp) WBUtilsClient.LOGGER.info("[MayhemBlast] Server response: {}", response.statusCode());
                })
                .exceptionally(e -> {
                    WBUtilsClient.LOGGER.error("[MayhemBlast] Failed to send mayhem report", e);
                    return null;
                });
        });
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
