package fr.eternom.eterHub.module.core;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ce que les lobbys retiennent d'un joueur (eterhub_players), commun à tous les lobbys : première venue, dernière
 * position au lobby, étape de la visite guidée, dernier serveur joué hors lobby (bouton « Reprendre »).
 * Lu à l'arrivée (événement asynchrone de Paper, avant la connexion) et gardé en mémoire tant qu'il est ici.
 */
public class HubPlayers {

    private static final String TABLE = "players";
    /** Visite guidée finie (ou passée). */
    public static final int TOUR_DONE = -1;

    /** Un joueur connu des lobbys. firstVisit : il vient pour la première fois (ligne créée à l'instant). */
    public static final class Data {
        private final UUID uuid;
        private final boolean firstVisit;
        private volatile Location last;
        private volatile int tourStep;
        private volatile String resume;

        Data(UUID uuid, boolean firstVisit, Location last, int tourStep, String resume) {
            this.uuid = uuid;
            this.firstVisit = firstVisit;
            this.last = last;
            this.tourStep = tourStep;
            this.resume = resume;
        }

        public UUID uuid() {
            return uuid;
        }

        public boolean firstVisit() {
            return firstVisit;
        }

        public Optional<Location> last() {
            return Optional.ofNullable(last).map(Location::clone);
        }

        public int tourStep() {
            return tourStep;
        }

        public Optional<String> resume() {
            return Optional.ofNullable(resume);
        }
    }

    private final JavaPlugin plugin;
    private final Database database;
    private final Map<UUID, Data> online = new ConcurrentHashMap<>();

    public HubPlayers(JavaPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        database.createTable(TABLE,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("first_seen", Column.Type.LONG).notNull(),
                Column.of("world", Column.Type.STRING).length(64),
                Column.of("x", Column.Type.DOUBLE),
                Column.of("y", Column.Type.DOUBLE),
                Column.of("z", Column.Type.DOUBLE),
                Column.of("yaw", Column.Type.FLOAT),
                Column.of("pitch", Column.Type.FLOAT),
                Column.of("tour_step", Column.Type.INT).notNull(),
                Column.of("resume", Column.Type.STRING).length(64));
    }

    /** Lecture à l'arrivée (crée la ligne la première fois). Bloquant : depuis l'événement asynchrone d'arrivée. */
    public Data load(UUID uuid) {
        Optional<Row> row = database.getFirst(TABLE, Map.of("uuid", uuid));
        Data data;
        if (row.isEmpty()) {
            database.insert(TABLE, Map.of("uuid", uuid, "first_seen", System.currentTimeMillis(), "tour_step", 0));
            data = new Data(uuid, true, null, 0, null);
        } else {
            Row found = row.get();
            data = new Data(uuid, false, location(found), found.getInt("tour_step"), found.getString("resume"));
        }
        online.put(uuid, data);
        return data;
    }

    /** Le joueur, s'il est ici (sinon lu à la demande : rare, ex. un rechargement à chaud). */
    public Data of(Player player) {
        Data data = online.get(player.getUniqueId());
        return data != null ? data : new Data(player.getUniqueId(), false, null, TOUR_DONE, null);
    }

    public boolean isLoaded(UUID uuid) {
        return online.containsKey(uuid);
    }

    /** Départ : la position est gardée (retour au même endroit), le joueur oublié. */
    public void quit(Player player) {
        Data data = online.remove(player.getUniqueId());
        if (data == null) {
            return;
        }
        Location at = player.getLocation();
        Map<String, Object> values = new HashMap<>();
        values.put("world", at.getWorld().getName());
        values.put("x", at.getX());
        values.put("y", at.getY());
        values.put("z", at.getZ());
        values.put("yaw", at.getYaw());
        values.put("pitch", at.getPitch());
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, () -> database.update(TABLE, values, Map.of("uuid", uuid)), "Position au lobby non gardée");
    }

    public void setTourStep(Player player, int step) {
        Data data = online.get(player.getUniqueId());
        if (data != null) {
            data.tourStep = step;
        }
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, () -> database.update(TABLE, Map.of("tour_step", step), Map.of("uuid", uuid)), "Visite non gardée");
    }

    /** Le dernier serveur joué hors lobby. Bloquant. */
    public void setResume(UUID uuid, String server) {
        Data data = online.get(uuid);
        if (data != null) {
            data.resume = server;
        }
        database.update(TABLE, Map.of("resume", server), Map.of("uuid", uuid));
    }

    private static Location location(Row row) {
        String worldName = row.getString("world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, row.getDouble("x"), row.getDouble("y"), row.getDouble("z"), row.getFloat("yaw"), row.getFloat("pitch"));
    }
}
