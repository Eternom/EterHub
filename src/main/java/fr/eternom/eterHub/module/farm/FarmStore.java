package fr.eternom.eterHub.module.farm;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Les récoltes des Champs, communes à tous les lobbys. Bloquant.
 * - eterhub_farm_daily : récoltes du jour par culture (le plafond quotidien vaut pour tout le réseau) ;
 * - eterhub_farm_totals : récoltes de toujours par culture et par qualité (carnet des récoltes).
 */
public class FarmStore {

    private static final String DAILY = "farm_daily";
    private static final String TOTALS = "farm_totals";

    private final Database database;

    public FarmStore(Database database) {
        this.database = database;
        database.createTable(DAILY,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("day", Column.Type.STRING).length(10).primaryKey(),
                Column.of("crop", Column.Type.STRING).length(32).primaryKey(),
                Column.of("amount", Column.Type.INT).notNull());
        database.createTable(TOTALS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("crop", Column.Type.STRING).length(32).primaryKey(),
                Column.of("quality", Column.Type.STRING).length(16).primaryKey(),
                Column.of("amount", Column.Type.LONG).notNull());
    }

    /** Récoltes de ce jour : culture -> nombre. */
    public Map<String, Integer> today(UUID player, String day) {
        Map<String, Integer> counts = new HashMap<>();
        database.get(DAILY, Map.of("uuid", player, "day", day)).forEach(row -> counts.put(row.getString("crop"), row.getInt("amount")));
        return counts;
    }

    /** Récoltes de toujours : culture -> {normales, belles, dorées}. */
    public Map<String, long[]> totals(UUID player) {
        Map<String, long[]> totals = new HashMap<>();
        database.get(TOTALS, Map.of("uuid", player)).forEach(row -> {
            long[] counts = totals.computeIfAbsent(row.getString("crop"), key -> new long[3]);
            counts[Quality.parse(row.getString("quality")).ordinal()] += row.getLong("amount");
        });
        return totals;
    }

    public void add(UUID player, String day, String crop, Quality quality) {
        database.execute("INSERT INTO " + database.table(DAILY) + " (uuid, day, crop, amount) VALUES (?, ?, ?, 1)"
                + " ON DUPLICATE KEY UPDATE amount = amount + 1", player, day, crop);
        database.execute("INSERT INTO " + database.table(TOTALS) + " (uuid, crop, quality, amount) VALUES (?, ?, ?, 1)"
                + " ON DUPLICATE KEY UPDATE amount = amount + 1", player, crop, quality.key());
    }

    /** Ménage : les jours de plus d'une semaine. */
    public void purge(String before) {
        database.execute("DELETE FROM " + database.table(DAILY) + " WHERE day < ?", before);
    }
}
