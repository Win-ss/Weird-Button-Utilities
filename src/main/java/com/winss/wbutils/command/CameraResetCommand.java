package com.winss.wbutils.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.winss.wbutils.Messages;
import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.features.CameraReset;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;

/**
 * Camera reset command - moves player yaw/pitch to target.
 * Command: /wcr [yaw] [pitch]
 */
public class CameraResetCommand {
    
    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("wcr")
            .executes(context -> {
                executeCameraReset(context.getSource(), 0.0f, 0.0f);
                return 1;
            })
            // /wcr <yaw> <pitch> args
            .then(argument("yaw", FloatArgumentType.floatArg(-180, 180))
                .then(argument("pitch", FloatArgumentType.floatArg(-90, 90))
                    .executes(context -> {
                        float yaw = FloatArgumentType.getFloat(context, "yaw");
                        float pitch = FloatArgumentType.getFloat(context, "pitch");
                        executeCameraReset(context.getSource(), yaw, pitch);
                        return 1;
                    })
                )
            )
        );
    }
    
    private static void executeCameraReset(FabricClientCommandSource source, float yaw, float pitch) {
        if (CameraReset.isResetting()) {
            CameraReset.cancel();
            source.sendFeedback(Text.literal(Messages.get("command.camera.cancelled")));
            WBUtilsClient.LOGGER.debug("[CameraReset] User cancelled camera reset");
        } else {
            source.sendFeedback(Text.literal(Messages.format("command.camera.resetting_to", "yaw", String.format("%.1f", yaw), "pitch", String.format("%.1f", pitch))));
            WBUtilsClient.LOGGER.debug("[CameraReset] User initiated camera reset to yaw: {}, pitch: {}", yaw, pitch);
            CameraReset.execute(yaw, pitch);
        }
    }
}