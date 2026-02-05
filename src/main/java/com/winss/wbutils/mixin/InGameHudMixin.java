package com.winss.wbutils.mixin;

import com.winss.wbutils.WBUtilsClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "setTitle", at = @At("HEAD"))
    private void wbutils$handleTitle(Text title, CallbackInfo ci) {
        if (WBUtilsClient.getKothProtector() != null) {
            WBUtilsClient.getKothProtector().handleTitle(title);
        }
        if (WBUtilsClient.getStatSpy() != null) {
            WBUtilsClient.getStatSpy().handleTitle(title);
        }
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"))
    private void wbutils$handleSubtitle(Text subtitle, CallbackInfo ci) {
        if (WBUtilsClient.getKothProtector() != null) {
            WBUtilsClient.getKothProtector().handleTitle(subtitle);
        }
        if (WBUtilsClient.getStatSpy() != null) {
            WBUtilsClient.getStatSpy().handleSubtitle(subtitle);
        }
    }

}

