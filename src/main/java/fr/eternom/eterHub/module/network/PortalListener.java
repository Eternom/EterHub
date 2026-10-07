package fr.eternom.eterHub.module.network;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.PortalType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Portails du Nether du lobby (portals.open-selector) : ils ne mènent nulle part ; y entrer ouvre le sélecteur de
 * serveurs. Le jeu ferme tout menu tant que le joueur est DANS un portail (effet de transition, côté client) : le
 * joueur est donc d'abord replacé là où il était juste avant d'y entrer, puis le menu s'ouvre.
 * Les blocs de portail tiennent sans cadre d'obsidienne (posés avec WorldEdit, ex : //set nether_portal).
 */
public class PortalListener implements Listener {

    /** Le temps que la téléportation arrive chez le joueur, avant d'ouvrir le menu. */
    private static final long OPEN_DELAY_TICKS = 2;
    /** Le contact avec le portail se répète à chaque tick : un seul menu par passage. */
    private static final long COOLDOWN_MILLIS = 1500;

    private final JavaPlugin plugin;
    private final NetworkGui gui;
    /** Dernière position de chaque joueur hors d'un portail. */
    private final Map<UUID, Location> lastOutside = new HashMap<>();
    private final Map<UUID, Long> lastOpened = new HashMap<>();

    public PortalListener(JavaPlugin plugin, NetworkGui gui) {
        this.plugin = plugin;
        this.gui = gui;
    }

    @EventHandler
    public void onEnter(EntityPortalEnterEvent event) {
        if (event.getPortalType() != PortalType.NETHER || !(event.getEntity() instanceof Player player)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastOpened.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MILLIS) {
            return;
        }
        lastOpened.put(player.getUniqueId(), now);
        Location back = lastOutside.get(player.getUniqueId());
        if (back != null) {
            player.teleportAsync(back, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                gui.openServers(player);
            }
        }, OPEN_DELAY_TICKS);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (event.hasChangedBlock() && !inPortal(to)) {
            lastOutside.put(event.getPlayer().getUniqueId(), to.clone());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastOutside.remove(event.getPlayer().getUniqueId());
        lastOpened.remove(event.getPlayer().getUniqueId());
    }

    /** Aucun voyage par portail au lobby (joueurs et entités). */
    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onEntityPortal(EntityPortalEvent event) {
        event.setCancelled(true);
    }

    /** Un bloc de portail sans cadre d'obsidienne disparaîtrait à la première mise à jour : il reste. */
    @EventHandler(ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        if (event.getBlock().getType() == Material.NETHER_PORTAL) {
            event.setCancelled(true);
        }
    }

    private static boolean inPortal(Location at) {
        return at.getBlock().getType() == Material.NETHER_PORTAL
                || at.clone().add(0, 1, 0).getBlock().getType() == Material.NETHER_PORTAL;
    }
}
