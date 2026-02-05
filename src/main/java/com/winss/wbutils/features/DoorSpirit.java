package com.winss.wbutils.features;

import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.config.ModConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.winss.wbutils.network.NetworkManager;

/**
 * Door Spirit Feature
 * Highlights the correct/incorrect "door" when near the door area.
 * 
 * Door regions:
 * - Door 1: X 62-65, Y 20-26, Z 81-85 (expanded for visibility)
 * - Door 2: X 57-60, Y 20-26, Z 81-85 (expanded for visibility)
 */
public class DoorSpirit {
    
    // Door region definitions (min/max coordinates)
    private static final BlockPos DOOR_1_MIN = new BlockPos(62, 20, 81);
    private static final BlockPos DOOR_1_MAX = new BlockPos(65, 26, 85);
    private static final BlockPos DOOR_2_MIN = new BlockPos(57, 20, 81);
    private static final BlockPos DOOR_2_MAX = new BlockPos(60, 26, 85);
    
    private static final double RENDER_DISTANCE = 30.0;
    
    private static final Pattern WRONG_DOOR_PATTERN = Pattern.compile("\\*\\s*RIP!\\s*That was the wrong door!", Pattern.CASE_INSENSITIVE);
    private static final Pattern CORRECT_DOOR_PATTERN = Pattern.compile("\\*\\s*YAY!\\s*You choose the correct door!", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOOR_CYCLE_PATTERN = Pattern.compile("\\*\\s*\\[!\\]\\s*The DOOR has cycled!", Pattern.CASE_INSENSITIVE);
    
    private static final float[] COLOR_CORRECT = {0.0f, 1.0f, 0.0f, 0.35f};
    private static final float[] COLOR_INCORRECT = {1.0f, 0.0f, 0.0f, 0.50f};
    private static final float[] COLOR_UNKNOWN = {0.5f, 0.5f, 0.5f, 0.35f};
    
    // Current door state
    private DoorState currentState = DoorState.UNKNOWN;
    private int correctDoor = 0;
    private long lastStateChangeTime = 0;
    private long lastServerFetchTime = 0;
    private static final long SERVER_FETCH_COOLDOWN_MS = 5000;
    private boolean needsWorldJoinFetch = false;
    
    private final List<DoorHistoryEntry> localHistory = new ArrayList<>();
    
    public enum DoorState {
        UNKNOWN,
        DOOR_1_CORRECT,
        DOOR_2_CORRECT
    }
    
    public DoorSpirit() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(this::onWorldRender);
        
        WBUtilsClient.LOGGER.info("[DoorSpirit] Initialized");
    }

    public void handleChatMessage(Text message) {
        if (message == null) return;
        
        String plain = message.getString();
        if (plain == null) return;
        
        String stripped = plain.replaceAll("§[0-9a-fk-or]", "").trim();
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        if (config.debugDoorSpirit) {
            WBUtilsClient.LOGGER.info("[DoorSpirit] Checking message: {}", stripped);
        }
        
        if (WRONG_DOOR_PATTERN.matcher(stripped).find()) {
            handleWrongDoor();
            return;
        }
        
        if (CORRECT_DOOR_PATTERN.matcher(stripped).find()) {
            handleCorrectDoor();
            return;
        }
        
        if (DOOR_CYCLE_PATTERN.matcher(stripped).find()) {
            handleDoorCycle();
            return;
        }
    }
        
     // Player went through the wrong door
 
    private void handleWrongDoor() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        
        if (player == null) return;
        
        // Determine which door the player went through based on their position
        int doorUsed = getPlayerNearestDoor(player);
        
        if (doorUsed == 0) {
            if (config.debugDoorSpirit) {
                WBUtilsClient.LOGGER.info("[DoorSpirit] Wrong door detected but can't determine which door");
            }
            return;
        }
        
        // The other door is correct
        int correctDoorNumber = (doorUsed == 1) ? 2 : 1;
        
        if (config.debugDoorSpirit) {
            WBUtilsClient.LOGGER.info("[DoorSpirit] Wrong door! Player used door {}, correct is door {}", doorUsed, correctDoorNumber);
        }
        
