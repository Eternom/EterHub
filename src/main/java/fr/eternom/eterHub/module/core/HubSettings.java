package fr.eternom.eterHub.module.core;

import com.google.gson.JsonObject;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Réglages communs à tous les lobbys, modifiables en jeu (/eterhub setting) : un réglage simple de config.yml (oui/non,
 * nombre, texte) sous l'une des sections EDITABLE peut être remplacé en base (eterhub_settings). La valeur de la base
 * l'emporte sur config.yml, sur TOUS les lobbys (bus réseau) : un nouveau lobby n'a rien à régler. Chaque changement est
 * noté dans eterhub_settings_log (qui, quand, avant, après). Les modules lisent le réglage à chaque usage : un changement
 * s'applique tout de suite, sans redémarrage.
 */
public class HubSettings {

    /** Sections de config.yml dont les réglages simples se changent en jeu. */
    public static final List<String> EDITABLE = List.of("activities", "arrival", "double-jump", "launch-pads", "world",
            "tips", "cosmetics", "social", "fishing", "farming", "archaeology", "parkour", "heads", "tour", "discovery", "spawn-command");

    private static final String TABLE = "settings";
    private static final String LOG = "settings_log";
    private static final String CHANGED = "settings";

    private final JavaPlugin plugin;
    private final Database database;
    private final NetworkBus bus;
    private final Map<String, String> overrides = new ConcurrentHashMap<>();

    public HubSettings(JavaPlugin plugin, Database database, NetworkBus bus) {
        this.plugin = plugin;
        this.database = database;
        this.bus = bus;
        database.createTable(TABLE,
                Column.of("path", Column.Type.STRING).length(96).primaryKey(),
                Column.of("value", Column.Type.STRING).length(255).notNull());
        database.createTable(LOG,
                Column.of("id", Column.Type.LONG).primaryKey().autoIncrement(),
                Column.of("path", Column.Type.STRING).length(96).notNull(),
                Column.of("old_value", Column.Type.STRING).length(255),
                Column.of("new_value", Column.Type.STRING).length(255),
                Column.of("author", Column.Type.STRING).length(32).notNull(),
                Column.of("changed_at", Column.Type.LONG).notNull());
        bus.on(CHANGED, data -> Tasks.async(plugin, this::load, "Réglages des lobbys non relus"));
        load();
    }

    /** Relit les réglages en base. Bloquant (démarrage, ou en tâche de fond). */
    public void load() {
        Map<String, String> fresh = new TreeMap<>();
        database.get(TABLE, Map.of()).forEach(row -> fresh.put(row.getString("path"), row.getString("value")));
        overrides.clear();
        overrides.putAll(fresh);
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }

    public boolean bool(String path, boolean fallback) {
        String value = overrides.get(path);
        return value != null ? Boolean.parseBoolean(value) : config().getBoolean(path, fallback);
    }

    public double number(String path, double fallback) {
        String value = overrides.get(path);
        if (value != null) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                // valeur abîmée : celle de config.yml
            }
        }
        return config().getDouble(path, fallback);
    }

    public int integer(String path, int fallback) {
        return (int) Math.round(number(path, fallback));
    }

    public String text(String path, String fallback) {
        String value = overrides.get(path);
        return value != null ? value : config().getString(path, fallback);
    }

    /** Un réglage modifiable en jeu : sous EDITABLE, simple (pas une liste ni une section). */
    public boolean editable(String path) {
        if (EDITABLE.stream().noneMatch(root -> path.equals(root) || path.startsWith(root + "."))) {
            return false;
        }
        Object value = config().get(path);
        return value instanceof Boolean || value instanceof Number || value instanceof String;
    }

    /** Tous les réglages modifiables, avec leur valeur actuelle (et * si changée en jeu). */
    public Map<String, String> list() {
        Map<String, String> all = new TreeMap<>();
        for (String root : EDITABLE) {
            ConfigurationSection section = config().getConfigurationSection(root);
            if (section == null) {
                continue;
            }
            for (String key : section.getKeys(true)) {
                String path = root + "." + key;
                if (editable(path)) {
                    all.put(path, current(path) + (overrides.containsKey(path) ? " *" : ""));
                }
            }
        }
        return all;
    }

    public String current(String path) {
        String value = overrides.get(path);
        return value != null ? value : String.valueOf(config().get(path));
    }

    /**
     * Change un réglage pour tous les lobbys. Renvoie la valeur normalisée, ou vide si la valeur n'a pas le type du
     * réglage (oui/non, nombre). Thread principal ; base en tâche de fond.
     */
    public Optional<String> set(String path, String raw, String author) {
        Object model = config().get(path);
        String value;
        if (model instanceof Boolean) {
            String lower = raw.toLowerCase(Locale.ROOT);
            if (List.of("true", "oui", "on", "yes").contains(lower)) {
                value = "true";
            } else if (List.of("false", "non", "off", "no").contains(lower)) {
                value = "false";
            } else {
                return Optional.empty();
            }
        } else if (model instanceof Number) {
            try {
                value = String.valueOf(Double.parseDouble(raw.replace(',', '.')));
                if (model instanceof Integer || model instanceof Long) {
                    value = String.valueOf(Math.round(Double.parseDouble(value)));
                }
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        } else {
            value = raw;
        }
        String old = current(path);
        overrides.put(path, value);
        String stored = value;
        Tasks.async(plugin, () -> {
            database.set(TABLE, Map.of("path", path, "value", stored), "path");
            log(path, old, stored, author);
            bus.publish(CHANGED, new JsonObject());
        }, "Réglage " + path + " non enregistré");
        return Optional.of(value);
    }

    /** Revient à la valeur de config.yml. */
    public void reset(String path, String author) {
        String old = current(path);
        overrides.remove(path);
        Tasks.async(plugin, () -> {
            database.delete(TABLE, Map.of("path", path));
            log(path, old, null, author);
            bus.publish(CHANGED, new JsonObject());
        }, "Réglage " + path + " non remis à zéro");
    }

    /** Journal (point 98) : aussi les changements des zones, plaques, hologrammes... (path = « zone:spawn »). */
    public void log(String path, String oldValue, String newValue, String author) {
        database.insert(LOG, nullable(Map.of("path", path, "author", author, "changed_at", System.currentTimeMillis()),
                oldValue, newValue));
    }

    private static Map<String, Object> nullable(Map<String, Object> base, String oldValue, String newValue) {
        Map<String, Object> values = new java.util.HashMap<>(base);
        if (oldValue != null) {
            values.put("old_value", cut(oldValue));
        }
        if (newValue != null) {
            values.put("new_value", cut(newValue));
        }
        return values;
    }

    private static String cut(String value) {
        return value.length() > 255 ? value.substring(0, 255) : value;
    }

    /** Les derniers changements, du plus récent au plus ancien. Bloquant. */
    public List<String[]> history(int limit) {
        List<String[]> lines = new ArrayList<>();
        database.query("SELECT path, old_value, new_value, author, changed_at FROM " + database.table(LOG)
                + " ORDER BY id DESC LIMIT " + Math.max(1, limit)).forEach(row -> lines.add(new String[]{
                row.getString("path"), String.valueOf(row.getString("old_value")), String.valueOf(row.getString("new_value")),
                row.getString("author"), String.valueOf(row.getLong("changed_at"))}));
        return lines;
    }
}
