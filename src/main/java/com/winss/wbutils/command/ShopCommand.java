package com.winss.wbutils.command;

import com.mojang.brigadier.CommandDispatcher;
import com.winss.wbutils.Messages;
import com.winss.wbutils.features.ShopHelper;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;

/**
 * Shop command quickly opens the shop menu and navigates to the crafting table.
 * Usage: /shop
 */
public class ShopCommand {
    
    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("shop")
            .executes(context -> {
                executeShopHelper(context.getSource());
                return 1;
            })
        );
    }
    
    private static void executeShopHelper(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal(Messages.getColorText() + Messages.get("command.shop.opening")));
        ShopHelper.execute();
    }
}
