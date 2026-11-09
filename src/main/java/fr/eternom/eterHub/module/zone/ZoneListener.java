package fr.eternom.eterHub.module.zone;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Le joueur change de zone : seulement aux changements de bloc (ou téléportation). Entrée refusée = mouvement annulé. */
public class ZoneListener implements Listener {

    private final ZoneService zones;

    public ZoneListener(ZoneService zones) {
        this.zones = zones;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedBlock() && !zones.update(event.getPlayer(), event.getTo(), event.getFrom())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!zones.update(event.getPlayer(), event.getTo(), event.getFrom())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        zones.quit(event.getPlayer());
        zones.discoveries().forget(event.getPlayer().getUniqueId());
    }
}
