package com.winss.wbutils.command;

import com.mojang.brigadier.CommandDispatcher;
import com.winss.wbutils.Messages;
import com.winss.wbutils.features.QuestHelper;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;

/**
 * Quest command quickly opens the quests menu.
 * Aliases: /wquests, /wq, /quests
 */
public class QuestCommand {
    
    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        //wquests
        dispatcher.register(literal("wquests")
            .executes(context -> {
                executeQuestHelper(context.getSource());
                return 1;
            })
        );
        
        dispatcher.register(literal("wq")
            .executes(context -> {
                executeQuestHelper(context.getSource());
                return 1;
            })
        );
        
        dispatcher.register(literal("quests")
            .executes(context -> {
                executeQuestHelper(context.getSource());
                return 1;
            })
        );
    }
    
    private static void executeQuestHelper(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal(Messages.getColorText() + Messages.get("command.quest.opening")));
        QuestHelper.execute();
    }
}
