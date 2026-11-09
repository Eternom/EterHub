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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Le spawn des lobbys : le point principal (/eterhub spawn set : /spawn, le vide) et des points d'arrivée en plus sur la
 * place (/eterhub spawn add : on arrive sur l'un d'eux au hasard, moins de foule au même endroit). Gardés en base
 * (eterhub_spawn), COMMUNS à tous les lobbys ; un changement est appliqué tout de suite partout (bus réseau). Un point
 * dans un monde absent ici est ignoré et signalé au staff. Sans spawn réglé : celui du monde principal.
 */
public class Spawn {

    private static final String TABLE = "spawn";
    private static final String MAIN = "lobby";
    private static final String EXTRA = "point-";
    private static final String CHANGED = "spawn";

    private final JavaPlugin plugin;
    private final Database database;
    private final NetworkBus bus;
    private volatile Location main;
    private volatile List<Location> extras = List.of();
    private volatile List<String> missingWorlds = List.of();

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

    /** Le spawn principal. */
    public Location location() {
        Location known = main;
        return known != null ? known.clone() : Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    /** Un point d'arrivée au hasard (le principal compris). Sans thread imposé. */
    public Location arrival() {
        List<Location> points = new ArrayList<>(extras);
        if (main != null) {
            points.add(main);
        }
        return points.isEmpty() ? location() : points.get(ThreadLocalRandom.current().nextInt(points.size())).clone();
    }

    public int pointCount() {
        return extras.size() + (main != null ? 1 : 0);
    }

    /** Mondes de points d'arrivée absents ici (signalés au staff). */
    public List<String> missingWorlds() {
        return missingWorlds;
    }

    public void teleport(Player player) {
        player.setFallDistance(0);
        player.teleportAsync(location(), PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    /** Le spawn principal de tous les lobbys devient cette position. */
    public void set(Location at) {
        main = at.clone();
        save(MAIN, at);
    }

    /** Un point d'arrivée de plus. */
    public void add(Location at) {
        List<Location> next = new ArrayList<>(extras);
        next.add(at.clone());
        extras = next;
        save(EXTRA + System.currentTimeMillis() % 100_000_000, at);
    }

    /** Retire les points en plus (le principal reste). */
    public void clearExtras() {
        extras = List.of();
        Tasks.async(plugin, () -> {
            database.execute("DELETE FROM " + database.table(TABLE) + " WHERE id LIKE ?", EXTRA + "%");
            bus.publish(CHANGED, new JsonObject());
        }, "Points d'arrivée non effacés");
    }

    private void save(String id, Location at) {
        Map<String, Object> values = Map.of("id", id, "world", at.getWorld().getName(), "x", at.getX(), "y", at.getY(),
                "z", at.getZ(), "yaw", at.getYaw(), "pitch", at.getPitch());
        Tasks.async(plugin, () -> {
            database.set(TABLE, values, "id");
            bus.publish(CHANGED, new JsonObject());
        }, "Spawn des lobbys non enregistré");
    }

    /** Relit les points en base (démarrage, ou changés sur un autre lobby). */
    private void reload() {
        Tasks.async(plugin, () -> {
            List<Row> rows = database.get(TABLE, Map.of());
            Bukkit.getScheduler().runTask(plugin, () -> {
                Location freshMain = null;
                List<Location> freshExtras = new ArrayList<>();
                List<String> missing = new ArrayList<>();
                for (Row row : rows) {
                    World world = Bukkit.getWorld(row.getString("world"));
                    if (world == null) {
                        missing.add(row.getString("id") + " (" + row.getString("world") + ")");
                        continue;
                    }
                    Location at = new Location(world, row.getDouble("x"), row.getDouble("y"), row.getDouble("z"),
                            row.getFloat("yaw"), row.getFloat("pitch"));
                    if (MAIN.equals(row.getString("id"))) {
                        freshMain = at;
                    } else {
                        freshExtras.add(at);
                    }
                }
                main = freshMain;
                extras = List.copyOf(freshExtras);
                missingWorlds = List.copyOf(missing);
                if (!missing.isEmpty()) {
                    plugin.getLogger().warning("Points d'arrivée dans un monde absent ici : " + missing + " (spawn du monde principal utilisé)");
                }
            });
        }, "Spawn des lobbys non lu");
    }
}
