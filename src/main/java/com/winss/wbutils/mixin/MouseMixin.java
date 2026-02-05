package com.winss.wbutils.mixin;

import com.winss.wbutils.WBUtilsClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseMixin {
    
    @Inject(method = "onMouseButton", at = @At("HEAD"))
    private void wbutils$onMouseButton(long window, int button, int action, int mods, CallbackInfo ci) {
        if (action == 1) { // 1 = press
            if (WBUtilsClient.getKothProtector() != null) {
                WBUtilsClient.getKothProtector().onPlayerInput();
            }
            if (WBUtilsClient.getStatSpy() != null) {
                WBUtilsClient.getStatSpy().onPlayerActivity();
            }
        }
    }
    
    @Inject(method = "onCursorPos", at = @At("HEAD"))
    private void wbutils$onCursorPos(long window, double x, double y, CallbackInfo ci) {
        if (WBUtilsClient.getKothProtector() != null) {
            WBUtilsClient.getKothProtector().onPlayerInput();
        }
        if (WBUtilsClient.getStatSpy() != null) {
            WBUtilsClient.getStatSpy().onPlayerActivity();
        }
    }
}
