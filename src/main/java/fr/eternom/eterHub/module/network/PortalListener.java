package fr.eternom.eterHub.module.network;

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

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Portails du Nether du lobby (portals.open-selector) : ils ne mènent nulle part ; y entrer ouvre le sélecteur de
 * serveurs, une fois par passage (rouvert seulement après en être sorti). Les blocs de portail tiennent sans cadre
 * d'obsidienne (posés avec WorldEdit, ex : //set nether_portal), pour habiller le lobby librement.
 */
public class PortalListener implements Listener {

    private final NetworkGui gui;
    /** Joueurs dans un portail, à qui le menu a déjà été ouvert. */
    private final Set<UUID> inside = new HashSet<>();

    public PortalListener(NetworkGui gui) {
        this.gui = gui;
    }

    @EventHandler
    public void onEnter(EntityPortalEnterEvent event) {
        if (event.getPortalType() == PortalType.NETHER && event.getEntity() instanceof Player player
                && inside.add(player.getUniqueId())) {
            gui.openServers(player);
        }
    }

    /** Sorti du portail : le menu pourra se rouvrir au prochain passage. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedBlock() && inside.contains(event.getPlayer().getUniqueId())
                && event.getTo().getBlock().getType() != Material.NETHER_PORTAL
                && event.getTo().clone().add(0, 1, 0).getBlock().getType() != Material.NETHER_PORTAL) {
            inside.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        inside.remove(event.getPlayer().getUniqueId());
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
}
