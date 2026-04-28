package com.winss.wbutils.mixin;

import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.config.ModConfig;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityMixin {
    
    @Unique
    private float wbutils$lastHealth = -1f;
    
    @Unique
    private int wbutils$ticksSinceLog = 0;
    
    @Unique
    private float wbutils$stargazerOldPitch = 0f;

    @Inject(method = "sendMovementPackets", at = @At("HEAD"))
    private void wbutils$beforeSendPackets(CallbackInfo ci) {
        if (WBUtilsClient.getRouteHelper() != null && WBUtilsClient.getRouteHelper().isStargazerEnabled()) {
            ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
            wbutils$stargazerOldPitch = player.getPitch();
            player.setPitch(-90.0f);
        }
    }

    @Inject(method = "sendMovementPackets", at = @At("TAIL"))
    private void wbutils$afterSendPackets(CallbackInfo ci) {
        if (WBUtilsClient.getRouteHelper() != null && WBUtilsClient.getRouteHelper().isStargazerEnabled()) {
            ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
            player.setPitch(wbutils$stargazerOldPitch);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void wbutils$onTick(CallbackInfo ci) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        float currentHealth = player.getHealth();
        
        if (wbutils$lastHealth < 0) {
            wbutils$lastHealth = currentHealth;
            return;
        }
        
        if (currentHealth < wbutils$lastHealth) {
            float damage = wbutils$lastHealth - currentHealth;
            
            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
            
            if (config.debugDamage) {
                WBUtilsClient.LOGGER.info("[DamageMixin] Health changed: {} -> {} (damage: {})", 
                    wbutils$lastHealth, currentHealth, damage);
                
                net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.sendMessage(net.minecraft.text.Text.literal(
                        com.winss.wbutils.Messages.format("mixin.damage.debug", 
                            "old", String.format("%.1f", wbutils$lastHealth), 
                            "new", String.format("%.1f", currentHealth), 
                            "damage", String.format("%.1f", damage))
                    ), false);
                }
            }
            
            if (WBUtilsClient.getKothProtector() != null) {
                WBUtilsClient.getKothProtector().onPlayerDamaged(damage, null, "unknown");
            }
            
            if (WBUtilsClient.getAutoRPS() != null) {
                WBUtilsClient.getAutoRPS().onPlayerDamaged(damage);
            }
            
            if (WBUtilsClient.getKillTracker() != null) {
                WBUtilsClient.getKillTracker().onPlayerTookDamage(damage);
            }
        }
        
        wbutils$lastHealth = currentHealth;
    }
}
