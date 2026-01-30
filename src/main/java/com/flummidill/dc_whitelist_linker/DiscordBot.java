package com.flummidill.dc_whitelist_linker;


import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audit.ActionType;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.guild.member.GuildMemberUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;


public class DiscordBot extends ListenerAdapter {

    private JDA jda;
    private final DCWhitelistLinker plugin;
    private WhitelistManager manager;

    public String botToken;
    public String guildId;
    public String whitelistRoleId;
    public boolean accessRoleRequired;
    public boolean removeAccessRoleOnUnlink;
    public String accessRoleId;
    public boolean useLinkingChannel;
    public String linkingChannelId;

    public DiscordBot(DCWhitelistLinker plugin) {
        this.plugin = plugin;
    }


    public void startBot() {
        this.manager = plugin.manager;

        try {
            jda = JDABuilder.createDefault(botToken)
                    .enableIntents(
                            GatewayIntent.GUILD_MEMBERS,
                            GatewayIntent.GUILD_PRESENCES,
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.GUILD_MODERATION,
                            GatewayIntent.MESSAGE_CONTENT
                    )
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .setChunkingFilter(ChunkingFilter.ALL)
                    .addEventListeners(this)
                    .build();

            jda.updateCommands().addCommands(
                    Commands.slash("linkmc", "Link your Minecraft-Account to your Discord-Account.")
                            .addOption(OptionType.STRING, "auth_code", "Your Auth-Code from Minecraft.", true),
                    Commands.slash("unlinkmc", "Unlink your Minecraft-Account from your Discord-Account."),
                    Commands.slash("forceunlink", "Unlink another Player's Minecraft-Account from their Discord-Account.")
                            .addOption(OptionType.USER, "target", "The Player to UnLink.", true)
                            .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR))
            ).queue();

