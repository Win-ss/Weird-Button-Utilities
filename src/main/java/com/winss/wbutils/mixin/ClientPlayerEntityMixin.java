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
                        "§d[DEBUG:Damage] " + String.format("%.1f", wbutils$lastHealth) + " -> " + 
                        String.format("%.1f", currentHealth) + " (took " + String.format("%.1f", damage) + " damage)"
                    ), false);
                }
            }
            
            if (WBUtilsClient.getKothProtector() != null) {
                WBUtilsClient.getKothProtector().onPlayerDamaged(damage, null, "unknown");
            }
            
            if (WBUtilsClient.getKillTracker() != null) {
                WBUtilsClient.getKillTracker().onPlayerTookDamage(damage);
            }
        }
        
        wbutils$lastHealth = currentHealth;
    }
}
