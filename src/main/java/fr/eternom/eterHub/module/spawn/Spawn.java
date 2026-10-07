package fr.eternom.eterHub.module.spawn;

import com.google.gson.JsonObject;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/**
 * Le spawn des lobbys (/eterhub setspawn) : où l'on arrive, où ramène /spawn, et où l'on revient en tombant dans le
 * vide. Gardé en base (eterhub_spawn), COMMUN à tous les lobbys : ils partagent le même monde, et un lobby créé plus
 * tard le trouve sans rien régler. Un changement est appliqué tout de suite sur les autres lobbys (bus réseau).
 * Sans spawn réglé : celui du monde principal. Thread principal ; base en tâche de fond.
 */
public class Spawn {

    private static final String TABLE = "spawn";
    private static final String ID = "lobby";
    private static final String CHANGED = "spawn";

    private final JavaPlugin plugin;
    private final Database database;
    private final NetworkBus bus;
    private volatile Location location;

    public Spawn(JavaPlugin plugin, Database database, NetworkBus bus) {
        this.plugin = plugin;
        this.database = database;
        this.bus = bus;
        database.createTable(TABLE,
                Column.of("id", Column.Type.STRING).length(16).primaryKey(),
                Column.of("world", Column.Type.STRING).length(64).notNull(),
                Column.of("x", Column.Type.DOUBLE).notNull(),
                Column.of("y", Column.Type.DOUBLE).notNull(),
                Column.of("z", Column.Type.DOUBLE).notNull(),
                Column.of("yaw", Column.Type.FLOAT).notNull(),
                Column.of("pitch", Column.Type.FLOAT).notNull());
        bus.on(CHANGED, data -> reload());
        reload();
    }

    public Location location() {
        Location known = location;
        return known != null ? known.clone() : Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    public void teleport(Player player) {
        player.teleportAsync(location(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        player.setFallDistance(0);
    }

    /** Le spawn de tous les lobbys devient la position du joueur. */
    public void set(Location at) {
        location = at.clone();
        Map<String, Object> values = Map.of("id", ID, "world", at.getWorld().getName(), "x", at.getX(), "y", at.getY(),
                "z", at.getZ(), "yaw", at.getYaw(), "pitch", at.getPitch());
        Tasks.async(plugin, () -> {
            database.set(TABLE, values, "id");
            bus.publish(CHANGED, new JsonObject());
        }, "Spawn des lobbys non enregistré");
    }

    /** Relit le spawn en base (démarrage, ou changé sur un autre lobby). */
    private void reload() {
        Tasks.async(plugin, () -> database.getFirst(TABLE, Map.of("id", ID)).ifPresent(row ->
                        Bukkit.getScheduler().runTask(plugin, () -> location = toLocation(row))),
                "Spawn des lobbys non lu");
    }

    private Location toLocation(Row row) {
        World world = Bukkit.getWorld(row.getString("world"));
        if (world == null) {
            plugin.getLogger().warning("Spawn des lobbys dans un monde absent ici (" + row.getString("world")
                    + ") : spawn du monde principal utilisé");
            return null;
        }
        return new Location(world, row.getDouble("x"), row.getDouble("y"), row.getDouble("z"),
                row.getFloat("yaw"), row.getFloat("pitch"));
    }
}
