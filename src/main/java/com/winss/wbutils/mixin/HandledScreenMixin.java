package com.winss.wbutils.mixin;

import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.features.AutoRPS;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.jetbrains.annotations.Nullable;

@Mixin(HandledScreen.class)
public class HandledScreenMixin {

    @Shadow @Nullable protected Slot focusedSlot;
    @Inject(method = "drawSlot", at = @At("TAIL"))
    private void wbutils$drawSlotHighlight(DrawContext context, Slot slot, CallbackInfo ci) {
        AutoRPS autoRPS = WBUtilsClient.getAutoRPS();
        if (autoRPS == null || !autoRPS.isRPSScreenActive()) {
            return;
        }

        int highlightSlot = autoRPS.getHighlightSlotIndex();
        if (highlightSlot == -1 || slot.id != highlightSlot) {
            return;
        }

        int x = slot.x;
        int y = slot.y;
        context.fill(x, y, x + 16, y + 16, 0x8000FF00);
    }


    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void wbutils$onMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button != 0) return;

        AutoRPS autoRPS = WBUtilsClient.getAutoRPS();
        if (autoRPS == null || !autoRPS.isRPSScreenActive() || autoRPS.getHighlightSlotIndex() == -1) {
            return;
        }

        if (this.focusedSlot != null && this.focusedSlot.hasStack()) {
            ItemStack stack = this.focusedSlot.getStack();
            boolean isRPSSlot = stack.getItem() == Items.COBBLESTONE
                    || stack.getItem() == Items.STONE
                    || stack.getItem() == Items.PAPER
                    || stack.getItem() == Items.SHEARS;

            if (isRPSSlot) {
                autoRPS.onPlayerManualChoice();
                return;
            } else {
                return;
            }
        }

        autoRPS.onConfirmClick();
        cir.setReturnValue(true);
    }
}
