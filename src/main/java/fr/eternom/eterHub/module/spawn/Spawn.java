package fr.eternom.eterHub.module.spawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Le spawn de CE lobby (config.yml > spawn, réglé avec /eterhub setspawn) : où l'on arrive, où ramène /spawn, et où
 * l'on revient en tombant dans le vide. Sans spawn réglé : celui du monde principal. Thread principal.
 */
public class Spawn {

    private final JavaPlugin plugin;
    private Location location;

    public Spawn(JavaPlugin plugin) {
        this.plugin = plugin;
        this.location = load();
    }

    public Location location() {
        return location != null ? location.clone() : Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    public void teleport(Player player) {
        player.teleportAsync(location(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        player.setFallDistance(0);
    }

    /** Le spawn devient la position du joueur, gardée dans config.yml. */
    public void set(Location at) {
        location = at.clone();
        plugin.getConfig().set("spawn.world", at.getWorld().getName());
        plugin.getConfig().set("spawn.x", at.getX());
        plugin.getConfig().set("spawn.y", at.getY());
        plugin.getConfig().set("spawn.z", at.getZ());
        plugin.getConfig().set("spawn.yaw", (double) at.getYaw());
        plugin.getConfig().set("spawn.pitch", (double) at.getPitch());
        plugin.saveConfig();
    }

    private Location load() {
        String worldName = plugin.getConfig().getString("spawn.world", "");
        if (worldName.isEmpty()) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("spawn.world : monde inconnu (" + worldName + "), spawn du monde principal utilisé");
            return null;
        }
        return new Location(world, plugin.getConfig().getDouble("spawn.x"), plugin.getConfig().getDouble("spawn.y"),
                plugin.getConfig().getDouble("spawn.z"), (float) plugin.getConfig().getDouble("spawn.yaw"),
                (float) plugin.getConfig().getDouble("spawn.pitch"));
    }
}
