package fr.eternom.eterHub.module.zone;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Les limites et le point d'arrivée des zones (eterhub_zones), tracés en jeu et COMMUNS à tous les lobbys. Bloquant.
 * La description de chaque zone (règles, ambiance) reste dans config.yml.
 */
public class ZoneStore {

    private static final String TABLE = "zones";

    /** Point d'arrivée d'une zone (carte du village), dans son monde. */
    public record Warp(String world, double x, double y, double z, float yaw, float pitch) {
    }

    private final Database database;

    public ZoneStore(Database database) {
        this.database = database;
        database.createTable(TABLE,
                Column.of("id", Column.Type.STRING).length(48).primaryKey(),
                Column.of("world", Column.Type.STRING).length(64).notNull(),
                Column.of("x1", Column.Type.INT).notNull(),
                Column.of("y1", Column.Type.INT).notNull(),
                Column.of("z1", Column.Type.INT).notNull(),
                Column.of("x2", Column.Type.INT).notNull(),
                Column.of("y2", Column.Type.INT).notNull(),
                Column.of("z2", Column.Type.INT).notNull(),
                Column.of("warp_world", Column.Type.STRING).length(64),
                Column.of("wx", Column.Type.DOUBLE),
                Column.of("wy", Column.Type.DOUBLE),
                Column.of("wz", Column.Type.DOUBLE),
                Column.of("wyaw", Column.Type.FLOAT),
                Column.of("wpitch", Column.Type.FLOAT));
        // Depuis 2.0.1 : surface libre (« x,z;x,z;... »), vide pour un pavé
        database.addColumn(TABLE, Column.of("points", Column.Type.TEXT));
    }

    public Map<String, Region> regions() {
        Map<String, Region> regions = new LinkedHashMap<>();
        database.get(TABLE, Map.of()).forEach(row -> regions.put(row.getString("id"), Region.read(row.getString("world"),
                row.getInt("x1"), row.getInt("y1"), row.getInt("z1"), row.getInt("x2"), row.getInt("y2"), row.getInt("z2"),
                row.getString("points"))));
        return regions;
    }

    public Map<String, Warp> warps() {
        Map<String, Warp> warps = new HashMap<>();
        for (Row row : database.get(TABLE, Map.of())) {
            warp(row).ifPresent(warp -> warps.put(row.getString("id"), warp));
        }
        return warps;
    }

    public void saveRegion(String id, Region region) {
        Map<String, Object> values = new HashMap<>(Map.of("id", id, "world", region.world(), "x1", region.minX(), "y1", region.minY(),
                "z1", region.minZ(), "x2", region.maxX(), "y2", region.maxY(), "z2", region.maxZ()));
        values.put("points", region.isPolygon() ? region.points() : "");
        database.set(TABLE, values, "id");
    }

    /** false si la zone n'est pas encore tracée (le point d'arrivée va dans sa ligne). */
    public boolean saveWarp(String id, Warp warp) {
        return database.update(TABLE, Map.of("warp_world", warp.world(), "wx", warp.x(), "wy", warp.y(), "wz", warp.z(),
                "wyaw", warp.yaw(), "wpitch", warp.pitch()), Map.of("id", id)) > 0;
    }

    public boolean remove(String id) {
        return database.delete(TABLE, Map.of("id", id)) > 0;
    }

    private static Optional<Warp> warp(Row row) {
        String world = row.getString("warp_world");
        if (world == null) {
            return Optional.empty();
        }
        return Optional.of(new Warp(world, row.getDouble("wx"), row.getDouble("wy"), row.getDouble("wz"),
                row.getFloat("wyaw"), row.getFloat("wpitch")));
    }
}
