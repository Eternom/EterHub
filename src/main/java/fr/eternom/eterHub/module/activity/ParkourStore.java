package fr.eternom.eterHub.module.activity;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Les parcours (eterhub_parkour_points : départ, points de passage dans l'ordre, arrivée, posés en jeu) et les temps
 * (eterhub_parkour_times : meilleur temps et nombre d'arrivées par joueur). Communs à tous les lobbys. Bloquant.
 */
public class ParkourStore {

    private static final String POINTS = "parkour_points";
    private static final String TIMES = "parkour_times";

    /** Un point : 0 = départ, 1..n = passages, -1 = arrivée. */
    public record Point(String course, int index, String world, int x, int y, int z) {
        public String key() {
            return world + ":" + x + ":" + y + ":" + z;
        }
    }

    /** Un parcours : départ, passages dans l'ordre, arrivée. */
    public record Course(String id, Point start, List<Point> checkpoints, Point finish) {
        public boolean complete() {
            return start != null && finish != null;
        }
    }

    public record Time(UUID player, long best) {
    }

    private final Database database;

    public ParkourStore(Database database) {
        this.database = database;
        database.createTable(POINTS,
                Column.of("course", Column.Type.STRING).length(32).primaryKey(),
                Column.of("idx", Column.Type.INT).primaryKey(),
                Column.of("world", Column.Type.STRING).length(64).notNull(),
                Column.of("x", Column.Type.INT).notNull(),
                Column.of("y", Column.Type.INT).notNull(),
                Column.of("z", Column.Type.INT).notNull());
        database.createTable(TIMES,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("course", Column.Type.STRING).length(32).primaryKey(),
                Column.of("best_ms", Column.Type.LONG).notNull(),
                Column.of("runs", Column.Type.INT).notNull(),
                Column.of("first_at", Column.Type.LONG).notNull());
    }

    public Map<String, Course> courses() {
        Map<String, List<Point>> byCourse = new LinkedHashMap<>();
        for (Row row : database.query("SELECT * FROM " + database.table(POINTS) + " ORDER BY course, idx")) {
            Point point = new Point(row.getString("course"), row.getInt("idx"), row.getString("world"),
                    row.getInt("x"), row.getInt("y"), row.getInt("z"));
            byCourse.computeIfAbsent(point.course(), key -> new ArrayList<>()).add(point);
        }
        Map<String, Course> courses = new LinkedHashMap<>();
        byCourse.forEach((id, points) -> {
            Point start = null;
            Point finish = null;
            List<Point> checkpoints = new ArrayList<>();
            for (Point point : points) {
                if (point.index() == 0) {
                    start = point;
                } else if (point.index() < 0) {
                    finish = point;
                } else {
                    checkpoints.add(point);
                }
            }
            courses.put(id, new Course(id, start, List.copyOf(checkpoints), finish));
        });
        return courses;
    }

    /** index : 0 départ, -1 arrivée, sinon le prochain passage (renvoyé). */
    public int setPoint(String course, int index, String world, int x, int y, int z) {
        int idx = index;
        if (index > 0) {
            idx = database.query("SELECT COALESCE(MAX(idx), 0) + 1 AS next FROM " + database.table(POINTS) + " WHERE course = ?", course)
                    .getFirst().getInt("next");
        }
        database.set(POINTS, Map.of("course", course, "idx", idx, "world", world, "x", x, "y", y, "z", z), "course", "idx");
        return idx;
    }

    /** Retire le dernier passage ; false s'il n'y en a pas. */
    public boolean removeLastCheckpoint(String course) {
        return database.execute("DELETE FROM " + database.table(POINTS) + " WHERE course = ? AND idx = (SELECT m FROM (SELECT MAX(idx) AS m FROM "
                + database.table(POINTS) + " WHERE course = ? AND idx > 0) t)", course, course) > 0;
    }

    public boolean delete(String course) {
        database.delete(TIMES, Map.of("course", course));
        return database.delete(POINTS, Map.of("course", course)) > 0;
    }

    /** Meilleur temps (0 sans) et nombre d'arrivées. */
    public long[] personal(UUID player, String course) {
        return database.getFirst(TIMES, Map.of("uuid", player, "course", course))
                .map(row -> new long[]{row.getLong("best_ms"), row.getInt("runs")}).orElse(new long[]{0, 0});
    }

    /** Une arrivée : garde le meilleur temps. */
    public void finish(UUID player, String course, long time) {
        database.execute("INSERT INTO " + database.table(TIMES) + " (uuid, course, best_ms, runs, first_at) VALUES (?, ?, ?, 1, ?)"
                + " ON DUPLICATE KEY UPDATE best_ms = LEAST(best_ms, VALUES(best_ms)), runs = runs + 1",
                player, course, time, System.currentTimeMillis());
    }

    public List<Time> top(String course, int limit) {
        List<Time> top = new ArrayList<>();
        database.query("SELECT uuid, best_ms FROM " + database.table(TIMES) + " WHERE course = ? ORDER BY best_ms ASC LIMIT "
                + Math.max(1, limit), course).forEach(row -> top.add(new Time(row.getUUID("uuid"), row.getLong("best_ms"))));
        return top;
    }

    /** Place du joueur (1 = premier), 0 sans temps. */
    public int rank(UUID player, String course) {
        long best = personal(player, course)[0];
        if (best <= 0) {
            return 0;
        }
        return database.query("SELECT COUNT(*) AS better FROM " + database.table(TIMES) + " WHERE course = ? AND best_ms < ?", course, best)
                .getFirst().getInt("better") + 1;
    }
}
