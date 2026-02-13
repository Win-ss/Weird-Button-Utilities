package com.winss.wbutils.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.winss.wbutils.WBUtilsClient;
import com.winss.wbutils.Messages;
import com.winss.wbutils.config.ModConfig;
import com.winss.wbutils.features.AuthService;
import com.winss.wbutils.features.AutoRejoin;
import com.winss.wbutils.features.KillTracker;
import com.winss.wbutils.features.ModUserManager;
import com.winss.wbutils.features.RPSTracker;
import com.winss.wbutils.features.AutoRPS;
import com.winss.wbutils.features.Unwrap;
import com.winss.wbutils.features.StatSpy;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.client.MinecraftClient;

import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;

public class WBUtilsCommand {
    
    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("wbutils")
            // Auth command - handles account linking/unlinking
            .then(literal("auth")
                .then(literal("link")
                    .executes(context -> {
                        String token = AuthService.generateAuthToken();
                        context.getSource().sendFeedback(Text.literal(Messages.withAccent("command.auth.generating")));
                        
                        AuthService.registerAuthToken(token, success -> {
                            if (success) {
                                Text tokenText = Text.literal(token)
                                    .setStyle(Style.EMPTY
                                        .withColor(0x55FF55)
                                        .withBold(true)
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, token))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(Messages.get("command.auth.click_to_copy"))))
                                    );
                                
                                context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.auth.header")));
                                context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.get("command.auth.token_label") + " ").append(tokenText));
                                context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.get("command.auth.instructions")));
                                context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.get("command.auth.expiry")));
                            } else {
                                context.getSource().sendFeedback(Text.literal("§c" + Messages.get("command.auth.failed")));
                            }
                        });
                        return 1;
                    })
                )
                .then(literal("unlink")
                    .executes(context -> {
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.auth.unlinking")));
                        AuthService.unlinkAuth(success -> {
                            if (success) {
                                context.getSource().sendFeedback(Text.literal(Messages.get("command.auth.unlink_success")));
                            } else {
                                context.getSource().sendFeedback(Text.literal(Messages.get("command.auth.unlink_failed")));
                            }
                        });
                        return 1;
                    })
                )
                .then(literal("status")
                    .executes(context -> {
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.get("command.auth.checking")));
                        
                        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                        boolean hasLocalToken = config.authToken != null && !config.authToken.isBlank();
                        
                        AuthService.checkLinkStatusSafe(result -> {
                            if (result == AuthService.LinkCheckResult.LINKED) {
                                if (hasLocalToken) {
                                    context.getSource().sendFeedback(Text.literal(Messages.withAccent("command.auth.status_linked")));
                                } else {
                                    context.getSource().sendFeedback(Text.literal("§e" + Messages.get("command.auth.status_linked_no_token")));
                                    context.getSource().sendFeedback(Text.literal("§7" + Messages.get("command.auth.status_reauth_hint")));
                                }
                            } else if (result == AuthService.LinkCheckResult.NOT_LINKED) {
                                context.getSource().sendFeedback(Text.literal("§c" + Messages.get("command.auth.status_not_linked")));
                            } else {
                                context.getSource().sendFeedback(Text.literal(Messages.get("command.auth.status_offline")));
                            }
                        });
                        return 1;
                    })
                )
                .then(literal("test")
                    .executes(context -> {
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.get("command.auth.sending_test")));
                        
                        AuthService.sendTestAlert(result -> {
                            switch (result) {
                                case SUCCESS:
                                    context.getSource().sendFeedback(Text.literal(Messages.withAccent("command.auth.test_success")));
                                    break;
                                case NOT_LINKED:
                                    context.getSource().sendFeedback(Text.literal("§c" + Messages.get("command.auth.test_not_linked")));
                                    break;
                                case DM_DISABLED:
                                    context.getSource().sendFeedback(Text.literal("§c" + Messages.get("command.auth.test_dm_disabled")));
                                    break;
                                case ERROR:
                                    context.getSource().sendFeedback(Text.literal("§c" + Messages.get("command.auth.test_error")));
                                    break;
                            }
                        });
                        return 1;
                    })
                )
                .executes(context -> {
                    // Default auth help
                    context.getSource().sendFeedback(Text.literal(Messages.get("command.auth.help.header")));
                    context.getSource().sendFeedback(Text.literal(Messages.get("command.auth.help.link")));
                    context.getSource().sendFeedback(Text.literal(Messages.get("command.auth.help.unlink")));
                    context.getSource().sendFeedback(Text.literal(Messages.get("command.auth.help.status")));
                    context.getSource().sendFeedback(Text.literal(Messages.get("command.auth.help.test")));
                    return 1;
                })
            )

            .then(literal("koth")
                .then(literal("toggle")
                    .executes(context -> {
                        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                        config.kothProtectorEnabled = !config.kothProtectorEnabled;
                        WBUtilsClient.getConfigManager().save();

                        String state = config.kothProtectorEnabled ? Messages.get("status.enabled") : Messages.get("status.disabled");
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.koth_toggle", "state", state)));
                        return 1;
                    })
                )
                .then(literal("setuserid")
                    .then(argument("userid", StringArgumentType.greedyString())
                        .executes(context -> {
                            String userId = StringArgumentType.getString(context, "userid");
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.discordUserId = userId.trim();
                            WBUtilsClient.getConfigManager().save();
                            
                            context.getSource().sendFeedback(Text.literal(Messages.withAccent("command.discord_userid_set")));
                            return 1;
                        })
                    )
                )
                .then(literal("deathnotify")
                    .then(literal("on")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.kothNotifyOnDeath = true;
                            WBUtilsClient.getConfigManager().save();
                            context.getSource().sendFeedback(Text.literal(Messages.withAccent("command.koth_deathnotify_on")));
                            return 1;
                        })
                    )
                    .then(literal("off")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.kothNotifyOnDeath = false;
                            WBUtilsClient.getConfigManager().save();
                            context.getSource().sendFeedback(Text.literal("§c" + Messages.get("command.koth_deathnotify_off")));
                            return 1;
                        })
                    )
                )
                .then(literal("entrynotify")
                    .then(literal("on")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.kothNotifyOnEntry = true;
                            WBUtilsClient.getConfigManager().save();
                            context.getSource().sendFeedback(Text.literal(Messages.withAccent("command.koth_entrynotify_on")));
                            return 1;
                        })
                    )
                    .then(literal("off")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.kothNotifyOnEntry = false;
                            WBUtilsClient.getConfigManager().save();
                            context.getSource().sendFeedback(Text.literal("§c" + Messages.get("command.koth_entrynotify_off")));
                            return 1;
                        })
                    )
                )
                .then(literal("exitnotify")
                    .then(literal("on")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.kothNotifyOnExit = true;
                            WBUtilsClient.getConfigManager().save();
                            context.getSource().sendFeedback(Text.literal(Messages.withAccent("command.koth_exitnotify_on")));
                            return 1;
                        })
                    )
                    .then(literal("off")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.kothNotifyOnExit = false;
                            WBUtilsClient.getConfigManager().save();
                            context.getSource().sendFeedback(Text.literal("§c" + Messages.get("command.koth_exitnotify_off")));
                            return 1;
                        })
                    )
                )
                .then(literal("status")
                    .executes(context -> {
                        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                        context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.koth_status.header")));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.koth_status.enabled", "value", (config.kothProtectorEnabled ? Messages.get("common.yes") : Messages.get("common.no")))));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.koth_status.deathalerts", "value", (config.kothNotifyOnDeath ? Messages.get("status.on") : Messages.get("status.off")))));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.koth_status.entryalerts", "value", (config.kothNotifyOnEntry ? Messages.get("status.on") : Messages.get("status.off")))));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.koth_status.exitalerts", "value", (config.kothNotifyOnExit ? Messages.get("status.on") : Messages.get("status.off")))));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.koth_status.discorduserid", "value", (config.discordUserId == null || config.discordUserId.isEmpty() ? "§8Not set" : Messages.getColorAccent() + "Set"))));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.koth_status.debuglogs", "value", (config.kothDebugLogs ? Messages.get("status.on") : Messages.get("status.off")))));
                        return 1;
                    })
                )
            )
            // Kill Tracker commands
            .then(literal("ktrack")
                .then(literal("toggle")
                    .executes(context -> {
                        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                        config.ktrackEnabled = !config.ktrackEnabled;
                        WBUtilsClient.getConfigManager().save();
                        String state = config.unwrapEnabled ? Messages.get("status.on") : Messages.get("status.off");
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.ktrack_toggle", "state", state)));
                        return 1;
                    })
                )
                .then(literal("whitelist")
                    .then(literal("add")
                        .then(argument("player", StringArgumentType.string())
                            .executes(context -> {
                                String player = StringArgumentType.getString(context, "player");
                                ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                                if (!config.ktrackWhitelist.contains(player.toLowerCase())) {
                                    config.ktrackWhitelist.add(player.toLowerCase());
                                    WBUtilsClient.getConfigManager().save();
                                    WBUtilsClient.getKillTracker().refreshWhitelistCache();
                                    context.getSource().sendFeedback(Text.literal(Messages.format("command.ktrack_whitelist_added", "player", player)));
                                } else {
                                    context.getSource().sendFeedback(Text.literal(Messages.get("command.ktrack_whitelist_already")));
                                }
                                return 1;
                            })
                        )
                    )
                    .then(literal("remove")
                        .then(argument("player", StringArgumentType.string())
                            .executes(context -> {
                                String player = StringArgumentType.getString(context, "player");
                                ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                                if (config.ktrackWhitelist.remove(player.toLowerCase())) {
                                    WBUtilsClient.getConfigManager().save();
                                    WBUtilsClient.getKillTracker().refreshWhitelistCache();
                                    context.getSource().sendFeedback(Text.literal(Messages.format("command.ktrack_whitelist_removed", "player", player)));
                                } else {
                                    context.getSource().sendFeedback(Text.literal(Messages.get("command.ktrack_whitelist_not_found")));
                                }
                                return 1;
                            })
                        )
                    )
                    .then(literal("list")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            if (config.ktrackWhitelist.isEmpty()) {
                                context.getSource().sendFeedback(Text.literal(Messages.get("command.ktrack_whitelist_empty")));
                            } else {
                                context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.ktrack_whitelist_header")));
                                for (String p : config.ktrackWhitelist) {
                                    context.getSource().sendFeedback(Text.literal(Messages.getColorAccent() + "- " + Messages.getColorText() + p));
                                }
                            }
                            return 1;
                        })
                    )
                )
                .then(literal("list")
                    .executes(context -> {
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.get("command.ktrack_fetching")));
                        WBUtilsClient.getKillTracker().fetchHotList(success -> {
                            if (success) {
                                var hotList = WBUtilsClient.getKillTracker().getHotList();
                                if (hotList.isEmpty()) {
                                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.get("command.ktrack_list_empty")));
                                } else {
                                    context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.ktrack_list_header")));
                                    int count = 0;
                                    for (KillTracker.KillerInfo info : hotList.values()) {
                                        if (count >= 10) break;
                                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + 
                                            Messages.format("command.ktrack_list_entry", 
                                                "player", info.playerName,
                                                "kills", String.valueOf(info.totalKills),
                                                "damages", String.valueOf(info.totalDamageEvents))));
                                        count++;
                                    }
                                }
                            } else {
                                context.getSource().sendFeedback(Text.literal("§c" + Messages.get("command.ktrack_fetch_failed")));
                            }
                        });
                        return 1;
                    })
                )
                .then(literal("top")
                    .executes(context -> {
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.get("command.ktrack_fetching")));
                        WBUtilsClient.getKillTracker().fetchHotList(success -> {
                            if (success) {
                                List<KillTracker.KillerInfo> top = WBUtilsClient.getKillTracker().getTopKillers(5);
                                if (top.isEmpty()) {
                                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.get("command.ktrack_list_empty")));
                                } else {
                                    context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.ktrack_top_header")));
                                    for (int i = 0; i < top.size(); i++) {
                                        KillTracker.KillerInfo info = top.get(i);
                                        context.getSource().sendFeedback(Text.literal(Messages.getColorAccent() + (i+1) + ". " + Messages.getColorText() + 
                                            Messages.format("command.ktrack_list_entry", 
                                                "player", info.playerName,
                                                "kills", String.valueOf(info.totalKills),
                                                "damages", String.valueOf(info.totalDamageEvents))));
                                    }
                                }
                            } else {
                                context.getSource().sendFeedback(Text.literal("§c" + Messages.get("command.ktrack_fetch_failed")));
                            }
                        });
                        return 1;
                    })
                )
                .then(literal("setheat")
                    .then(argument("hours", IntegerArgumentType.integer(1, 24))
                        .executes(context -> {
                            int hours = IntegerArgumentType.getInteger(context, "hours");
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.ktrackTimeWindowHours = hours;
                            WBUtilsClient.getConfigManager().save();
                            context.getSource().sendFeedback(Text.literal(Messages.withAccent("command.ktrack_heat_set") + " " + hours + " " + Messages.get("command.ktrack_hours")));
                            return 1;
                        })
                    )
                )
                .then(literal("setcooldown")
                    .then(argument("minutes", IntegerArgumentType.integer(1, 60))
                        .executes(context -> {
                            int minutes = IntegerArgumentType.getInteger(context, "minutes");
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.ktrackAlertCooldownMinutes = minutes;
                            WBUtilsClient.getConfigManager().save();
                            context.getSource().sendFeedback(Text.literal(Messages.withAccent("command.ktrack_cooldown_set") + " " + minutes + " " + Messages.get("command.ktrack_minutes")));
                            return 1;
                        })
                    )
                )
                .then(literal("status")
                    .executes(context -> {
                        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                        context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.ktrack_status_header")));
                        context.getSource().sendFeedback(Text.literal(Messages.format("command.ktrack_status_enabled", "value", (config.ktrackEnabled ? Messages.get("common.yes") : Messages.get("common.no")))));
                        context.getSource().sendFeedback(Text.literal(Messages.format("command.ktrack_status_heat", "value", Messages.getColorAccent() + config.ktrackTimeWindowHours + "h")));
                        context.getSource().sendFeedback(Text.literal(Messages.format("command.ktrack_status_cooldown", "value", Messages.getColorAccent() + config.ktrackAlertCooldownMinutes + " min")));
                        context.getSource().sendFeedback(Text.literal(Messages.format("command.ktrack_status_debug", "value", (config.ktrackDebugLogs ? Messages.get("status.on") : Messages.get("status.off")))));
                        
                        // Show hot list count
                        var hotList = WBUtilsClient.getKillTracker().getHotList();
                        context.getSource().sendFeedback(Text.literal(Messages.format("command.ktrack_status_tracked", "value", Messages.getColorAccent() + String.valueOf(hotList.size()))));
                        return 1;
                    })
                )
            )

            // RPS commands
            .then(literal("rps")
                .then(literal("stats")
                    .executes(context -> {
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.get("command.rps.fetching")));
                        
                        WBUtilsClient.getRPSTracker().fetchStats(stats -> {
                            if (stats == null) {
                                context.getSource().sendFeedback(Text.literal("§c" + Messages.get("command.rps.fetch_failed")));
                                return;
                            }
                            
                            context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.rps.stats.header")));
                            
                            // Player stats
                            context.getSource().sendFeedback(Text.literal(Messages.getColorAccent() + "--- " + Messages.get("command.rps.stats.your_stats") + " ---"));
                            context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.stats.total_games", "value", String.valueOf(stats.totalGames))));
                            context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.stats.wins", "value", "§a" + stats.wins)));
                            context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.stats.losses", "value", "§c" + stats.losses)));
                            context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.stats.ties", "value", "§7" + stats.ties)));
                            if (stats.totalGames > 0) {
                                context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.stats.win_rate", "value", String.format("%.1f%%", stats.getWinRate()))));
                            }
                            
                            // Global stats
                            context.getSource().sendFeedback(Text.literal(Messages.getColorAccent() + "--- " + Messages.get("command.rps.stats.global_stats") + " ---"));
                            context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.stats.global_games", "value", String.valueOf(stats.globalTotalGames))));
                            context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.stats.global_win_rate", "value", String.format("%.1f%%", stats.getGlobalWinRate()))));
                            
                            // Analytics status
                            if (stats.analyticsAvailable) {
                                context.getSource().sendFeedback(Text.literal(Messages.getColorAccent() + "--- " + Messages.get("command.rps.stats.analytics") + " ---"));
                                context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.stats.rock_win", "value", String.format("%.1f%%", stats.rockWinRate))));
                                context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.stats.paper_win", "value", String.format("%.1f%%", stats.paperWinRate))));
                                context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.stats.scissors_win", "value", String.format("%.1f%%", stats.scissorsWinRate))));
                                
                                context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.stats.npc_rock", "value", String.format("%.1f%%", stats.npcRockRate))));
                                context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.stats.npc_paper", "value", String.format("%.1f%%", stats.npcPaperRate))));
                                context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.stats.npc_scissors", "value", String.format("%.1f%%", stats.npcScissorsRate))));
                                
                                if (stats.recommendedMove != null) {
                                    context.getSource().sendFeedback(Text.literal(Messages.getColorAccent() + "▶ " + Messages.format("command.rps.stats.recommendation", "move", stats.recommendedMove)));
                                }
                            } else {
                                context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.stats.games_needed", "value", String.valueOf(stats.gamesUntilAnalytics))));
                            }
                        });
                        return 1;
                    })
                )
                .then(literal("toggle")
                    .executes(context -> {
                        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                        config.rpsTrackerEnabled = !config.rpsTrackerEnabled;
                        WBUtilsClient.getConfigManager().save();
                        String state = config.rpsTrackerEnabled ? Messages.get("status.on") : Messages.get("status.off");
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.toggle", "state", state)));
                        return 1;
                    })
                )
                .then(literal("feedback")
                    .then(literal("on")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.rpsShowFeedback = true;
                            WBUtilsClient.getConfigManager().save();
                            context.getSource().sendFeedback(Text.literal(Messages.withAccent("command.rps.feedback_on")));
                            return 1;
                        })
                    )
                    .then(literal("off")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.rpsShowFeedback = false;
                            WBUtilsClient.getConfigManager().save();
                            context.getSource().sendFeedback(Text.literal("§c" + Messages.get("command.rps.feedback_off")));
                            return 1;
                        })
                    )
                )
                .then(literal("status")
                    .executes(context -> {
                        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                        RPSTracker tracker = WBUtilsClient.getRPSTracker();
                        
                        context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.rps.status.header")));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.status.enabled", "value", config.rpsTrackerEnabled ? Messages.get("common.yes") : Messages.get("common.no"))));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.status.feedback", "value", config.rpsShowFeedback ? Messages.get("status.on") : Messages.get("status.off"))));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.status.session_games", "value", Messages.getColorAccent() + String.valueOf(tracker.getSessionGameCount()))));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.rps.status.session_id", "value", Messages.getColorAccent() + tracker.getSessionId())));
                        return 1;
                    })
                )
                .executes(context -> {
                    context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.rps.help.header")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils rps stats " + Messages.getColorAccent() + "- " + Messages.get("command.rps.help.stats")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils rps toggle " + Messages.getColorAccent() + "- " + Messages.get("command.rps.help.toggle")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils rps feedback <on|off> " + Messages.getColorAccent() + "- " + Messages.get("command.rps.help.feedback")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils rps status " + Messages.getColorAccent() + "- " + Messages.get("command.rps.help.status")));
                    return 1;
                })
            )
            .then(literal("autorps")
                .then(literal("toggle")
                    .executes(context -> {
                        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                        config.autoRPSEnabled = !config.autoRPSEnabled;
                        WBUtilsClient.getConfigManager().save();
                        String state = config.autoRPSEnabled ? Messages.get("status.on") : Messages.get("status.off");
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.autorps.toggle", "state", state)));
                        return 1;
                    })
                )
                .then(literal("mode")
                    .then(literal("random")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.autoRPSMode = AutoRPS.Mode.RANDOM;
                            WBUtilsClient.getConfigManager().save();
                            context.getSource().sendFeedback(Text.literal(Messages.withAccent("command.autorps.mode_random")));
                            return 1;
                        })
                    )
                    .then(literal("analytical")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.autoRPSMode = AutoRPS.Mode.ANALYTICAL;
                            WBUtilsClient.getConfigManager().save();
                            context.getSource().sendFeedback(Text.literal(Messages.withAccent("command.autorps.mode_analytical")));
                            
                            WBUtilsClient.getRPSTracker().fetchStats(stats -> {
                                if (stats == null || !stats.analyticsAvailable || stats.totalGames < 100) {
                                    int gamesNeeded = stats != null ? stats.gamesUntilAnalytics : 100;
                                    context.getSource().sendFeedback(Text.literal("§e" + Messages.format("command.autorps.analytics_warning", "games", String.valueOf(gamesNeeded))));
                                }
                            });
                            return 1;
                        })
                    )
                    .then(literal("off")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.autoRPSMode = AutoRPS.Mode.OFF;
                            WBUtilsClient.getConfigManager().save();
                            context.getSource().sendFeedback(Text.literal("§c" + Messages.get("command.autorps.mode_off")));
                            return 1;
                        })
                    )
                    .executes(context -> {
                        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                        config.autoRPSMode = AutoRPS.cycleMode(config.autoRPSMode);
                        WBUtilsClient.getConfigManager().save();
                        String modeStr = AutoRPS.getModeDisplayString(config.autoRPSMode);
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.autorps.mode_set", "mode", modeStr)));
                        return 1;
                    })
                )
                .then(literal("feedback")
                    .then(literal("on")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.autoRPSShowFeedback = true;
                            WBUtilsClient.getConfigManager().save();
                            context.getSource().sendFeedback(Text.literal(Messages.withAccent("command.autorps.feedback_on")));
                            return 1;
                        })
                    )
                    .then(literal("off")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.autoRPSShowFeedback = false;
                            WBUtilsClient.getConfigManager().save();
                            context.getSource().sendFeedback(Text.literal("§c" + Messages.get("command.autorps.feedback_off")));
                            return 1;
                        })
                    )
                )
                .then(literal("status")
                    .executes(context -> {
                        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                        
                        context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.autorps.status.header")));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.autorps.status.enabled", "value", config.autoRPSEnabled ? Messages.get("common.yes") : Messages.get("common.no"))));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.autorps.status.mode", "value", AutoRPS.getModeDisplayString(config.autoRPSMode))));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.autorps.status.feedback", "value", config.autoRPSShowFeedback ? Messages.get("status.on") : Messages.get("status.off"))));
                        
                        WBUtilsClient.getRPSTracker().fetchStats(stats -> {
                            if (stats != null) {
                                if (stats.analyticsAvailable && stats.totalGames >= 100) {
                                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.autorps.status.analytics", "value", Messages.get("status.available"))));
                                    if (stats.recommendedMove != null) {
                                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.autorps.status.recommended", "move", "§a" + stats.recommendedMove)));
                                    }
                                } else {
                                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.autorps.status.analytics", "value", Messages.get("status.need") + stats.gamesUntilAnalytics + Messages.get("status.more_games"))));
                                }
                            }
                        });
                        return 1;
                    })
                )
                .executes(context -> {
                    context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.autorps.help.header")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils autorps toggle " + Messages.getColorAccent() + "- " + Messages.get("command.autorps.help.toggle")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils autorps mode <random|analytical|off> " + Messages.getColorAccent() + "- " + Messages.get("command.autorps.help.mode")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils autorps feedback <on|off> " + Messages.getColorAccent() + "- " + Messages.get("command.autorps.help.feedback")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils autorps status " + Messages.getColorAccent() + "- " + Messages.get("command.autorps.help.status")));
                    return 1;
                })
            )
            .then(literal("autorejoin")
                .then(literal("toggle")
                    .executes(context -> {
                        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                        config.autoRejoinEnabled = !config.autoRejoinEnabled;
                        WBUtilsClient.getConfigManager().save();
                        String state = config.autoRejoinEnabled ? Messages.get("status.on") : Messages.get("status.off");
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.autorejoin.toggle", "state", state)));
                        return 1;
                    })
                )
                .then(literal("status")
                    .executes(context -> {
                        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                        var autoRejoin = WBUtilsClient.getAutoRejoin();
                        
                        context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.autorejoin.status.header")));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.autorejoin.status.enabled", "value", config.autoRejoinEnabled ? Messages.get("common.yes") : Messages.get("common.no"))));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.autorejoin.status.debug", "value", config.debugAutoRejoin ? Messages.get("status.on") : Messages.get("status.off"))));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.autorejoin.status.in_progress", "value", autoRejoin.isRejoinInProgress() ? Messages.get("common.yes") : Messages.get("common.no"))));
                        
                        if (autoRejoin.isRejoinInProgress()) {
                            context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.autorejoin.status.state", "value", Messages.getColorAccent() + autoRejoin.getCurrentState().name())));
                        }
                        
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.autorejoin.status.was_in_housing", "value", autoRejoin.wasPlayerInHousing() ? Messages.get("common.yes") : Messages.get("common.no"))));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.autorejoin.status.indicators", "value", Messages.getColorAccent() + String.valueOf(autoRejoin.getDisconnectIndicatorCount()))));
                        
                        long lastRefresh = autoRejoin.getLastDisconnectMessagesFetch();
                        String refreshStr = lastRefresh == 0 ? "§cNever" : Messages.getColorAccent() + getTimeAgo(lastRefresh);
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.autorejoin.status.last_refresh", "value", refreshStr)));
                        return 1;
                    })
                )
                .then(literal("cancel")
                    .executes(context -> {
                        var autoRejoin = WBUtilsClient.getAutoRejoin();
                        if (autoRejoin.isRejoinInProgress()) {
                            autoRejoin.cancelRejoin("Cancelled by user");
                            context.getSource().sendFeedback(Text.literal(Messages.get("command.autorejoin.cancel")));
                        } else {
                            context.getSource().sendFeedback(Text.literal(Messages.get("command.autorejoin.not_active")));
                        }
                        return 1;
                    })
                )
                .then(literal("refresh")
                    .executes(context -> {
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.autorejoin.refresh")));
                        WBUtilsClient.getAutoRejoin().forceRefreshDisconnectMessages(success -> {
                            if (success) {
                                int count = WBUtilsClient.getAutoRejoin().getDisconnectIndicatorCount();
                                context.getSource().sendFeedback(Text.literal(Messages.format("command.autorejoin.refresh_success", "count", String.valueOf(count))));
                            } else {
                                context.getSource().sendFeedback(Text.literal(Messages.get("command.autorejoin.refresh_failed")));
                            }
                        });
                        return 1;
                    })
                )
                .executes(context -> {
                    context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.autorejoin.help.header")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils autorejoin toggle " + Messages.getColorAccent() + "- " + Messages.get("command.autorejoin.help.toggle")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils autorejoin status " + Messages.getColorAccent() + "- " + Messages.get("command.autorejoin.help.status")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils autorejoin cancel " + Messages.getColorAccent() + "- " + Messages.get("command.autorejoin.help.cancel")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils autorejoin refresh " + Messages.getColorAccent() + "- " + Messages.get("command.autorejoin.help.refresh")));
                    return 1;
                })
            )
            .then(literal("unwrap")
                .then(literal("toggle")
                    .executes(context -> {
                        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                        config.unwrapEnabled = !config.unwrapEnabled;
                        WBUtilsClient.getConfigManager().save();
                        String state = config.unwrapEnabled ? Messages.get("status.on") : Messages.get("status.off");
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.unwrap.toggle", "state", state)));
                        return 1;
                    })
                )
                .then(literal("status")
                    .executes(context -> {
                        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                        var unwrap = WBUtilsClient.getUnwrap();
                        
                        context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.unwrap.status.header")));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.unwrap.status.enabled", "value", config.unwrapEnabled ? Messages.get("common.yes") : Messages.get("common.no"))));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.unwrap.status.debug", "value", config.debugUnwrap ? Messages.get("status.on") : Messages.get("status.off"))));
                        
                        String cmds = String.join(", ", unwrap.getUnwrapCommands());
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.unwrap.status.commands", "value", Messages.getColorAccent() + cmds)));
                        return 1;
                    })
                )
                .executes(context -> {
                    context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.unwrap.help.header")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils unwrap toggle " + Messages.getColorAccent() + "- " + Messages.get("command.unwrap.help.toggle")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils unwrap status " + Messages.getColorAccent() + "- " + Messages.get("command.unwrap.help.status")));
                    return 1;
                })
            )

            .then(literal("system")
                .then(literal("status")
                    .executes(context -> {
                        context.getSource().sendFeedback(Text.literal(Messages.getColorMain() + "§l⸻ System Status ⸻"));
                        
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "Mod Version: " + Messages.getColorAccent() + "0.91"));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "API Server: " + Messages.getColorAccent() + WBUtilsClient.getConfigManager().getConfig().authServerUrl));
                        
                        AuthService.checkLinkStatusSafe(result -> {
                            String apiStatus = result == AuthService.LinkCheckResult.ERROR ? Messages.get("status.offline") : Messages.get("status.online");
                            context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "API Status: " + apiStatus));
                        });
                        
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "Features Active: " + Messages.getColorAccent() + "9"));
                        return 1;
                    })
                )
                .then(literal("diagnostics")
                    .executes(context -> {
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.system.diagnostics_running")));
                        AuthService.testEndpoints(msg ->
                            MinecraftClient.getInstance().execute(() ->
                                context.getSource().sendFeedback(Text.literal(msg))
                            )
                        );
                        return 1;
                    })
                )
                .then(literal("setserver")
                    .then(argument("url", StringArgumentType.string())
                        .executes(context -> {
                            String url = StringArgumentType.getString(context, "url");
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.authServerUrl = url;
                            WBUtilsClient.getConfigManager().save();
                            context.getSource().sendFeedback(Text.literal(Messages.format("command.system.server_set", "url", url)));
                            return 1;
                        })
                    )
                )
                .then(literal("setwebhook")
                    .then(argument("url", StringArgumentType.string())
                        .executes(context -> {
                            String url = StringArgumentType.getString(context, "url");
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.webhookUrl = url;
                            WBUtilsClient.getConfigManager().save();
                            context.getSource().sendFeedback(Text.literal(Messages.get("command.system.webhook_legacy_set")));
                            return 1;
                        })
                    )
                )
                .then(literal("modusers")
                    .then(literal("list")
                        .executes(context -> {
                            context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "Syncing mod users..."));
                            WBUtilsClient.getModUserManager().syncOnlineModUsers(success -> {
                                List<String> users = WBUtilsClient.getModUserManager().getOnlineModUsers();
                                if (users.isEmpty()) {
                                    context.getSource().sendFeedback(Text.literal(Messages.get("command.system.modusers.none")));
                                } else {
                                    context.getSource().sendFeedback(Text.literal(Messages.format("command.system.modusers.header", "count", String.valueOf(users.size()))));
                                    context.getSource().sendFeedback(Text.literal(Messages.getColorAccent() + String.join(", ", users)));
                                }
                            });
                            return 1;
                        })
                    )
                )
                .then(literal("debug")
                    .then(literal("bounty")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.debugBounty = !config.debugBounty;
                            WBUtilsClient.getConfigManager().save();
                            String state = config.debugBounty ? Messages.get("status.on") : Messages.get("status.off");
                            context.getSource().sendFeedback(Text.literal(Messages.format("command.debug.bounty", "state", state)));
                            return 1;
                        })
                    )
                    .then(literal("damage")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.debugDamage = !config.debugDamage;
                            WBUtilsClient.getConfigManager().save();
                            String state = config.debugDamage ? Messages.get("status.on") : Messages.get("status.off");
                            context.getSource().sendFeedback(Text.literal(Messages.format("command.debug.damage", "state", state)));
                            return 1;
                        })
                    )
                    .then(literal("koth")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.debugKothState = !config.debugKothState;
                            WBUtilsClient.getConfigManager().save();
                            String state = config.debugKothState ? Messages.get("status.on") : Messages.get("status.off");
                            context.getSource().sendFeedback(Text.literal(Messages.format("command.debug.koth", "state", state)));
                            return 1;
                        })
                    )
                    .then(literal("ktrack")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.debugKtrack = !config.debugKtrack;
                            WBUtilsClient.getConfigManager().save();
                            String state = config.debugKtrack ? Messages.get("status.on") : Messages.get("status.off");
                            context.getSource().sendFeedback(Text.literal(Messages.format("command.debug.ktrack", "state", state)));
                            return 1;
                        })
                    )
                    .then(literal("http")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.debugHttp = !config.debugHttp;
                            WBUtilsClient.getConfigManager().save();
                            String state = config.debugHttp ? Messages.get("status.on") : Messages.get("status.off");
                            context.getSource().sendFeedback(Text.literal(Messages.format("command.debug.http", "state", state)));
                            return 1;
                        })
                    )
                    .then(literal("doorspirit")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.debugDoorSpirit = !config.debugDoorSpirit;
                            WBUtilsClient.getConfigManager().save();
                            String state = config.debugDoorSpirit ? Messages.get("status.on") : Messages.get("status.off");
                            context.getSource().sendFeedback(Text.literal(Messages.format("command.debug.doorspirit", "state", state)));
                            return 1;
                        })
                    )
                    .then(literal("rps")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.debugRPS = !config.debugRPS;
                            WBUtilsClient.getConfigManager().save();
                            String state = config.debugRPS ? Messages.get("status.on") : Messages.get("status.off");
                            context.getSource().sendFeedback(Text.literal(Messages.format("command.debug.rps", "state", state)));
                            return 1;
                        })
                    )
                    .then(literal("autorps")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.debugAutoRPS = !config.debugAutoRPS;
                            WBUtilsClient.getConfigManager().save();
                            String state = config.debugAutoRPS ? Messages.get("status.on") : Messages.get("status.off");
                            context.getSource().sendFeedback(Text.literal(Messages.format("command.debug.autorps", "state", state)));
                            return 1;
                        })
                    )
                    .then(literal("autorejoin")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.debugAutoRejoin = !config.debugAutoRejoin;
                            WBUtilsClient.getConfigManager().save();
                            String state = config.debugAutoRejoin ? Messages.get("status.on") : Messages.get("status.off");
                            context.getSource().sendFeedback(Text.literal(Messages.format("command.debug.autorejoin", "state", state)));
                            return 1;
                        })
                    )
                    .then(literal("unwrap")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.debugUnwrap = !config.debugUnwrap;
                            WBUtilsClient.getConfigManager().save();
                            String state = config.debugUnwrap ? Messages.get("status.on") : Messages.get("status.off");
                            context.getSource().sendFeedback(Text.literal(Messages.format("command.debug.unwrap", "state", state)));
                            return 1;
                        })
                    )
                    .then(literal("housing")
                        .executes(context -> {
                            WBUtilsClient.getHousingDetector().showScoreboardDebug();
                            return 1;
                        })
                    )
                    .then(literal("modusers")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.debugModUsers = !config.debugModUsers;
                            WBUtilsClient.getConfigManager().save();
                            String state = config.debugModUsers ? Messages.get("status.on") : Messages.get("status.off");
                            context.getSource().sendFeedback(Text.literal(Messages.format("command.debug.modusers", "state", state)));
                            return 1;
                        })
                    )
                    .then(literal("bootlist")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.debugBootlist = !config.debugBootlist;
                            WBUtilsClient.getConfigManager().save();
                            String state = config.debugBootlist ? Messages.get("status.on") : Messages.get("status.off");
                            context.getSource().sendFeedback(Text.literal(Messages.format("command.debug.bootlist", "state", state)));
                            return 1;
                        })
                    )
                    .then(literal("statspy")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            config.debugStatSpy = !config.debugStatSpy;
                            WBUtilsClient.getConfigManager().save();
                            String state = config.debugStatSpy ? Messages.get("status.on") : Messages.get("status.off");
                            context.getSource().sendFeedback(Text.literal(Messages.format("command.debug.statspy", "state", state)));
                            return 1;
                        })
                    )
                    .then(literal("all")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            boolean newState = !(config.debugBounty && config.debugDamage && config.debugKothState && config.debugKtrack && config.debugHttp && config.debugDoorSpirit && config.debugRPS && config.debugAutoRPS && config.debugAutoRejoin && config.debugUnwrap);
                            config.debugBounty = newState;
                            config.debugDamage = newState;
                            config.debugKothState = newState;
                            config.debugKtrack = newState;
                            config.debugHttp = newState;
                            config.debugDoorSpirit = newState;
                            config.debugRPS = newState;
                            config.debugAutoRPS = newState;
                            config.debugAutoRejoin = newState;
                            config.debugUnwrap = newState;
                            config.debugModUsers = newState;
                            config.debugBootlist = newState;
                            config.debugStatSpy = newState;
                            config.kothDebugLogs = newState;
                            config.ktrackDebugLogs = newState;
                            WBUtilsClient.getConfigManager().save();
                            String state = newState ? Messages.get("status.on") : Messages.get("status.off");
                            context.getSource().sendFeedback(Text.literal(Messages.format("command.debug.all", "state", state)));
                            return 1;
                        })
                    )
                    .then(literal("status")
                        .executes(context -> {
                            ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                            context.getSource().sendFeedback(Text.literal(Messages.get("command.system.debug.header")));
                            context.getSource().sendFeedback(Text.literal(Messages.format("command.system.debug.modusers", "state", (config.debugModUsers ? Messages.get("status.on") : Messages.get("status.off")))));
                            context.getSource().sendFeedback(Text.literal(""));
                            context.getSource().sendFeedback(Text.literal(Messages.get("command.system.debug.housing_hint")));
                            return 1;
                        })
                    )
                    .executes(context -> {
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.system.debug.help_header")));
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.system.debug.help.bounty")));
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.system.debug.help.damage")));
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.system.debug.help.koth")));
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.system.debug.help.ktrack")));
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.system.debug.help.http")));
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.system.debug.help.doorspirit")));
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.system.debug.help.rps")));
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.system.debug.help.autorps")));
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.system.debug.help.autorejoin")));
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.system.debug.help.unwrap")));
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.system.debug.help.housing")));
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.system.debug.help.modusers")));
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.system.debug.help.all")));
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.system.debug.help.status")));
                        return 1;
                    })
                )
                .executes(context -> {
                    context.getSource().sendFeedback(Text.literal(Messages.get("command.system.header")));
                    context.getSource().sendFeedback(Text.literal(Messages.get("command.system.help.status")));
                    context.getSource().sendFeedback(Text.literal(Messages.get("command.system.help.diagnostics")));
                    context.getSource().sendFeedback(Text.literal(Messages.get("command.system.help.setserver")));
                    context.getSource().sendFeedback(Text.literal(Messages.get("command.system.help.setwebhook")));
                    context.getSource().sendFeedback(Text.literal(Messages.get("command.system.help.modusers")));
                    context.getSource().sendFeedback(Text.literal(Messages.get("command.system.help.debug")));
                    return 1;
                })
            )
            // Bootlist - tracks boots from /boots GUI
            .then(literal("bootlist")
                .then(literal("toggle")
                    .executes(context -> {
                        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                        config.bootlistEnabled = !config.bootlistEnabled;
                        WBUtilsClient.getConfigManager().save();
                        String state = config.bootlistEnabled ? Messages.get("status.enabled") : Messages.get("status.disabled");
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.bootlist.toggle", "state", state)));
                        return 1;
                    })
                )
                .then(literal("status")
                    .executes(context -> {
                        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                        var tracker = WBUtilsClient.getBootlistTracker();
                        
                        context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.bootlist.status.header")));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.bootlist.status.enabled", "value", config.bootlistEnabled ? Messages.get("common.yes") : Messages.get("common.no"))));
                        
                        long lastSync = tracker.getLastSyncTime();
                        String lastSyncStr = lastSync == 0 ? Messages.get("status.never") : Messages.getColorAccent() + getTimeAgo(lastSync);
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.bootlist.status.last_sync", "value", lastSyncStr)));
                        
                        long cooldownMs = tracker.getRemainingCooldownMs();
                        if (cooldownMs > 0) {
                            long cooldownMin = cooldownMs / 60000;
                            context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.bootlist.status.cooldown", "value", "§e" + cooldownMin + " minutes")));
                        } else {
                            context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.bootlist.status.cooldown", "value", Messages.get("status.ready"))));
                        }
                        
                        int bootsTracked = tracker.getLastBootsData().size();
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.bootlist.status.boots_tracked", "value", Messages.getColorAccent() + String.valueOf(bootsTracked))));
                        
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.bootlist.status.debug", "value", config.debugBootlist ? Messages.get("status.on") : Messages.get("status.off"))));
                        return 1;
                    })
                )
                .then(literal("sync")
                    .executes(context -> {
                        var tracker = WBUtilsClient.getBootlistTracker();
                        
                        long cooldownMs = tracker.getRemainingCooldownMs();
                        if (cooldownMs > 0) {
                            long cooldownMin = cooldownMs / 60000;
                            context.getSource().sendFeedback(Text.literal("§c" + Messages.format("command.bootlist.sync.cooldown", "minutes", String.valueOf(cooldownMin))));
                            return 1;
                        }
                        
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.bootlist.sync.hint")));
                        tracker.resetCooldown();
                        return 1;
                    })
                )
                .executes(context -> {
                    context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.bootlist.help.header")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils bootlist toggle " + Messages.getColorAccent() + "- " + Messages.get("command.bootlist.help.toggle")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils bootlist status " + Messages.getColorAccent() + "- " + Messages.get("command.bootlist.help.status")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils bootlist sync " + Messages.getColorAccent() + "- " + Messages.get("command.bootlist.help.sync")));
                    return 1;
                })
            )
            // StatSpy - detects and logs when other players check your stats
            .then(literal("statspy")
                .then(literal("log")
                    .executes(context -> {
                        StatSpy statSpy = WBUtilsClient.getStatSpy();
                        var entries = statSpy.getRecentLogs(10);
                        
                        if (entries.isEmpty()) {
                            context.getSource().sendFeedback(Text.literal(Messages.get("command.statspy.log.empty")));
                            return 1;
                        }
                        
                        context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.statspy.log.header")));
                        for (StatSpy.StatCheckEntry entry : entries) {
                            String message;
                            if (entry.rank != null && !entry.rank.isEmpty()) {
                                message = Messages.format("command.statspy.log.entry",
                                    "time", entry.getFormattedTime(),
                                    "rank", entry.rank,
                                    "player", entry.playerName);
                            } else {
                                message = Messages.format("command.statspy.log.entry_no_rank",
                                    "time", entry.getFormattedTime(),
                                    "player", entry.playerName);
                            }
                            context.getSource().sendFeedback(Text.literal(message));
                        }
                        return 1;
                    })
                )
                .then(literal("toggle")
                    .executes(context -> {
                        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                        config.statSpyEnabled = !config.statSpyEnabled;
                        WBUtilsClient.getConfigManager().save();
                        String state = config.statSpyEnabled ? Messages.get("status.enabled") : Messages.get("status.disabled");
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.statspy.toggle", "state", state)));
                        return 1;
                    })
                )
                .then(literal("status")
                    .executes(context -> {
                        ModConfig config = WBUtilsClient.getConfigManager().getConfig();
                        StatSpy statSpy = WBUtilsClient.getStatSpy();
                        
                        context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.statspy.status.header")));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.statspy.status.enabled", "value", config.statSpyEnabled ? Messages.get("common.yes") : Messages.get("common.no"))));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.statspy.status.log_count", "value", Messages.getColorAccent() + String.valueOf(statSpy.getLogCount()))));
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.format("command.statspy.status.debug", "value", config.debugStatSpy ? Messages.get("status.on") : Messages.get("status.off"))));
                        return 1;
                    })
                )
                .then(literal("clear")
                    .executes(context -> {
                        WBUtilsClient.getStatSpy().clearLog();
                        context.getSource().sendFeedback(Text.literal(Messages.get("command.statspy.clear")));
                        return 1;
                    })
                )
                .executes(context -> {
                    context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.statspy.help.header")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils statspy log " + Messages.getColorAccent() + "- " + Messages.get("command.statspy.help.log")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils statspy toggle " + Messages.getColorAccent() + "- " + Messages.get("command.statspy.help.toggle")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils statspy status " + Messages.getColorAccent() + "- " + Messages.get("command.statspy.help.status")));
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + "/wbutils statspy clear " + Messages.getColorAccent() + "- " + Messages.get("command.statspy.help.clear")));
                    return 1;
                })
            )
            .then(literal("help")
                .executes(context -> {
                    context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.help.header")));
                    String[] helpLines = Messages.raw("command.help.lines").split("\\|");
                    for (String line : helpLines) {
                        context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.translateColorCodes(line)));
                    }
                    return 1;
                })
            )
            .executes(context -> {
                context.getSource().sendFeedback(Text.literal(Messages.withMainBold("command.help.header")));
                String[] helpLines = Messages.raw("command.help.lines").split("\\|");
                for (String line : helpLines) {
                    context.getSource().sendFeedback(Text.literal(Messages.getColorText() + Messages.translateColorCodes(line)));
                }
                return 1;
            })
        );
    }
    

    private static String getTimeAgo(long timestamp) {
        long now = System.currentTimeMillis();
        long diff = now - timestamp;
        
        long seconds = diff / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (days > 0) {
            return days + " " + (days > 1 ? Messages.get("time.days") : Messages.get("time.day")) + " " + Messages.get("time.ago");
        } else if (hours > 0) {
            return hours + " " + (hours > 1 ? Messages.get("time.hours") : Messages.get("time.hour")) + " " + Messages.get("time.ago");
        } else if (minutes > 0) {
            return minutes + " " + (minutes > 1 ? Messages.get("time.minutes") : Messages.get("time.minute")) + " " + Messages.get("time.ago");
        } else {
            return seconds + " " + (seconds > 1 ? Messages.get("time.seconds") : Messages.get("time.second")) + " " + Messages.get("time.ago");
        }
    }
}