            new BukkitRunnable() {
                @Override
                public void run() {
                    UpdateAllMembersTask();
                }
            }.runTaskLater(plugin, 50L);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void UpdateAllMembersTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            manager.dbWorker.execute(() -> {
                manager.getAllLinkedAccountsAsync().thenAccept(linkedAccounts -> {
                    Guild guild = jda.getGuildById(guildId);

                    if (guild != null) {
                        Role whitelistRole = guild.getRoleById(whitelistRoleId);

                        if (whitelistRole != null) {
                            for (Member member : guild.getMembersWithRoles(whitelistRole)) {
                                boolean isLinked = linkedAccounts.stream().anyMatch(account -> account[0].equals(member.getId()));

                                if (!isLinked) {
                                    plugin.getLogger().info("Cleaning Up Non-Linked Discord User: " + member.getUser().getName());
                                    finishUnLinking(member.getId());
                                }
                            }
                        }

                        for (Object[] account : linkedAccounts) {
                            String dcUUID = (String) account[0];
                            String dcName = (String) account[1];
                            String mcUUID = (String) account[2];
                            String mcName = (String) account[3];

                            Member member = guild.getMemberById(dcUUID);

                            if (member == null || member.getRoles().stream().noneMatch(r -> r.getId().equals(whitelistRoleId)) || (accessRoleRequired && member.getRoles().stream().noneMatch(r -> r.getId().equals(accessRoleId)))) {
                                plugin.getLogger().info("Auto Unlinking Discord User: " + dcName);
                                manager.startUnLinking(dcUUID, mcUUID);
                                continue;
                            }

                            member.modifyNickname(mcName).queue();
                        }
                    }
                });

                manager.removeExpiredAuthCodesAsync();
            });
        }, 0L, 15 * 60 * 20L);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getGuild() != null) {
            if (event.getGuild().getId().equals(guildId)) {
                if (useLinkingChannel) {
                    if (!event.getName().equals("forceunlink")) {
                        if (!event.getChannel().getId().equals(linkingChannelId)) {
                            event.reply("Please use the <#" + linkingChannelId + "> Channel for Linking Commands!").setEphemeral(true).queue();
                            return;
                        }
                    }
                }

                switch (event.getName()) {
                    case "linkmc":
                        String authCode = event.getOption("auth_code", OptionMapping::getAsString);

                        if (authCode != null) {
                            if (accessRoleRequired) {
                                Member member = event.getMember();
                                if (member != null) {
                                    if (member.getRoles().stream().noneMatch(role -> role.getId().equals(accessRoleId))) {
                                        event.reply("The <@&" + accessRoleId + "> Role is required to Link your Account!").setEphemeral(true).queue();
                                        return;
                                    }
                                } else {
                                    event.reply("Failed to get your User Data! Please try Again.").setEphemeral(true).queue();
                                    return;
                                }
                            }

                            String mcName = manager.getMinecraftName(event.getUser().getId());

                            if (mcName.equals("ERROR")) {
                                if (manager.authCodeValid(authCode)) {
                                    if (manager.finishLinking(authCode, event.getUser().getId(), event.getUser().getName())) {
                                        event.reply("Successfully linked your Minecraft-Account!").setEphemeral(true).queue();
                                    } else {
                                        event.reply("Failed to Link your Minecraft-Account!").setEphemeral(true).queue();
                                    }
                                } else {
                                    event.reply("Invalid or Expired Auth-Code!").setEphemeral(true).queue();
                                }
                            } else {
                                event.reply("You already have a linked Minecraft-Account!").setEphemeral(true).queue();
                            }
                        } else {
                            event.reply("Please enter a Valid Auth-Code!").setEphemeral(true).queue();
                        }

                        break;


                    case "unlinkmc":
                        String dcUUID = event.getUser().getId();

                        if (!dcUUID.equals("ERROR")) {
                            String mcUUID = manager.getMinecraftUUID(dcUUID);

                            if (!mcUUID.equals("ERROR")) {
                                event.reply("Unlinking your Minecraft-Account: " + manager.getMinecraftName(dcUUID)).setEphemeral(true).queue();
                                manager.startUnLinking(dcUUID, mcUUID);
                            } else {
                                event.reply("You do not have a linked Minecraft-Account.").setEphemeral(true).queue();
                            }
                        } else {
                            event.reply("Failed to determine your UUID!").setEphemeral(true).queue();
                        }

                        break;


                    case "forceunlink":
                        User target = event.getOption("target", OptionMapping::getAsUser);

                        if (target != null) {
                            String targetDcUUID = target.getId();

                            if (!targetDcUUID.equals("ERROR")) {
                                String targetMcUUID = manager.getMinecraftUUID(targetDcUUID);

                                if (!targetMcUUID.equals("ERROR")) {
                                    event.reply("Unlinking " + target.getName() + "'s Minecraft-Account: " + manager.getMinecraftName(targetDcUUID)).setEphemeral(true).queue();
                                    manager.startUnLinking(targetDcUUID, targetMcUUID);
                                } else {
                                    event.reply(target.getName() + " does not have a linked Minecraft-Account.").setEphemeral(true).queue();
                                }
                            } else {
                                event.reply("Failed to determine the User's UUID!").setEphemeral(true).queue();
                            }
                        }

                        break;
                }
            }
        }
    }

    @Override
    public void onGuildMemberUpdate(GuildMemberUpdateEvent event) {
        if (event.getGuild().getId().equals(guildId)) {
            String userId = event.getUser().getId();
            String userName = event.getUser().getName();

            event.getGuild().retrieveAuditLogs()
                    .type(ActionType.MEMBER_ROLE_UPDATE)
                    .limit(1)
                    .queue(logs -> {
                        if (!logs.isEmpty()) {
                            var entry = logs.get(0);
                            if (entry.getUser() != null &&
                                    entry.getUser().getId().equals(event.getJDA().getSelfUser().getId())) {
                                return;
                            }
                        }

                        event.getGuild().retrieveMemberById(userId).queue(member -> {
                            boolean hasAccessRole = member.getRoles().stream()
                                    .anyMatch(r -> r.getId().equals(accessRoleId));

                            boolean hasMemberRole = member.getRoles().stream()
                                    .anyMatch(r -> r.getId().equals(whitelistRoleId));

                            if (!hasAccessRole || !hasMemberRole) {
                                String mcUUID = manager.getMinecraftUUID(userId);
                                if (mcUUID != null && !mcUUID.equals("ERROR")) {
                                    plugin.getLogger().info("Auto Unlinking Discord User: " + userName);
                                    manager.startUnLinking(userId, mcUUID);
                                }
                            }
                        });
                    });
        }
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        if (event.getGuild().getId().equals(guildId)) {
            manager.unLinkRemovedMember(event.getUser().getId());
        }
    }

    public void finishLinking(String dcUUID, String mcName) {
        try {
            Guild guild = jda.getGuildById(guildId);

            if (guild != null) {
                Role role = guild.getRoleById(whitelistRoleId);

                if (role != null) {
                    Member member = guild.retrieveMemberById(dcUUID).complete();

                    if (member != null) {
                        if (botRoleHighEnoughToManage(guild, member)) {
                            guild.addRoleToMember(member, role).queue();
                            guild.modifyNickname(member, mcName).queue();
                        } else {
                            plugin.getLogger().info("Linking Error: Bot's Highest Role is not Higher than Linked Member's Highest Role!");
                        }
                    } else {
                        plugin.getLogger().info("Error: Member not Found.");
                    }
                } else {
                    plugin.getLogger().info("Error: Whitelist Role not Found.");
                }
            } else {
                plugin.getLogger().info("Error: Guild not Found.");
            }
        } catch (ErrorResponseException e) {
            plugin.getLogger().info("Error: " + e.getMessage());
        }
    }

    public void finishUnLinking(String dcUUID) {
        try {
            Guild guild = jda.getGuildById(guildId);

            if (guild != null) {
                Role role = guild.getRoleById(whitelistRoleId);

                if (role != null) {
                    Member member = guild.retrieveMemberById(dcUUID).complete();

                    if (member != null) {
                        if (botRoleHighEnoughToManage(guild, member)) {
                            guild.removeRoleFromMember(member, role).queue();
                            guild.modifyNickname(member, null).queue();
                        } else {
                            plugin.getLogger().info("UnLinking Error: Bot's Highest Role is not Higher than Linked Member's Highest Role!");
                        }
                    } else {
                        plugin.getLogger().info("Error: Member not Found.");
                    }
                } else {
                    plugin.getLogger().info("Error: Whitelist Role not Found.");
                }
            } else {
                plugin.getLogger().info("Error: Guild not Found.");
            }
        } catch (ErrorResponseException e) {
            plugin.getLogger().info("Error: " + e.getMessage());
        }
    }

    public void removeAccessRole(String dcUUID) {
        if (accessRoleRequired) {
            Guild guild = jda.getGuildById(guildId);

            if (guild != null) {
                Role role = guild.getRoleById(accessRoleId);

                if (role != null) {
                    Member member = guild.retrieveMemberById(dcUUID).complete();

                    if (member != null) {
                        guild.removeRoleFromMember(member, role).queue();
                    } else {
                        plugin.getLogger().info("Error: Member not Found.");
                    }
                } else {
                    plugin.getLogger().info("Error: Access Role not Found.");
                }
            } else {
                plugin.getLogger().info("Error: Guild not Found.");
            }
        }
    }

    public boolean botRoleHighEnoughToManage(Guild guild, Member member) {
        if (guild != null) {
            if (member != null) {
                Member bot = guild.getSelfMember();

                if (bot != null) {
                    return bot.getRoles().get(0).getPosition() >= member.getRoles().get(0).getPosition();
                }
            }
        }

        return false;
    }

    public void stopBot() {
        if (jda != null) {
            jda.shutdown();
        }
    }
}