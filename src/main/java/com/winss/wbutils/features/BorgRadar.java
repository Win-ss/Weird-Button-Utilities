package com.winss.wbutils.features;

import com.winss.wbutils.WBUtilsClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class BorgRadar {
    private final Set<UUID> alertedPlayers = new HashSet<>();
    private long lastClearTime = 0;


    private static final double MIN_X = -30;
    private static final double MAX_X = -5;
    private static final double MIN_Y = 40;
    private static final double MAX_Y = 67;
    private static final double MIN_Z = 109;
    private static final double MAX_Z = 136;
    

    private static final Box EXCLUSION_ZONE = new Box(MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z);

    public void onClientTick() {
        if (!WBUtilsClient.getConfigManager().getConfig().borgRadarEnabled) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastClearTime > 5000) {
            alertedPlayers.clear();
            lastClearTime = currentTime;
        }


        if (EXCLUSION_ZONE.contains(client.player.getPos())) {
            return;
        }

        for (PlayerEntity player : client.world.getPlayers()) {
            if (player == client.player) continue;

            if (player.isInvisible()) {
                if (EXCLUSION_ZONE.contains(player.getPos())) {
                    continue;
                }

                double dist = client.player.distanceTo(player);
                if (dist <= 25.0) {
                    if (!alertedPlayers.contains(player.getUuid())) {
                        alertedPlayers.add(player.getUuid());
                        client.player.sendMessage(Text.literal("§9[WBUtils] §b" + player.getName().getString() + " §7is near you (§e" + String.format("%.1f", dist) + "m§7) while invis!"), false);
                        
                        if (WBUtilsClient.getConfigManager().getConfig().debugBorgRadar) {
                            WBUtilsClient.LOGGER.info("BorgRadar alerted for " + player.getName().getString() + " at distance " + dist);
                        }
                    }
                }
            }
        }
    }
}