        updateDoorState(correctDoorNumber);
        sendDoorReport(doorUsed, false, correctDoorNumber);
        
        if (config.debugDoorSpirit && player != null) {
            player.sendMessage(Text.literal("§c[DoorSpirit] Wrong door! Door " + correctDoorNumber + " is correct."), false);
        }
    }
    
    // Player went through the correct door

    private void handleCorrectDoor() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        
        if (player == null) return;
        
        // Determine which door the player went through
        int doorUsed = getPlayerNearestDoor(player);
        
        if (doorUsed == 0) {
            if (config.debugDoorSpirit) {
                WBUtilsClient.LOGGER.info("[DoorSpirit] Correct door detected but can't determine which door");
            }
            return;
        }
        
        if (config.debugDoorSpirit) {
            WBUtilsClient.LOGGER.info("[DoorSpirit] Correct door! Player used door {}", doorUsed);
        }
        
        updateDoorState(doorUsed);
        sendDoorReport(doorUsed, true, doorUsed);
        
        if (config.debugDoorSpirit && player != null) {
            player.sendMessage(Text.literal("§a[DoorSpirit] Correct! Door " + doorUsed + " confirmed."), false);
        }
    }
    
    // Door has cycled - reset knowledge
    private void handleDoorCycle() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        
        if (config.debugDoorSpirit) {
            WBUtilsClient.LOGGER.info("[DoorSpirit] Door cycle detected! Resetting knowledge.");
        }
        
        if (correctDoor != 0) {
            addToHistory(correctDoor, lastStateChangeTime);
        }
        
        currentState = DoorState.UNKNOWN;
        correctDoor = 0;
        lastStateChangeTime = System.currentTimeMillis();
        
        sendDoorCycleReport();
        
        if (player != null) {
            player.sendMessage(Text.literal("§7[DoorSpirit] Doors cycled! Unknown which door is correct."), false);
        }
    }
    
    /**
     * Update the door state
     */
    private void updateDoorState(int correctDoorNumber) {
        this.correctDoor = correctDoorNumber;
        this.currentState = (correctDoorNumber == 1) ? DoorState.DOOR_1_CORRECT : DoorState.DOOR_2_CORRECT;
        this.lastStateChangeTime = System.currentTimeMillis();
    }

    private int getPlayerNearestDoor(ClientPlayerEntity player) {
        if (player == null) return 0;
        
        Vec3d playerPos = player.getPos();
        
        Vec3d door1Center = new Vec3d(
            (DOOR_1_MIN.getX() + DOOR_1_MAX.getX()) / 2.0,
            (DOOR_1_MIN.getY() + DOOR_1_MAX.getY()) / 2.0,
            DOOR_1_MIN.getZ()
        );
        Vec3d door2Center = new Vec3d(
            (DOOR_2_MIN.getX() + DOOR_2_MAX.getX()) / 2.0,
            (DOOR_2_MIN.getY() + DOOR_2_MAX.getY()) / 2.0,
            DOOR_2_MIN.getZ()
        );
        
        double dist1 = playerPos.distanceTo(door1Center);
        double dist2 = playerPos.distanceTo(door2Center);
        
        if (dist1 > 50 && dist2 > 50) {
            return 0;
        }
        
        return (dist1 < dist2) ? 1 : 2;
    }
    
    private boolean isPlayerNearDoors(ClientPlayerEntity player) {
        if (player == null) return false;
        
        Vec3d playerPos = player.getPos();
        Vec3d doorAreaCenter = new Vec3d(61.0, 23.0, 83.0);
        
        return playerPos.distanceTo(doorAreaCenter) <= RENDER_DISTANCE;
    }
    
    // Called when player joins a world - triggers immediate server fetch

    public void onWorldJoin() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.doorSpiritEnabled) return;
        
        needsWorldJoinFetch = true;
        
        if (config.debugDoorSpirit) {
            WBUtilsClient.LOGGER.info("[DoorSpirit] World join detected, will fetch server state");
        }
    }

    public void onClientTick() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.doorSpiritEnabled) return;
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        
        if (needsWorldJoinFetch) {
            needsWorldJoinFetch = false;
            lastServerFetchTime = System.currentTimeMillis();
            fetchDoorStateFromServer();
            if (config.debugDoorSpirit) {
                MinecraftClient.getInstance().player.sendMessage(
                    Text.literal("§7[DoorSpirit] Fetching shared door state from server..."), false
                );
            }
            return;
        }
        
        // Also fetch periodically when near doors (to get updates from other players)
        long now = System.currentTimeMillis();
        if (now - lastServerFetchTime > SERVER_FETCH_COOLDOWN_MS) {
            if (isPlayerNearDoors(client.player)) {
                lastServerFetchTime = now;
                fetchDoorStateFromServer();
            }
        }
    }
    
    // ts wont work on 1.21.10
    private void onWorldRender(WorldRenderContext context) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.doorSpiritEnabled) return;
        
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        
        if (player == null || !isPlayerNearDoors(player)) return;
        
        Vec3d cameraPos = context.camera().getPos();
        MatrixStack matrices = context.matrixStack();
        
        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        
        float[] door1Color;
        float[] door2Color;
        
        switch (currentState) {
            case DOOR_1_CORRECT:
                door1Color = COLOR_CORRECT;
                door2Color = COLOR_INCORRECT;
                break;
            case DOOR_2_CORRECT:
                door1Color = COLOR_INCORRECT;
                door2Color = COLOR_CORRECT;
                break;
            default:
                door1Color = COLOR_UNKNOWN;
                door2Color = COLOR_UNKNOWN;
                break;
        }
        
        renderDoorHighlight(matrices, context, DOOR_1_MIN, DOOR_1_MAX, door1Color);
        
        renderDoorHighlight(matrices, context, DOOR_2_MIN, DOOR_2_MAX, door2Color);
        
        matrices.pop();
    }
    

    private void renderDoorHighlight(MatrixStack matrices, WorldRenderContext context, 
                                     BlockPos min, BlockPos max, float[] color) {
        float minX = min.getX();
        float minY = min.getY();
        float minZ = min.getZ();
        float maxX = max.getX() + 1;
        float maxY = max.getY() + 1;
        float maxZ = max.getZ() + 1;
        
        var entry = matrices.peek();
        var posMatrix = entry.getPositionMatrix();
        
        float r = color[0];
        float g = color[1];
        float b = color[2];
        float a = color[3];
        
        Tessellator tessellator = Tessellator.getInstance();
        
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
        
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        
        buffer.vertex(posMatrix, minX, minY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, minY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, minY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, minY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, minY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, minY, minZ).color(r, g, b, a);
        
        buffer.vertex(posMatrix, minX, maxY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, maxY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, maxY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, maxY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, maxY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, maxY, maxZ).color(r, g, b, a);
        
        buffer.vertex(posMatrix, minX, minY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, minY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, maxY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, minY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, maxY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, maxY, minZ).color(r, g, b, a);
        
        buffer.vertex(posMatrix, minX, minY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, maxY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, minY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, minY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, maxY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, maxY, maxZ).color(r, g, b, a);
        
        buffer.vertex(posMatrix, minX, minY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, maxY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, maxY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, minY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, maxY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, minY, maxZ).color(r, g, b, a);
        
        buffer.vertex(posMatrix, maxX, minY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, maxY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, maxY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, minY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, minY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, maxY, maxZ).color(r, g, b, a);
        
        BufferRenderer.drawWithGlobalProgram(buffer.end());
        
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
    
    // ==================== Server Communication ====================

    private void sendDoorReport(int doorUsed, boolean wasCorrect, int correctDoorNumber) {
        CompletableFuture.runAsync(() -> {
            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            
            if (player == null) return;
            
            String urlStr = config.authServerUrl + "/door/report";
            String json = String.format(
                "{\"minecraft_uuid\":\"%s\",\"minecraft_name\":\"%s\",\"door_used\":%d,\"was_correct\":%s,\"correct_door\":%d,\"timestamp\":%d}",
                escapeJson(player.getUuid().toString()),
                escapeJson(player.getGameProfile().getName()),
                doorUsed,
                wasCorrect ? "true" : "false",
                correctDoorNumber,
                System.currentTimeMillis()
            );

            NetworkManager.post(urlStr, json, config.authToken)
                .thenAccept(response -> {
                    if (config.debugDoorSpirit) {
                        WBUtilsClient.LOGGER.info("[DoorSpirit] Door report sent, response: {}", response.statusCode());
                    }
                })
                .exceptionally(e -> {
                    if (config.debugDoorSpirit) {
                        WBUtilsClient.LOGGER.error("[DoorSpirit] Failed to send door report", e);
                    }
                    return null;
                });
        });
    }
    
    // Send door cycle notification to server
     
    private void sendDoorCycleReport() {
        CompletableFuture.runAsync(() -> {
            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
            MinecraftClient client = MinecraftClient.getInstance();
            ClientPlayerEntity player = client.player;
            
            if (player == null) return;
            
            String urlStr = config.authServerUrl + "/door/cycle";
            String json = String.format(
                "{\"minecraft_uuid\":\"%s\",\"minecraft_name\":\"%s\",\"timestamp\":%d}",
                escapeJson(player.getUuid().toString()),
                escapeJson(player.getGameProfile().getName()),
                System.currentTimeMillis()
            );

            NetworkManager.post(urlStr, json, config.authToken)
                .thenAccept(response -> {
                    if (config.debugDoorSpirit) {
                        WBUtilsClient.LOGGER.info("[DoorSpirit] Door cycle report sent, response: {}", response.statusCode());
                    }
                })
                .exceptionally(e -> {
                    if (config.debugDoorSpirit) {
                        WBUtilsClient.LOGGER.error("[DoorSpirit] Failed to send door cycle report", e);
                    }
                    return null;
                });
        });
    }
    
    // Fetch current door state from server
     
    private void fetchDoorStateFromServer() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        String urlStr = config.authServerUrl + "/door/current";

        NetworkManager.get(urlStr, false, config.authToken).thenAccept(response -> {
            if (response.statusCode() == 200) {
                String responseStr = response.body();
                Pattern doorPattern = Pattern.compile("\"correct_door\"\\s*:\\s*(\\d+)");
                Matcher matcher = doorPattern.matcher(responseStr);
                if (matcher.find()) {
                    int serverCorrectDoor = Integer.parseInt(matcher.group(1));

                    // Only update if no local knowledge or server is more recent
                    if (correctDoor == 0 && serverCorrectDoor > 0) {
                        MinecraftClient.getInstance().execute(() -> {
                            updateDoorState(serverCorrectDoor);
                            if (config.debugDoorSpirit) {
                                WBUtilsClient.LOGGER.info("[DoorSpirit] Got door state from server: door {}", serverCorrectDoor);
                            }
                        });
                    }
                }
            }
        }).exceptionally(e -> {
            if (config.debugDoorSpirit) {
                WBUtilsClient.LOGGER.error("[DoorSpirit] Failed to fetch door state", e);
            }
            return null;
        });
    }
    
    // ==================== Local History Storage ====================

    private void addToHistory(int doorNumber, long timestamp) {
        DoorHistoryEntry entry = new DoorHistoryEntry(doorNumber, timestamp);
        localHistory.add(entry);
        
        while (localHistory.size() > 2) {
            localHistory.remove(0);
        }
    }
    
    
    private static class DoorHistoryEntry {
        final int door;
        final long timestamp;
        
        DoorHistoryEntry(int door, long timestamp) {
            this.door = door;
            this.timestamp = timestamp;
        }
    }
    
    
    public DoorState getCurrentState() {
        return currentState;
    }
    
    public int getCorrectDoor() {
        return correctDoor;
    }
    
    public List<DoorHistoryEntry> getLocalHistory() {
        return Collections.unmodifiableList(localHistory);
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
}
