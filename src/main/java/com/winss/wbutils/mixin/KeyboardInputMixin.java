package com.winss.wbutils.mixin;

import com.winss.wbutils.WBUtilsClient;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.option.GameOptions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {
    
    @Shadow @Final
    private GameOptions settings;
    
    @Inject(method = "tick", at = @At("HEAD"))
    private void wbutils$onTick(CallbackInfo ci) {
        if (settings.forwardKey.isPressed() || settings.backKey.isPressed() || 
            settings.leftKey.isPressed() || settings.rightKey.isPressed() || 
            settings.jumpKey.isPressed() || settings.sneakKey.isPressed() ||
            settings.attackKey.isPressed() || settings.useKey.isPressed()) {
            if (WBUtilsClient.getKothProtector() != null) {
                WBUtilsClient.getKothProtector().onPlayerInput();
            }
            if (WBUtilsClient.getStatSpy() != null) {
                WBUtilsClient.getStatSpy().onPlayerActivity();
            }
        }
    }
}
