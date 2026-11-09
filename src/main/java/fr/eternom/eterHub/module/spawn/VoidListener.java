package fr.eternom.eterHub.module.spawn;

import fr.eternom.eterHub.module.core.HubSettings;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Le vide : sous la hauteur du monde (world.void-y, ou world.void-y-per-world.<monde>), retour au spawn avec un
 * effet. Une seule fois par chute (2 s entre deux retours), jamais pour un spectateur. Un parcours en cours a la main
 * (retour au dernier point de passage) : interceptor.
 */
public class VoidListener implements Listener {

    private static final long COOLDOWN_MILLIS = 2000;

    private final JavaPlugin plugin;
    private final Spawn spawn;
    private final HubSettings settings;
    private final Map<UUID, Long> lastReturn = new HashMap<>();
    private Predicate<Player> interceptor = player -> false;

    public VoidListener(JavaPlugin plugin, Spawn spawn, HubSettings settings) {
        this.plugin = plugin;
        this.spawn = spawn;
        this.settings = settings;
    }

    /** Un module qui ramène lui-même le joueur (parcours) : true = géré. */
    public void setInterceptor(Predicate<Player> interceptor) {
        this.interceptor = interceptor;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo().getBlockY() == event.getFrom().getBlockY()) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR || event.getTo().getY() >= voidY(player.getWorld().getName())) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastReturn.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MILLIS) {
            return;
        }
        lastReturn.put(player.getUniqueId(), now);
        if (interceptor.test(player)) {
            return;
        }
        spawn.teleport(player);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.playSound(player, Sound.ENTITY_PHANTOM_FLAP, 0.6f, 0.8f);
                player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.05);
            }
        }, 3);
    }

    private double voidY(String world) {
        String path = "world.void-y-per-world." + world;
        return plugin.getConfig().contains(path) ? plugin.getConfig().getDouble(path) : settings.number("world.void-y", -64);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastReturn.remove(event.getPlayer().getUniqueId());
    }
}
