package fr.eternom.eterHub.module.activity;

import com.google.gson.JsonObject;
import fr.eternom.eterHub.module.zone.Region;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Les coins de pêche sans nom (en plus des lieux où la règle fishing est permise, comme le Lac) : tracés en jeu avec la
 * même sélection que les lieux (/eterhub zone point... puis /eterhub fishing add), gardés en base
 * (eterhub_fishing_spots), communs à tous les lobbys. La pêche compte d'où se tient le joueur : tracer la berge avec.
 */
public final class FishingSpots {

    private static final String TABLE = "fishing_spots";
    private static final String CHANGED = "fishing-spots";

    private final JavaPlugin plugin;
    private final Database database;
    private final NetworkBus bus;
    private volatile Map<Long, Region> spots = Map.of();

    public FishingSpots(JavaPlugin plugin, Database database, NetworkBus bus) {
        this.plugin = plugin;
        this.database = database;
        this.bus = bus;
        database.createTable(TABLE,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("world", Column.Type.STRING).length(64).notNull(),
                Column.of("x1", Column.Type.INT).notNull(),
                Column.of("y1", Column.Type.INT).notNull(),
                Column.of("z1", Column.Type.INT).notNull(),
                Column.of("x2", Column.Type.INT).notNull(),
                Column.of("y2", Column.Type.INT).notNull(),
                Column.of("z2", Column.Type.INT).notNull(),
                Column.of("points", Column.Type.TEXT));
        bus.on(CHANGED, data -> Tasks.async(plugin, this::reload, "Coins de pêche non relus"));
    }

    /** Bloquant : relit les coins en base. */
    public void reload() {
        Map<Long, Region> loaded = new LinkedHashMap<>();
        database.query("SELECT * FROM " + database.table(TABLE) + " ORDER BY id").forEach(row -> loaded.put(row.getLong("id"),
                Region.read(row.getString("world"), row.getInt("x1"), row.getInt("y1"), row.getInt("z1"), row.getInt("x2"),
                        row.getInt("y2"), row.getInt("z2"), row.getString("points"))));
        spots = Map.copyOf(loaded);
    }

    public boolean contains(Location location) {
        if (location.getWorld() == null) {
            return false;
        }
        String world = location.getWorld().getName();
        for (Region region : spots.values()) {
            if (region.contains(world, location.getBlockX(), location.getBlockY(), location.getBlockZ())) {
                return true;
            }
        }
        return false;
    }

    public Map<Long, Region> all() {
        return spots;
    }

    /** Le coin où se tient le joueur, s'il y en a un. */
    public Optional<Map.Entry<Long, Region>> at(Location location) {
        String world = location.getWorld() == null ? "" : location.getWorld().getName();
        return spots.entrySet().stream().filter(entry -> entry.getValue().contains(world, location.getBlockX(), location.getBlockY(),
                location.getBlockZ())).findFirst();
    }

    /** Bloquant : ajoute un coin ; renvoie son numéro. */
    public long add(Region region) {
        Map<String, Object> values = new HashMap<>(Map.of("world", region.world(), "x1", region.minX(), "y1", region.minY(),
                "z1", region.minZ(), "x2", region.maxX(), "y2", region.maxY(), "z2", region.maxZ()));
        values.put("points", region.isPolygon() ? region.points() : "");
        long id = database.insertReturningId(TABLE, values);
        changed();
        return id;
    }

    /** Bloquant : retire un coin. */
    public boolean remove(long id) {
        boolean removed = database.delete(TABLE, Map.of("id", id)) > 0;
        if (removed) {
            changed();
        }
        return removed;
    }

    private void changed() {
        reload();
        bus.publish(CHANGED, new JsonObject());
    }

    /** Pour la liste du staff : numéro et description. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        spots.forEach((id, region) -> lines.add("#" + id + " " + region.describe()));
        return lines;
    }

    JavaPlugin plugin() {
        return plugin;
    }
}
