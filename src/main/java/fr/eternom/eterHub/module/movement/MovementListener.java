package fr.eternom.eterHub.module.movement;

import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.zone.ZoneRule;
import fr.eternom.eterHub.module.zone.ZoneService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Double saut (double-jump.*) : en survie ou aventure, le vol est « autorisé » pour capter la double pression sur
 * espace ; au lieu de voler, le joueur est propulsé (délai entre deux sauts, particules de son grade, son), et le saut
 * revient en touchant le sol. Coupé dans les zones qui l'interdisent (grottes, donjons), pendant un parcours, en
 * véhicule. Le droit de voler donné ici est repris au changement de monde, de mode, de zone, en véhicule.
 * Ceux qui ont eterhub.fly (staff, /fly) ne sont jamais touchés : ils volent normalement. Le mode de jeu est réglé
 * à l'arrivée (survie, ArrivalListener). Les mouvements ne sont regardés qu'aux changements de bloc.
 */
public class MovementListener implements Listener {

    public static final String FLY = "eterhub.fly";

    private final JavaPlugin plugin;
    private final ZoneService zones;
    private final HubSettings settings;
    private final Cosmetics cosmetics;
    /** Joueurs à qui EterHub a donné le droit de voler (pour le reprendre, sans toucher au /fly du staff). */
    private final Set<UUID> granted = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastJump = new ConcurrentHashMap<>();
    private Predicate<Player> busy = player -> false;

    public MovementListener(JavaPlugin plugin, ZoneService zones, HubSettings settings, Cosmetics cosmetics) {
        this.plugin = plugin;
        this.zones = zones;
        this.settings = settings;
        this.cosmetics = cosmetics;
    }

    /** Un joueur sans double saut pour l'instant (parcours en cours). */
    public void setBusy(Predicate<Player> busy) {
        this.busy = busy;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    /** Le mode de jeu peut arriver après la connexion (EterSync) : on regarde au tick suivant. */
    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent event) {
        later(event.getPlayer());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        later(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            later(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            later(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        later(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!event.isFlying() || !granted.contains(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        player.setAllowFlight(false); // revient en touchant le sol
        if (!jumps(player)) {
            granted.remove(player.getUniqueId());
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastJump.get(player.getUniqueId());
        if (last != null && now - last < (long) settings.number("double-jump.cooldown-ms", 600)) {
            return;
        }
        lastJump.put(player.getUniqueId(), now);
        player.setVelocity(player.getLocation().getDirection().multiply(settings.number("double-jump.power", 1.2))
                .setY(settings.number("double-jump.height", 0.9)));
        player.playSound(player, Sound.ENTITY_BAT_TAKEOFF, 0.5f, 1.4f);
        Particle trail = cosmetics.trail(player);
        player.getWorld().spawnParticle(trail != null ? trail : Particle.CLOUD, player.getLocation(), 12, 0.3, 0.1, 0.3, 0.02);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }
        Player player = event.getPlayer();
        boolean jumps = jumps(player);
        if (!jumps) {
            revoke(player);
        } else if (!player.getAllowFlight() && event.getTo().clone().subtract(0, 0.1, 0).getBlock().getType().isSolid()) {
            grant(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        revoke(player);
        lastJump.remove(player.getUniqueId());
    }

    /** Remet le droit de voler à jour (zone, mode, monde, véhicule). */
    public void refresh(Player player) {
        if (jumps(player)) {
            grant(player);
        } else {
            revoke(player);
        }
    }

    private void later(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                refresh(player);
            }
        });
    }

    private void grant(Player player) {
        if (!player.getAllowFlight()) {
            player.setAllowFlight(true);
        }
        granted.add(player.getUniqueId());
    }

    private void revoke(Player player) {
        if (granted.remove(player.getUniqueId()) && !player.hasPermission(FLY)) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    /** Double saut pour ce joueur maintenant. */
    private boolean jumps(Player player) {
        GameMode mode = player.getGameMode();
        return settings.bool("double-jump.enabled", true) && (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE)
                && !player.hasPermission(FLY) && !player.isInsideVehicle() && !player.isGliding()
                && zones.allows(player, ZoneRule.DOUBLE_JUMP) && !busy.test(player);
    }
}
