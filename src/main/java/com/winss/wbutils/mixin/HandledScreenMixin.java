package com.winss.wbutils.mixin;

import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.config.ModConfig;
import com.winss.wbutils.features.AutoBuy;
import com.winss.wbutils.features.AutoRPS;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
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
        if (button != 0 && button != 1) return;

        // AutoBuy safety: cancel operation if player clicks during auto-buy
        AutoBuy autoBuy = WBUtilsClient.getAutoBuy();
        if (autoBuy != null && autoBuy.isActive()) {
            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
            if (config.autoBuySafetyEnabled) {
                autoBuy.onPlayerInterference();
                cir.setReturnValue(true); // consume the click
                return;
            }
        }

        AutoRPS autoRPS = WBUtilsClient.getAutoRPS();
        if (autoRPS == null || !autoRPS.isRPSScreenActive() || autoRPS.getHighlightSlotIndex() == -1) {
            return;
        }

        if (this.focusedSlot != null && this.focusedSlot.hasStack()) {
            ItemStack stack = this.focusedSlot.getStack();
            Item item = stack.getItem();
            boolean isRPSSlot = item == Items.COBBLESTONE
                    || item == Items.STONE
                    || item == Items.PAPER
                    || item == Items.SHEARS;

            if (isRPSSlot) {
                autoRPS.onPlayerManualChoice();
                return;
            } else if (item == Items.WHITE_STAINED_GLASS_PANE) {
                autoRPS.onConfirmClick();
                cir.setReturnValue(true);
                return;
            } else {
                return;
            }
        }

        autoRPS.onConfirmClick();
        cir.setReturnValue(true);
    }

    /**
     * F8 keybind: copy full debug info of the hovered item to clipboard.
     * Works in any container screen — useful for diagnosing item name matching issues.
     */
    @Inject(method = "keyPressed", at = @At("HEAD"))
    private void wbutils$onKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        KeyBinding key = WBUtilsClient.getCopyItemInfoKey();
        if (key == null || !key.matchesKey(keyCode, scanCode)) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        if (this.focusedSlot == null || !this.focusedSlot.hasStack()) {
            client.player.sendMessage(Text.literal("§9[WBUtils] §cNo item under cursor."), false);
            return;
        }

        Slot slot = this.focusedSlot;
        ItemStack stack = slot.getStack();

        String itemType = Registries.ITEM.getId(stack.getItem()).toString();
        String plainName = stack.getName().getString();
        String rawNameVisible = plainName.replace('§', '&');
        String textRepr = stack.getName().toString().replace('§', '&');
        int slotIndex = slot.id;
        int count = stack.getCount();

        // Build clipboard text
        StringBuilder clipText = new StringBuilder();
        clipText.append("=== WBUtils Item Debug ===\n");
        clipText.append("Slot: ").append(slotIndex).append("\n");
        clipText.append("Type: ").append(itemType).append("\n");
        clipText.append("Count: ").append(count).append("\n");
        clipText.append("Name (getString): ").append(plainName).append("\n");
        clipText.append("Name (visible §): ").append(rawNameVisible).append("\n");
        clipText.append("Name (toString): ").append(stack.getName().toString()).append("\n");
        clipText.append("Text repr: ").append(textRepr).append("\n");

        // Lore/tooltip omitted — use F3+H in-game for full tooltip
        client.keyboard.setClipboard(clipText.toString());

        // Chat feedback
        client.player.sendMessage(Text.literal("§9[WBUtils] §aItem info copied to clipboard!"), false);
        client.player.sendMessage(Text.literal("§9[WBUtils] §7Slot: §b" + slotIndex + " §7| Type: §e" + itemType + " §7| Count: §b" + count), false);
        client.player.sendMessage(Text.literal("§9[WBUtils] §7Name (plain): §f" + plainName), false);
        client.player.sendMessage(Text.literal("§9[WBUtils] §7Name (raw): §f" + rawNameVisible), false);
    }
}

