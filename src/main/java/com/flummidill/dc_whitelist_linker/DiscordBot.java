package com.flummidill.dc_whitelist_linker;


import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
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
import java.security.SecureRandom;
import java.time.Instant;


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
                    Commands.slash("linkmc", "Link your Minecraft-Account to your Discord-Account."),
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
                manager.getAllLinkedAccountNamesAsync().thenAccept(linkedAccounts -> {
                    Guild guild = jda.getGuildById(guildId);

                    if (guild != null) {
                        for (Object[] account : linkedAccounts) {
                            String dcUUID = (String) account[0];
                            String dcName = (String) account[1];
                            String mcUUID = (String) account[2];
                            String mcName = (String) account[3];

                            Member member = guild.getMemberById(dcUUID);

                            if (member == null || member.getRoles().stream().noneMatch(r -> r.getId().equals(whitelistRoleId)) || (accessRoleRequired && member.getRoles().stream().noneMatch(r -> r.getId().equals(accessRoleId)))) {
                                plugin.getLogger().info("Auto Unlinking User!\n       DC-Name: " + dcName + " | MC-Name: " + mcName);
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
                            String authCode = genAuthCode();
                            Long expiryTime = Instant.now().plusSeconds(300).getEpochSecond();

                            manager.startLinking(event.getUser().getId(), event.getUser().getName(), authCode, expiryTime);

                            event.reply("To Link your Account, run `/linkdc " + authCode + "` in Minecraft.\nExpiry: <t:" + expiryTime + ":R>").setEphemeral(true).queue();
                        } else {
                            event.reply("You already have a linked Minecraft-Account: " + mcName).setEphemeral(true).queue();
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

    public static String genAuthCode() {
        String codeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        SecureRandom randomVal = new SecureRandom();

        StringBuilder authCode = new StringBuilder(6);

        for (int i = 0; i < 6; i++) {
            int index = randomVal.nextInt(codeChars.length());
            authCode.append(codeChars.charAt(index));
        }

        return authCode.toString();
    }

    @Override
    public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent event) {
        if (event.getGuild().getId().equals(guildId)) {
            if (event.getRoles().stream().anyMatch(role -> role.getId().equals(whitelistRoleId)) || (accessRoleRequired && event.getRoles().stream().anyMatch(role -> role.getId().equals(accessRoleId)))) {
                String dcUUID = event.getUser().getId();
                String dcName = event.getUser().getName();
                String mcUUID = manager.getMinecraftUUID(dcUUID);
                String mcName = manager.getMinecraftName(dcUUID);

                if (mcUUID != null && mcName != null && !mcUUID.equals("ERROR")) {
                    plugin.getLogger().info("Auto Unlinking User!\n       DC-Name: " + dcName + " | MC-Name: " + mcName);
                    manager.startUnLinking(dcUUID, mcUUID);
                } else {
                    plugin.getLogger().info("Failed to Auto Unlink User \"" + dcName + "\": Minecraft Account Data Unavailable.");
                }
            }
        }
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        if (event.getGuild().getId().equals(guildId)) {
            Member member = event.getMember();

            if (member != null) {
                manager.unLinkRemovedMember(member.getId());
            }
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

                            removeAccessRole(member);
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

    public void removeAccessRole(Member member) {
        if (removeAccessRoleOnUnlink) {
            Guild guild = jda.getGuildById(guildId);

            if (guild != null) {
                Role role = guild.getRoleById(accessRoleId);

                if (role != null) {
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

    public void stopBot() {
        if (jda != null) {
            jda.shutdown();
        }
    }
}