package fr.eternom.eterHub.module.heads;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Les têtes cachées et les trouvailles, communes à tous les lobbys. Bloquant.
 * - eterhub_heads : position, chasse, nom, rare, et de quoi la reposer (bloc et texture) si sa chasse la cache ;
 * - eterhub_heads_found : qui a trouvé quelle tête, et quand ;
 * - eterhub_heads_finished : qui a fini quelle chasse, et quand (le rang d'arrivée, la récompense finale une seule fois).
 */
public class HeadStore {

    private static final String HEADS = "heads";
    private static final String FOUND = "heads_found";
    private static final String FINISHED = "heads_finished";

    /** Une tête de la chasse. */
    public record Head(long id, String world, int x, int y, int z, String hunt, String name, boolean rare, String blockData,
                       String texture, String signature) {
        public String key() {
            return world + ":" + x + ":" + y + ":" + z;
        }
    }

    public record Ranked(UUID player, long value) {
    }

    private final Database database;

    public HeadStore(Database database) {
        this.database = database;
        database.createTable(HEADS,
                Column.of("id", Column.Type.LONG).primaryKey().autoIncrement(),
                Column.of("world", Column.Type.STRING).length(64).notNull(),
                Column.of("x", Column.Type.INT).notNull(),
                Column.of("y", Column.Type.INT).notNull(),
                Column.of("z", Column.Type.INT).notNull());
        // Depuis 2.4.0
        database.addColumn(HEADS, Column.of("hunt", Column.Type.STRING).length(32));
        database.addColumn(HEADS, Column.of("name", Column.Type.STRING).length(64));
        database.addColumn(HEADS, Column.of("rare", Column.Type.BOOLEAN));
        database.addColumn(HEADS, Column.of("block_data", Column.Type.STRING).length(255));
        database.addColumn(HEADS, Column.of("texture", Column.Type.TEXT));
        database.addColumn(HEADS, Column.of("signature", Column.Type.TEXT));
        database.createTable(FOUND,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("head", Column.Type.LONG).primaryKey(),
                Column.of("found_at", Column.Type.LONG).notNull());
        database.createTable(FINISHED,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("hunt", Column.Type.STRING).length(32).primaryKey(),
                Column.of("finished_at", Column.Type.LONG).notNull());
    }

    public List<Head> all() {
        List<Head> heads = new ArrayList<>();
        for (Row row : database.get(HEADS, Map.of())) {
            String hunt = row.getString("hunt");
            Object rare = row.get("rare");
            heads.add(new Head(row.getLong("id"), row.getString("world"), row.getInt("x"), row.getInt("y"), row.getInt("z"),
                    hunt == null || hunt.isBlank() ? Hunt.MAIN : hunt, row.getString("name"),
                    rare != null && row.getBoolean("rare"), row.getString("block_data"), row.getString("texture"), row.getString("signature")));
        }
        return heads;
    }

    public boolean exists(String world, int x, int y, int z) {
        return database.getFirst(HEADS, Map.of("world", world, "x", x, "y", y, "z", z)).isPresent();
    }

    public long add(Head head) {
        Map<String, Object> values = new HashMap<>(Map.of("world", head.world(), "x", head.x(), "y", head.y(), "z", head.z(),
                "hunt", head.hunt(), "rare", head.rare()));
        if (head.name() != null) {
            values.put("name", head.name());
        }
        if (head.blockData() != null) {
            values.put("block_data", head.blockData());
        }
        if (head.texture() != null) {
            values.put("texture", head.texture());
        }
        if (head.signature() != null) {
            values.put("signature", head.signature());
        }
        return database.insertReturningId(HEADS, values);
    }

    public boolean remove(long id) {
        database.delete(FOUND, Map.of("head", id));
        return database.delete(HEADS, Map.of("id", id)) > 0;
    }

    public void setName(long id, String name) {
        database.update(HEADS, Map.of("name", name), Map.of("id", id));
    }

    public void setRare(long id, boolean rare) {
        database.update(HEADS, Map.of("rare", rare), Map.of("id", id));
    }

    public void setHunt(long id, String hunt) {
        database.update(HEADS, Map.of("hunt", hunt), Map.of("id", id));
    }

    // ---------- Trouvailles ----------

    /** Têtes trouvées par un joueur : tête -> date. */
    public Map<Long, Long> found(UUID player) {
        Map<Long, Long> found = new HashMap<>();
        database.get(FOUND, Map.of("uuid", player)).forEach(row -> found.put(row.getLong("head"), row.getLong("found_at")));
        return found;
    }

    public void markFound(UUID player, long head, long at) {
        database.execute("INSERT IGNORE INTO " + database.table(FOUND) + " (uuid, head, found_at) VALUES (?, ?, ?)", player, head, at);
    }

    /** Combien de joueurs ont trouvé chaque tête (liste du staff). */
    public Map<Long, Integer> foundCounts() {
        Map<Long, Integer> counts = new HashMap<>();
        database.query("SELECT head, COUNT(*) AS total FROM " + database.table(FOUND) + " GROUP BY head")
                .forEach(row -> counts.put(row.getLong("head"), row.getInt("total")));
        return counts;
    }

    /** Combien de joueurs participent (ont trouvé au moins une tête). */
    public int hunters() {
        return database.query("SELECT COUNT(DISTINCT uuid) AS total FROM " + database.table(FOUND)).getFirst().getInt("total");
    }

    // ---------- Chasses finies ----------

    public Set<String> finished(UUID player) {
        Set<String> hunts = new HashSet<>();
        database.get(FINISHED, Map.of("uuid", player)).forEach(row -> hunts.add(row.getString("hunt")));
        return hunts;
    }

    /** Note la fin de la chasse ; renvoie le rang d'arrivée (1 = premier), 0 si elle était déjà notée. */
    public int finish(UUID player, String hunt) {
        long now = System.currentTimeMillis();
        int inserted = database.execute("INSERT IGNORE INTO " + database.table(FINISHED) + " (uuid, hunt, finished_at) VALUES (?, ?, ?)",
                player, hunt, now);
        if (inserted == 0) {
            return 0;
        }
        return database.query("SELECT COUNT(*) AS total FROM " + database.table(FINISHED) + " WHERE hunt = ? AND finished_at <= ?", hunt, now)
                .getFirst().getInt("total");
    }

    // ---------- Classements ----------

    /** Ceux qui ont trouvé le plus de têtes (encore là) d'une chasse. */
    public List<Ranked> topFound(String hunt, int limit) {
        List<Ranked> top = new ArrayList<>();
        database.query("SELECT f.uuid, COUNT(*) AS total FROM " + database.table(FOUND) + " f JOIN " + database.table(HEADS)
                        + " h ON h.id = f.head WHERE COALESCE(h.hunt, 'main') = ? GROUP BY f.uuid ORDER BY total DESC, MAX(f.found_at) ASC LIMIT "
                        + Math.max(1, limit), hunt)
                .forEach(row -> top.add(new Ranked(row.getUUID("uuid"), row.getLong("total"))));
        return top;
    }

    /** Les premiers à avoir fini une chasse, dans l'ordre (valeur : rang d'arrivée). */
    public List<Ranked> finishers(String hunt, int limit) {
        List<Ranked> top = new ArrayList<>();
        List<Row> rows = database.query("SELECT uuid FROM " + database.table(FINISHED) + " WHERE hunt = ? ORDER BY finished_at ASC LIMIT "
                + Math.max(1, limit), hunt);
        for (int i = 0; i < rows.size(); i++) {
            top.add(new Ranked(rows.get(i).getUUID("uuid"), i + 1));
        }
        return top;
    }
}
