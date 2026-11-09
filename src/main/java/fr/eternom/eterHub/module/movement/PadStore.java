package fr.eternom.eterHub.module.movement;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Les plaques (eterhub_pads), communes à tous les lobbys. Bloquant. */
public class PadStore {

    private static final String TABLE = "pads";

    private final Database database;

    public PadStore(Database database) {
        this.database = database;
        database.createTable(TABLE,
                Column.of("id", Column.Type.LONG).primaryKey().autoIncrement(),
                Column.of("type", Column.Type.STRING).length(16).notNull(),
                Column.of("world", Column.Type.STRING).length(64).notNull(),
                Column.of("x", Column.Type.INT).notNull(),
                Column.of("y", Column.Type.INT).notNull(),
                Column.of("z", Column.Type.INT).notNull(),
                Column.of("power", Column.Type.DOUBLE).notNull(),
                Column.of("height", Column.Type.DOUBLE).notNull(),
                Column.of("yaw", Column.Type.FLOAT),
                Column.of("target_world", Column.Type.STRING).length(64),
                Column.of("tx", Column.Type.DOUBLE),
                Column.of("ty", Column.Type.DOUBLE),
                Column.of("tz", Column.Type.DOUBLE),
                Column.of("tyaw", Column.Type.FLOAT),
                Column.of("tpitch", Column.Type.FLOAT));
    }

    public List<Pad> all() {
        List<Pad> pads = new ArrayList<>();
        for (Row row : database.get(TABLE, Map.of())) {
            Pad.Type type;
            try {
                type = Pad.Type.valueOf(row.getString("type"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            Pad.Target target = row.getString("target_world") == null ? null : new Pad.Target(row.getString("target_world"),
                    row.getDouble("tx"), row.getDouble("ty"), row.getDouble("tz"), row.getFloat("tyaw"), row.getFloat("tpitch"));
            Float yaw = row.get("yaw") == null ? null : row.getFloat("yaw");
            pads.add(new Pad(row.getLong("id"), type, row.getString("world"), row.getInt("x"), row.getInt("y"), row.getInt("z"),
                    row.getDouble("power"), row.getDouble("height"), yaw, target));
        }
        return pads;
    }

    /** Remplace la plaque de ce bloc s'il y en avait une. */
    public void save(Pad pad) {
        database.delete(TABLE, Map.of("world", pad.world(), "x", pad.x(), "y", pad.y(), "z", pad.z()));
        Map<String, Object> values = new HashMap<>();
        values.put("type", pad.type().name());
        values.put("world", pad.world());
        values.put("x", pad.x());
        values.put("y", pad.y());
        values.put("z", pad.z());
        values.put("power", pad.power());
        values.put("height", pad.height());
        if (pad.yaw() != null) {
            values.put("yaw", pad.yaw());
        }
        if (pad.target() != null) {
            values.put("target_world", pad.target().world());
            values.put("tx", pad.target().x());
            values.put("ty", pad.target().y());
            values.put("tz", pad.target().z());
            values.put("tyaw", pad.target().yaw());
            values.put("tpitch", pad.target().pitch());
        }
        database.insert(TABLE, values);
    }

    public boolean remove(String world, int x, int y, int z) {
        return database.delete(TABLE, Map.of("world", world, "x", x, "y", y, "z", z)) > 0;
    }
}
