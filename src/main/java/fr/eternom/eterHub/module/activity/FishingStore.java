package fr.eternom.eterHub.module.activity;

import fr.eternom.eterLib.helper.ranking.RankingSource;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Les prises du lac (eterhub_catches) : une ligne par prise (poisson, rareté, poids en grammes, date). Bloquant.
 * Classements : la plus belle prise de chaque joueur, du jour ou du mois.
 */
public class FishingStore {

    private static final String TABLE = "catches";
    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    /** Une prise d'un classement. */
    public record Ranked(UUID player, String fish, long grams) {
    }

    private final Database database;

    public FishingStore(Database database) {
        this.database = database;
        database.createTable(TABLE,
                Column.of("id", Column.Type.LONG).primaryKey().autoIncrement(),
                Column.of("uuid", Column.Type.UUID).notNull(),
                Column.of("fish", Column.Type.STRING).length(48).notNull(),
                Column.of("rarity", Column.Type.STRING).length(16).notNull(),
                Column.of("grams", Column.Type.LONG).notNull(),
                Column.of("caught_at", Column.Type.LONG).notNull());
    }

    public void add(UUID player, String fish, String rarity, long grams) {
        database.execute("INSERT INTO " + database.table(TABLE) + " (uuid, fish, rarity, grams, caught_at) VALUES (?, ?, ?, ?, ?)",
                player, fish, rarity, grams, System.currentTimeMillis());
    }

    public static long since(boolean month) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate from = month ? today.withDayOfMonth(1) : today;
        return from.atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    /** La plus belle prise de chaque joueur depuis from, de la plus lourde à la plus légère. */
    public List<Ranked> top(long from, int limit) {
        List<Ranked> top = new ArrayList<>();
        String table = database.table(TABLE);
        database.query("SELECT c.uuid, c.fish, c.grams FROM " + table + " c JOIN (SELECT uuid, MAX(grams) AS best FROM " + table
                        + " WHERE caught_at >= ? GROUP BY uuid) b ON b.uuid = c.uuid AND b.best = c.grams WHERE c.caught_at >= ?"
                        + " ORDER BY c.grams DESC LIMIT " + Math.max(1, limit * 2), from, from)
                .forEach(row -> {
                    UUID uuid = row.getUUID("uuid");
                    if (top.size() < limit && top.stream().noneMatch(entry -> entry.player().equals(uuid))) {
                        top.add(new Ranked(uuid, row.getString("fish"), row.getLong("grams")));
                    }
                });
        return top;
    }

    /** Sa plus belle prise depuis toujours (grammes, 0 sans prise) et le nombre d'espèces différentes prises. */
    public long[] personal(UUID player) {
        long best = database.query("SELECT COALESCE(MAX(grams), 0) AS best FROM " + database.table(TABLE) + " WHERE uuid = ?", player)
                .getFirst().getLong("best");
        long species = database.query("SELECT COUNT(DISTINCT fish) AS species FROM " + database.table(TABLE) + " WHERE uuid = ?", player)
                .getFirst().getLong("species");
        return new long[]{best, species};
    }

    /** Classement du mois pour EterLib (« hub_fishing ») : la plus belle prise en grammes. */
    public RankingSource monthly() {
        return new RankingSource() {
            @Override
            public String id() {
                return "hub_fishing";
            }

            @Override
            public List<Ranked> top(int limit) {
                return FishingStore.this.top(since(true), limit).stream()
                        .map(entry -> new RankingSource.Ranked(entry.player(), entry.grams())).toList();
            }

            @Override
            public long[] of(UUID player) {
                List<FishingStore.Ranked> all = FishingStore.this.top(since(true), 500);
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i).player().equals(player)) {
                        return new long[]{i + 1, all.get(i).grams()};
                    }
                }
                return new long[]{0, 0};
            }
        };
    }
}
