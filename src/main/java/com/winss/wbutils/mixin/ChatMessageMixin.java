package com.winss.wbutils.mixin;

import com.winss.wbutils.Messages;
import com.winss.wbutils.WBUtilsClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(ClientPlayNetworkHandler.class)
public class ChatMessageMixin {
    
    private static final Set<String> KNOWN_SUBCOMMANDS = Set.of(
        "auth", "setserver", "setwebhook", "koth", "help", "ktrack", "housing", "rps", "debug", "status", "autorps", "autorejoin", "unwrap", "bootlist"
    );
    
    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void wbutils$onSendChatMessage(String message, CallbackInfo ci) {
        if (message.toLowerCase().startsWith("/wbutils")) {
            ci.cancel();
            showUnknownCommandError(message);
        }
    }
    
    @Inject(method = "sendChatCommand", at = @At("HEAD"), cancellable = true)
    private void wbutils$onSendChatCommand(String command, CallbackInfo ci) {
        if (WBUtilsClient.getUnwrap() != null) {
            WBUtilsClient.getUnwrap().onCommandSent(command);
        }
        
        // Intercept /boots command for boot tracking
        if (command.toLowerCase().equals("boots") || command.toLowerCase().startsWith("boots ")) {
            if (WBUtilsClient.getBootlistTracker() != null) {
                WBUtilsClient.getBootlistTracker().onBootsCommand();
            }
        }
        
        if (command.toLowerCase().startsWith("wbutils")) {
            ci.cancel();
            showUnknownCommandError("/" + command);
        }
    }
    
    private void showUnknownCommandError(String fullCommand) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            String[] parts = fullCommand.split("\\s+", 2);
            String subcommand = parts.length > 1 ? parts[1].split("\\s+")[0] : "";
            
            if (subcommand.isEmpty()) {
                client.player.sendMessage(Text.literal(Messages.get("command.fallback.no_args")), false);
            } else if (!KNOWN_SUBCOMMANDS.contains(subcommand.toLowerCase())) {
                client.player.sendMessage(Text.literal(Messages.format("command.unknown", "cmd", subcommand)), false);
                client.player.sendMessage(Text.literal(Messages.get("command.unknown.hint")), false);
            } else {
                client.player.sendMessage(Text.literal(Messages.get("command.fallback.try_again")), false);
            }
        }
    }
}
