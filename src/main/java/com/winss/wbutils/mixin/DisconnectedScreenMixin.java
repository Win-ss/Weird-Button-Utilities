package com.winss.wbutils.mixin;

import com.winss.wbutils.WBUtilsClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin extends Screen {
    
    protected DisconnectedScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void wbutils$onInit(CallbackInfo ci) {
        if (WBUtilsClient.getAutoRejoin() != null) {
            Text reason = this.title;
            
            WBUtilsClient.LOGGER.info("[DisconnectedScreenMixin] Disconnect screen shown, reason: {}", 
                reason != null ? reason.getString() : "null");
            
            boolean willRejoin = WBUtilsClient.getAutoRejoin().onDisconnected(reason);
            
            if (willRejoin) {
                WBUtilsClient.LOGGER.info("[DisconnectedScreenMixin] AutoRejoin triggered");
            }
        }
    }
}
