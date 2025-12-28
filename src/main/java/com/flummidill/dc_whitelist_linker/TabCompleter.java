package com.flummidill.dc_whitelist_linker;


import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.stream.Collectors;


public class TabCompleter implements org.bukkit.command.TabCompleter {

    private final DCWhitelistLinker plugin;
    private final WhitelistManager manager;


    public TabCompleter(DCWhitelistLinker plugin, WhitelistManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }


    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String string, String[] args) {
        if (!(sender instanceof Player)) {
            return Collections.emptyList();
        }

        Player player = (Player) sender;

        switch (command.getName()) {
            case "linkdc":
                return List.of("<Auth-Code>");
            case "unlinkdc":
                return Collections.emptyList();
            case "forceunlink":
                if (player.hasPermission("dcwhitelistlinker.forceunlink")) {
                    if (args.length == 1) {
                        return manager.getAllLinkedMinecraftAccountNames().stream().sorted().collect(Collectors.toList());
                    }
                    if (args.length == 2) {
                        return List.of("[<remove-access-role>]");
                    }
                }
        }

        return Collections.emptyList();
    }
}