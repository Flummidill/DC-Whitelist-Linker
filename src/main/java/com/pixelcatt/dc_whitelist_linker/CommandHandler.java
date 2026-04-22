package com.pixelcatt.dc_whitelist_linker;


import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.UUID;


public class CommandHandler implements CommandExecutor {

    private final DCWhitelistLinker plugin;
    private final WhitelistManager manager;


    public CommandHandler(DCWhitelistLinker plugin, WhitelistManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }


    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly Players can run this Command!");
            return true;
        }

        Player player = (Player) sender;

        switch (command.getName()) {
             case "unlinkdc":
                if (player.hasPermission("dcwhitelistlinker.unlinkdc")) {
                    String mcUUID = player.getUniqueId().toString();

                    if (!mcUUID.equals("ERROR")) {
                        String dcUUID = manager.getDiscordUUID(UUID.fromString(mcUUID));

                        if (!dcUUID.equals("ERROR")) {
                            sender.sendMessage("§aUnlinking your Discord-Account: " + manager.getDiscordName(UUID.fromString(mcUUID)));
                            manager.startUnLinking(dcUUID, mcUUID);
                        } else {
                            sender.sendMessage("§cYou do not have a linked Discord-Account.");
                        }
                    } else {
                        sender.sendMessage("§cFailed to determine your UUID!");
                    }
                }

                return true;


            case "forceunlink":
                if (player.hasPermission("dcwhitelistlinker.forceunlink")) {
                    if (args.length == 1 || args.length == 2) {
                        String mcUUID = manager.getMinecraftUUIDfromName(args[0]);

                        if (!mcUUID.equals("ERROR")) {
                            String dcUUID = manager.getDiscordUUID(UUID.fromString(mcUUID));

                            if (!dcUUID.equals("ERROR")) {
                                sender.sendMessage("§aUnlinking " + args[0] + "'s Discord-Account: " + manager.getDiscordNameFromUUID(dcUUID));
                                manager.startUnLinking(dcUUID, mcUUID);

                                if (args.length == 2) {
                                    if (args[1].equals("true")) {
                                        manager.dcBot.removeAccessRole(dcUUID);
                                    }
                                }
                            } else {
                                sender.sendMessage("§c" + args[0] + " does not have a linked Discord-Account.");
                            }
                        } else {
                            sender.sendMessage("§cFailed to determine " + args[0] + "'s UUID!");
                        }
                    } else {
                        sender.sendMessage("§cUsage: /forceunlink <Target> [<Remove-Access-Role>]");
                    }
                } else {
                    sender.sendMessage("§cYou do not have permission to run this Command!");
                }

                return true;
        }


        return true;
    }
}