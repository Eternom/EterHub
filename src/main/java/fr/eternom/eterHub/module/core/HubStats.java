package fr.eternom.eterHub.module.core;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Statistiques des lobbys, par jour (eterhub_stats) : arrivées, premières arrivées, zones visitées, activités jouées.
 * Comptées en mémoire et écrites toutes les minutes (une ligne par jour et par compteur, additionnée par tous les
 * lobbys). /eterhub stats les montre.
 */
public class HubStats {

    private static final String TABLE = "stats";
    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    private final JavaPlugin plugin;
    private final Database database;
    private final Map<String, AtomicLong> pending = new ConcurrentHashMap<>();

    public HubStats(JavaPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        database.createTable(TABLE,
                Column.of("day", Column.Type.STRING).length(10).primaryKey(),
                Column.of("name", Column.Type.STRING).length(80).primaryKey(),
                Column.of("total", Column.Type.LONG).notNull());
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flush, 20 * 60, 20 * 60);
    }

    /** Un de plus (thread quelconque). Ex : « join », « zone:lac », « fishing ». */
    public void add(String name) {
        pending.computeIfAbsent(name.length() > 80 ? name.substring(0, 80) : name, key -> new AtomicLong()).incrementAndGet();
    }

    /** Écrit les compteurs (arrêt, ou toutes les minutes). Bloquant. */
    public void flush() {
        if (pending.isEmpty()) {
            return;
        }
        String day = LocalDate.now(ZONE).toString();
        Map<String, Long> batch = new HashMap<>();
        pending.forEach((name, count) -> {
            long value = count.getAndSet(0);
            if (value > 0) {
                batch.put(name, value);
            }
        });
        pending.values().removeIf(count -> count.get() == 0);
        String sql = "INSERT INTO " + database.table(TABLE) + " (day, name, total) VALUES (?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE total = total + VALUES(total)";
        batch.forEach((name, value) -> {
            try {
                database.execute(sql, day, name, value);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Statistique " + name + " non écrite : " + e.getMessage());
            }
        });
    }

    /** Les compteurs des derniers jours (aujourd'hui compris), du plus grand au plus petit. Bloquant. */
    public Map<String, Long> totals(int days, String prefix) {
        String from = LocalDate.now(ZONE).minusDays(Math.max(0, days - 1)).toString();
        Map<String, Long> totals = new LinkedHashMap<>();
        database.query("SELECT name, SUM(total) AS total FROM " + database.table(TABLE)
                        + " WHERE day >= ? AND name LIKE ? GROUP BY name ORDER BY total DESC LIMIT 30", from, prefix + "%")
                .forEach(row -> totals.put(row.getString("name"), row.getLong("total")));
        return totals;
    }
}
