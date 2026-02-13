package com.winss.wbutils.mixin;

import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.Messages;
import com.winss.wbutils.config.ModConfig;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.regex.Pattern;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
    
    private static final Pattern PLAYER_CHAT_PATTERN = Pattern.compile(
        "^(?:\\[[^\\]]+\\]\\s*)*[A-Za-z0-9_]{1,16}:\\s"
    );

    private boolean isPlayerChatMessage(String stripped) {
        if (stripped == null) return false;
        
        if (stripped.startsWith("* You earned") || stripped.startsWith("*You earned")) {
            return false;
        }
        
        return PLAYER_CHAT_PATTERN.matcher(stripped).find();
    }
    

    @Inject(method = "onGameJoin", at = @At("RETURN"))
    private void wbutils$onGameJoin(GameJoinS2CPacket packet, CallbackInfo ci) {
        WBUtilsClient.LOGGER.info("[WBUtils] Player joined world, triggering world join handlers");
        
        if (WBUtilsClient.getAutoRejoin() != null && WBUtilsClient.getHousingDetector() != null) {
            if (WBUtilsClient.getHousingDetector().isInDptb2Housing()) {
                WBUtilsClient.getAutoRejoin().captureHousingState(true);
                WBUtilsClient.LOGGER.info("[WBUtils] Captured housing state for AutoRejoin: was in housing");
            }
        }
        
        if (WBUtilsClient.getDoorSpirit() != null) {
            WBUtilsClient.getDoorSpirit().onWorldJoin();
        }
        
        if (WBUtilsClient.getHousingDetector() != null) {
            WBUtilsClient.getHousingDetector().onWorldJoin();
        }
    }
    
    @Inject(method = "onGameMessage", at = @At("HEAD"))
    private void wbutils$onGameMessage(GameMessageS2CPacket packet, CallbackInfo ci) {
        Text message = packet.content();
        if (message != null) {
            String plain = message.getString();
            
            String stripped = plain != null ? plain.replaceAll("§[0-9a-fk-or]", "").trim() : "";
            
            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
            if (WBUtilsClient.getAutoRejoin() != null && config.autoRejoinEnabled) {
                WBUtilsClient.getAutoRejoin().handleChatMessage(message);
            }
            
            if (config.debugBounty && plain != null && plain.toLowerCase().contains("your bounty")) {
                boolean isPlayerChat = isPlayerChatMessage(stripped);
                WBUtilsClient.LOGGER.info("[ChatMixin] BOUNTY KEYWORD FOUND!");
                WBUtilsClient.LOGGER.info("[ChatMixin] Raw: {}", plain);
                WBUtilsClient.LOGGER.info("[ChatMixin] Is player chat (ignored): {}", isPlayerChat);
                
                net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.sendMessage(Text.literal(Messages.format("mixin.bounty.debug.msg", "msg", plain)), false);
                    client.player.sendMessage(Text.literal(Messages.format("mixin.bounty.debug.chat", "status", String.valueOf(isPlayerChat))), false);
                }
            }
            
            if (WBUtilsClient.getHousingDetector() != null) {
                WBUtilsClient.getHousingDetector().handleChatMessage(message);
            }
            
            boolean featuresActive = !config.requireHousing || 
                (WBUtilsClient.getHousingDetector() != null && WBUtilsClient.getHousingDetector().isInDptb2Housing());
            
            if (featuresActive) {
                if (WBUtilsClient.getKothProtector() != null) {
                    if (!isPlayerChatMessage(stripped)) {
                        WBUtilsClient.getKothProtector().handleChatMessage(message);
                    } else if (config.debugBounty && stripped.toLowerCase().contains("bounty")) {
                        WBUtilsClient.LOGGER.info("[ChatMixin] Ignoring player chat with bounty keyword");
                    }
                }
                
                if (WBUtilsClient.getDoorSpirit() != null && config.doorSpiritEnabled) {
                    if (!isPlayerChatMessage(stripped)) {
                        WBUtilsClient.getDoorSpirit().handleChatMessage(message);
                    }
                }
                
                if (WBUtilsClient.getRPSTracker() != null && config.rpsTrackerEnabled) {
                    if (!isPlayerChatMessage(stripped)) {
                        WBUtilsClient.getRPSTracker().handleChatMessage(message);
                    }
                }
                
                if (WBUtilsClient.getUnwrap() != null && config.unwrapEnabled) {
                    if (!isPlayerChatMessage(stripped)) {
                        WBUtilsClient.getUnwrap().handleChatMessage(message);
                    }
                }
                
                if (WBUtilsClient.getMayhemBlast() != null && config.mayhemBlastEnabled) {
                    if (!isPlayerChatMessage(stripped)) {
                        WBUtilsClient.getMayhemBlast().handleChatMessage(message);
                    }
                }
            }
        }
    }
}
