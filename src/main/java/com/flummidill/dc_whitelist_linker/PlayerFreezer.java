package com.flummidill.dc_whitelist_linker;


import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public class PlayerFreezer implements Listener {

    private final JavaPlugin plugin;
    private final Set<UUID> frozenPlayers = ConcurrentHashMap.newKeySet();

    public PlayerFreezer(JavaPlugin plugin) {
        this.plugin = plugin;

        startFrozenPlayerTask();
    }


    public void freezePlayer(UUID playerUUID) {
        frozenPlayers.add(playerUUID);
    }

    public boolean isFrozen(Player player) {
        return frozenPlayers.contains(player.getUniqueId());
    }

    public void unfreezePlayer(UUID playerUUID) {
        frozenPlayers.remove(playerUUID);
    }


    // Frozen Player Notifier Task
    private void startFrozenPlayerTask() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (UUID uuid : frozenPlayers) {
                Player p = plugin.getServer().getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
                    p.sendTitle("§aPlease Link your Discord-Account!", "§aUse the \"/linkmc\" Command on Discord!", 0, 40, 0);
                }
            }
        }, 0L, 20L);
    }


    // Cancel Events
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isFrozen(event.getPlayer())) return;

        if (!event.getMessage().toLowerCase().startsWith("/linkdc")) {
            event.setCancelled(true);
        }
    }

    @EventHandler public void onMove(PlayerMoveEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onInteract(PlayerInteractEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onBreak(BlockBreakEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onHit(EntityDamageByEntityEvent e) { if (e.getDamager() instanceof Player p && isFrozen(p)) e.setCancelled(true); }
    @EventHandler public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p && isFrozen(p)) e.setCancelled(true); }
    @EventHandler public void onPickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && isFrozen(p)) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onInventory(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && isFrozen(p)) e.setCancelled(true); }
    @EventHandler public void onConsume(PlayerItemConsumeEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onSneak(PlayerToggleSneakEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onSprint(PlayerToggleSprintEvent e) { if (isFrozen(e.getPlayer())) e.setCancelled(true); }


    // Player Leave
    @EventHandler public void onKick(PlayerKickEvent e) {
        if (isFrozen(e.getPlayer())) {
            unfreezePlayer(e.getPlayer().getUniqueId());
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        if (isFrozen(e.getPlayer())) {
            unfreezePlayer(e.getPlayer().getUniqueId());
        }
    }
}