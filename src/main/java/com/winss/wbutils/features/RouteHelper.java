package com.winss.wbutils.features;

import com.winss.wbutils.WBUtilsClient;
import net.minecraft.client.MinecraftClient;

public class RouteHelper {
    private boolean stargazerEnabled = false;

    public void setStargazer(boolean enabled) {
        this.stargazerEnabled = enabled;
        if (!enabled && MinecraftClient.getInstance().player != null) {
            CameraReset.execute(0.0f, 0.0f);
        }
    }

    public boolean isStargazerEnabled() {
        return stargazerEnabled;
    }

    public void tick() {
        if (stargazerEnabled) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                if (!WBUtilsClient.getHousingDetector().isInDptb2Housing()) {
                    stargazerEnabled = false;
                    client.player.sendMessage(net.minecraft.text.Text.literal("§9[WBUtils] §cStargazer disabled (left housing)."), false);
                    CameraReset.execute(0.0f, 0.0f);
                    return;
                }
                client.player.setYaw(0.0f);
                client.player.setPitch(0.0f);
            }
        }
    }
}

