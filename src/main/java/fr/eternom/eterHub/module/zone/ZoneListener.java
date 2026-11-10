package fr.eternom.eterHub.module.zone;

import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le joueur change de lieu : seulement aux changements de bloc (ou téléportation). Entrée refusée = mouvement annulé.
 * Dans un lieu no-teleport, aucune téléportation pour en sortir (/spawn, carte, plaques, commandes...), sauf le staff.
 */
public class ZoneListener implements Listener {

    public static final String STAFF = "eterhub.staff";

    private final ZoneService zones;
    private final Messages messages;
    private final Map<UUID, Long> lastRefusal = new ConcurrentHashMap<>();

    public ZoneListener(ZoneService zones, Messages messages) {
        this.zones = zones;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedBlock() && !zones.update(event.getPlayer(), event.getTo(), event.getFrom())) {
            event.setCancelled(true);
        }
    }

    /** Avant tout le reste : une téléportation depuis un lieu no-teleport est refusée. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleportOut(PlayerTeleportEvent event) {
        if (!zones.blocksTeleport(event.getPlayer()) || event.getPlayer().hasPermission(STAFF)) {
            return;
        }
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        Long last = lastRefusal.get(event.getPlayer().getUniqueId());
        if (last == null || now - last > 2000) {
            lastRefusal.put(event.getPlayer().getUniqueId(), now);
            messages.send(event.getPlayer(), "zone.no-teleport");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!zones.update(event.getPlayer(), event.getTo(), event.getFrom())) {
            event.setCancelled(true);
        }
    }

    /** En tout premier au départ : les effets du lieu sont retirés (ils ne suivent pas le joueur en survie). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuitFirst(PlayerQuitEvent event) {
        zones.clearEffects(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        zones.quit(event.getPlayer());
        zones.discoveries().forget(event.getPlayer().getUniqueId());
        lastRefusal.remove(event.getPlayer().getUniqueId());
    }
}
