package com.winss.wbutils.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.features.AutoBuy;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;

/**
 * /abuy command alias for auto-buying items.
 * Usage: /abuy <item> <quantity>
 * Delegates to AutoBuy.start().
 */
public class AutoBuyCommand {

    private static final SuggestionProvider<FabricClientCommandSource> ITEM_SUGGESTIONS = (context, builder) -> {
        String remaining = builder.getRemaining().toLowerCase();
        for (AutoBuy.BuyableItem item : AutoBuy.BuyableItem.values()) {
            if (item.id.toLowerCase().startsWith(remaining)) {
                builder.suggest(item.id);
            }
        }
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("abuy")
            .then(argument("item", StringArgumentType.string())
                .suggests(ITEM_SUGGESTIONS)
                .then(argument("quantity", IntegerArgumentType.integer(1, 64))
                    .executes(context -> {
                        String itemId = StringArgumentType.getString(context, "item");
                        int quantity = IntegerArgumentType.getInteger(context, "quantity");
                        executeAutoBuy(context.getSource(), itemId, quantity);
                        return 1;
                    })
                )
            )
        );
    }


    public static void executeAutoBuy(FabricClientCommandSource source, String itemId, int quantity) {
        if (!WBUtilsClient.getConfigManager().getConfig().autoBuyEnabled) {
            source.sendFeedback(Text.literal("§9[WBUtils] §cAuto-Buy is currently disabled. Enable it with §b/wbutils auto buy toggle§c."));
            return;
        }

        AutoBuy.BuyableItem item = AutoBuy.BuyableItem.fromId(itemId);
        if (item == null) {
            source.sendFeedback(Text.literal(
                "§9[WBUtils] §cUnknown item: §e" + itemId
                + "§c. Valid items: §7immunity_apple§c, §7remote§c, §7jump_4§c, §7speed_4"));
            return;
        }

        AutoBuy autoBuy = WBUtilsClient.getAutoBuy();
        if (autoBuy != null) {
            autoBuy.start(item, quantity);
        }
    }
}
