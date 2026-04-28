package com.winss.wbutils.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.config.ModConfig;
import com.winss.wbutils.network.NetworkManager;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class TrapAvoider {

    private static final float[] COLOR_HIGHLIGHT = {1.0f, 0.0f, 0.0f, 0.10f};
    private static final double PROXIMITY_DISTANCE = 30.0;
    private static final Pattern VALID_PLAYER_NAME = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");
    private static final Pattern DEATH_BOUNTY_PATTERN = Pattern.compile("You earned [\\d,]+.? from your bounty", Pattern.CASE_INSENSITIVE);
    
    
    private final List<TrapRegion> trappers = new ArrayList<>();
    private final List<TrapRegion> victims = new ArrayList<>();

    
    private final Set<String> alertedTrappers = new HashSet<>();
    private final List<String> currentTrappersInPlace = new ArrayList<>();
    
    private Instant lastFetchTime = null;
    private boolean fetchInProgress = false;

    public TrapAvoider() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(this::onWorldRender);
        WBUtilsClient.LOGGER.info("[TrapAvoider] Initialized");
    }

    public void onClientTick() {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.trapAvoiderEnabled) return;
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        
        
        if (!fetchInProgress && (lastFetchTime == null || ChronoUnit.DAYS.between(lastFetchTime, Instant.now()) >= 1)) {
            fetchRegionsFromServer();
        }
    }

    private void fetchRegionsFromServer() {
        fetchInProgress = true;
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        
        if (config.debugTrapAvoider) {
            WBUtilsClient.LOGGER.info("[TrapAvoider] Fetching trap regions from server...");
        }

        String urlStr = config.authServerUrl + "/trap-avoider/regions";
        
        NetworkManager.get(urlStr, false, config.authToken).thenAccept(response -> {
            if (response.statusCode() == 200) {
                try {
                    String json = response.body();
                    JsonArray array = JsonParser.parseString(json).getAsJsonArray();
                    
                    List<TrapRegion> newTrappers = new ArrayList<>();
                    List<TrapRegion> newVictims = new ArrayList<>();
                    
                    for (JsonElement el : array) {
                        JsonObject obj = el.getAsJsonObject();
                        String role = obj.get("role").getAsString();
                        
                        JsonObject minObj = obj.getAsJsonObject("min");
                        BlockPos min = new BlockPos(minObj.get("x").getAsInt(), minObj.get("y").getAsInt(), minObj.get("z").getAsInt());
                        
                        JsonObject maxObj = obj.getAsJsonObject("max");
                        BlockPos max = new BlockPos(maxObj.get("x").getAsInt(), maxObj.get("y").getAsInt(), maxObj.get("z").getAsInt());
                        
                        
                        int actualMinX = Math.min(min.getX(), max.getX());
                        int actualMinY = Math.min(min.getY(), max.getY());
                        int actualMinZ = Math.min(min.getZ(), max.getZ());
                        int actualMaxX = Math.max(min.getX(), max.getX());
                        int actualMaxY = Math.max(min.getY(), max.getY());
                        int actualMaxZ = Math.max(min.getZ(), max.getZ());
                        
                        BlockPos actualMin = new BlockPos(actualMinX, actualMinY, actualMinZ);
                        BlockPos actualMax = new BlockPos(actualMaxX, actualMaxY, actualMaxZ);
                        
                        TrapRegion region = new TrapRegion(
                            obj.get("id").getAsString(),
                            obj.get("name").getAsString(),
                            obj.get("map").getAsString(),
                            actualMin,
                            actualMax
                        );
                        
                        if ("T".equals(role)) {
                            newTrappers.add(region);
                        } else if ("V".equals(role)) {
                            newVictims.add(region);
                        }
                    }
                    
                    MinecraftClient.getInstance().execute(() -> {
                        trappers.clear();
                        trappers.addAll(newTrappers);
                        victims.clear();
                        victims.addAll(newVictims);
                        lastFetchTime = Instant.now();
                        fetchInProgress = false;
                        
                        if (config.debugTrapAvoider) {
                            WBUtilsClient.LOGGER.info("[TrapAvoider] Loaded {} trappers and {} victims.", trappers.size(), victims.size());
                            if (MinecraftClient.getInstance().player != null) {
                                MinecraftClient.getInstance().player.sendMessage(Text.literal("§9[WBUtils] §aLoaded trap regions from server."), false);
                            }
                        }
                    });
                } catch (Exception e) {
                    handleFetchError(e);
                }
            } else {
                handleFetchError(new RuntimeException("Server returned status code: " + response.statusCode()));
            }
        }).exceptionally(e -> {
            handleFetchError(e);
            return null;
        });
    }
    
    private void handleFetchError(Throwable e) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            fetchInProgress = false;
            
            if (config.debugTrapAvoider) {
                WBUtilsClient.LOGGER.error("[TrapAvoider] Failed to fetch regions", e);
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("§9[WBUtils] §cFailed to fetch trap regions from server."), false);
                }
            }
        });
    }

    public void handleChatMessage(Text message) {
        String plain = message.getString();
        String stripped = plain != null ? plain.replaceAll("§[0-9a-fk-or]", "").trim() : "";
        
        if (DEATH_BOUNTY_PATTERN.matcher(stripped).find()) {
            if (!currentTrappersInPlace.isEmpty()) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    StringBuilder sb = new StringBuilder("§9[WBUtils] §cYou died while in a trap! §7Players in trapper place: ");
                    for (int i = 0; i < currentTrappersInPlace.size(); i++) {
                        sb.append("§b").append(currentTrappersInPlace.get(i));
                        if (i < currentTrappersInPlace.size() - 1) {
                            sb.append("§7, ");
                        }
                    }
                    client.player.sendMessage(Text.literal(sb.toString()), false);
                }
            }
        }
    }

    private void onWorldRender(WorldRenderContext context) {
        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
        if (!config.trapAvoiderEnabled) return;
        if (config.requireHousing && !WBUtilsClient.getHousingDetector().isInDptb2Housing()) return;
        
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;
        
        Vec3d cameraPos = context.camera().getPos();
        MatrixStack matrices = context.matrixStack();
        
        // Find which victim regions we are currently close to
        List<TrapRegion> nearbyVictims = new ArrayList<>();
        for (TrapRegion victim : victims) {
            Vec3d center = new Vec3d(
                (victim.min.getX() + victim.max.getX()) / 2.0,
                (victim.min.getY() + victim.max.getY()) / 2.0,
                (victim.min.getZ() + victim.max.getZ()) / 2.0
            );
            if (player.getPos().distanceTo(center) <= PROXIMITY_DISTANCE) {
                nearbyVictims.add(victim);
            }
        }
        
        if (nearbyVictims.isEmpty()) {
            currentTrappersInPlace.clear();
            return;
        }
        
        List<String> activeTrapperIds = new ArrayList<>();
        List<String> trappersFound = new ArrayList<>();
        
        for (AbstractClientPlayerEntity otherPlayer : client.world.getPlayers()) {
            if (otherPlayer == player) continue;
            
            if (!isRealPlayer(otherPlayer)) continue;
            
            String playerName = otherPlayer.getName().getString();
            
            
            ModUserManager modUserManager = WBUtilsClient.getModUserManager();
            if (modUserManager != null && modUserManager.isModUser(playerName)) {
                continue;
            }
            
            if (config.trapAvoiderWhitelistEnabled) {
                
                if (config.trapAvoiderWhitelist.contains(playerName.toLowerCase())) {
                    continue;
                }

                
                if (config.trapAvoiderWhitelist.contains("elite")) {
                    String displayName = otherPlayer.getDisplayName().getString();
                    String cleanDisplayName = displayName.replaceAll("§[0-9a-fk-or]", "");
                    if (cleanDisplayName.contains("[VI]") || cleanDisplayName.contains("[VII]") || 
                        cleanDisplayName.contains("[VIII]") || cleanDisplayName.contains("[IX]") || 
                        cleanDisplayName.contains("[X]") || cleanDisplayName.contains("[STAFF]") || 
                        cleanDisplayName.contains("[ADMIN]")) {
                        continue;
                    }
                }
            }
            
            Box playerBox = otherPlayer.getBoundingBox();
            
            for (TrapRegion trapper : trappers) {
                if (activeTrapperIds.contains(trapper.id)) continue;
                
                
                
                Box trapperBox = new Box(
                    trapper.min.getX(), trapper.min.getY() - 1.0, trapper.min.getZ(),
                    trapper.max.getX() + 1, trapper.max.getY() + 2.5, trapper.max.getZ() + 1
                );
                
                if (playerBox.intersects(trapperBox)) {
                    activeTrapperIds.add(trapper.id);
                    if (!trappersFound.contains(playerName)) {
                        trappersFound.add(playerName);
                    }
                }
            }
        }
        
        currentTrappersInPlace.clear();
        currentTrappersInPlace.addAll(trappersFound);
        
        if (activeTrapperIds.isEmpty()) {
            alertedTrappers.clear();
            return;
        }

        
        
        Set<String> newlyActive = new HashSet<>(activeTrapperIds);
        newlyActive.removeAll(alertedTrappers);
        alertedTrappers.retainAll(activeTrapperIds);

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        for (TrapRegion victim : nearbyVictims) {
            // Find corresponding trapper (e.g. W1V corresponds to W1T)
            String correspondingTrapperId = victim.id.substring(0, victim.id.length() - 1) + "T";

            if (activeTrapperIds.contains(correspondingTrapperId)) {
                renderHighlight(matrices, victim.min, victim.max, COLOR_HIGHLIGHT);

                if (config.debugTrapAvoider && newlyActive.contains(correspondingTrapperId)) {
                    player.sendMessage(Text.literal("§9[WBUtils] §cTrap detected! Trapper: §b" + correspondingTrapperId + "§7, Victim: §b" + victim.id), false);
                    alertedTrappers.add(correspondingTrapperId);
                    newlyActive.remove(correspondingTrapperId);
                }
            }
        }

        matrices.pop();
    }
    
    private void renderHighlight(MatrixStack matrices, BlockPos min, BlockPos max, float[] color) {
        float minX = min.getX();
        float minY = min.getY();
        float minZ = min.getZ();
        // Add +1 so it surrounds the whole block
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
        
        // Quad 1 (Bottom Y)
        buffer.vertex(posMatrix, minX, minY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, minY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, minY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, minY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, minY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, minY, minZ).color(r, g, b, a);
        
        // Quad 2 (Top Y)
        buffer.vertex(posMatrix, minX, maxY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, maxY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, maxY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, maxY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, maxY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, maxY, maxZ).color(r, g, b, a);
        
        // Quad 3 (Side Z-)
        buffer.vertex(posMatrix, minX, minY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, minY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, maxY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, minY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, maxY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, maxY, minZ).color(r, g, b, a);
        
        // Quad 4 (Side Z+)
        buffer.vertex(posMatrix, minX, minY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, maxY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, minY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, minY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, maxY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, maxX, maxY, maxZ).color(r, g, b, a);
        
        // Quad 5 (Side X-)
        buffer.vertex(posMatrix, minX, minY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, maxY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, maxY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, minY, minZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, maxY, maxZ).color(r, g, b, a);
        buffer.vertex(posMatrix, minX, minY, maxZ).color(r, g, b, a);
        
        // Quad 6 (Side X+)
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
    
    private boolean isRealPlayer(AbstractClientPlayerEntity otherPlayer) {
        if (otherPlayer == null) return false;
        
        String name = otherPlayer.getName().getString();
        if (name == null || name.isEmpty()) return false;
        
        String cleanName = name.replaceAll("§[0-9a-fk-or]", "");
        if (!VALID_PLAYER_NAME.matcher(cleanName).matches()) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            if (client.getNetworkHandler().getPlayerListEntry(otherPlayer.getUuid()) == null) {
                return false;
            }
        }

        if (cleanName.length() >= 8 && cleanName.equals(cleanName.toLowerCase()) && !cleanName.contains("_")) {
            int vowels = 0;
            for (char c : cleanName.toCharArray()) {
                if ("aeiou".indexOf(c) >= 0) vowels++;
            }
            double vowelRatio = (double) vowels / cleanName.length();
            if (vowelRatio < 0.1 || vowelRatio > 0.6) {
                return false; 
            }
        }
        
        return true;
    }
    
    private static class TrapRegion {
        final String id;
        final String name;
        final String map;
        final BlockPos min;
        final BlockPos max;
        
        public TrapRegion(String id, String name, String map, BlockPos min, BlockPos max) {
            this.id = id;
            this.name = name;
            this.map = map;
            this.min = min;
            this.max = max;
        }
    }
}
