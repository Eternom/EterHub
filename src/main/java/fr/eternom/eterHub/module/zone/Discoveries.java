package fr.eternom.eterHub.module.zone;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les lieux découverts par chaque joueur (eterhub_discoveries), communs à tous les lobbys. Lus à l'arrivée, gardés en
 * mémoire tant qu'il est là ; une découverte est écrite tout de suite.
 */
public class Discoveries {

    private static final String TABLE = "discoveries";

    private final JavaPlugin plugin;
    private final Database database;
    private final Map<UUID, Set<String>> known = new ConcurrentHashMap<>();

    public Discoveries(JavaPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        database.createTable(TABLE,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("zone", Column.Type.STRING).length(48).primaryKey(),
                Column.of("found_at", Column.Type.LONG).notNull());
    }

    /** Bloquant (arrivée). */
    public void load(UUID player) {
        Set<String> zones = ConcurrentHashMap.newKeySet();
        database.get(TABLE, Map.of("uuid", player)).forEach(row -> zones.add(row.getString("zone")));
        known.put(player, zones);
    }

    public void forget(UUID player) {
        known.remove(player);
    }

    public boolean isLoaded(UUID player) {
        return known.containsKey(player);
    }

    public Set<String> of(UUID player) {
        return new HashSet<>(known.getOrDefault(player, Set.of()));
    }

    public boolean has(UUID player, String zone) {
        return known.getOrDefault(player, Set.of()).contains(zone);
    }

    /** true si c'est une nouvelle découverte (écrite en tâche de fond). Thread principal. */
    public boolean discover(UUID player, String zone) {
        Set<String> zones = known.get(player);
        if (zones == null || !zones.add(zone)) {
            return false;
        }
        Tasks.async(plugin, () -> database.execute("INSERT IGNORE INTO " + database.table(TABLE)
                + " (uuid, zone, found_at) VALUES (?, ?, ?)", player, zone, System.currentTimeMillis()), "Découverte non gardée");
        return true;
    }
}
