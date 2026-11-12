package fr.eternom.eterHub.module.display;

import com.google.gson.JsonObject;
import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.SubCommand;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.ranking.RankingSource;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;
import fr.eternom.eterLib.helper.task.Tasks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Les hologrammes du village (TextDisplay), communs à tous les lobbys (eterhub_holograms) et posés en jeu :
 * - text : des lignes libres (MiniMessage) : tableau des nouveautés, des règles, panneaux « bientôt » ;
 * - players <serveur> : joueurs en ligne sur ce serveur, au-dessus de son portail ;
 * - board <classement> [time|grams] : les 10 premiers d'un classement d'EterLib (success, playtime_total,
 *   monthly_general, clans, hub_fishing, et ceux d'autres plugins...), relu chaque minute (tableaux d'honneur) ;
 * - progress : la progression de chaque joueur, vue par lui seul (lieux découverts, têtes, musée...).
 * La première ligne d'un players / board / progress est son titre. Les entités ne sont pas gardées dans le monde :
 * recréées à chaque démarrage. Éditeur : /eterhub holo.
 */
public class Holograms implements Listener, SubCommand {

    private static final String TABLE = "holograms";
    private static final String CHANGED = "holograms";

    public enum Type { TEXT, PLAYERS, BOARD, PROGRESS }

    public record Hologram(String id, Type type, String argument, List<String> lines, String world, double x, double y, double z) {
    }

    private final JavaPlugin plugin;
    private final Database database;
    private final HubSettings settings;
    private final Messages messages;
    private final NetworkBus bus;
    private final Function<Player, List<Component>> progress;
    private volatile Map<String, Hologram> holograms = Map.of();
    private final Map<String, TextDisplay> shared = new HashMap<>();
    /** Hologrammes « progress » : un par joueur et par hologramme. */
    private final Map<String, Map<UUID, TextDisplay>> personal = new HashMap<>();
    private final Map<String, Component> boards = new ConcurrentHashMap<>();

    public Holograms(JavaPlugin plugin, Database database, HubSettings settings, Messages messages, NetworkBus bus,
                     Function<Player, List<Component>> progress) {
        this.plugin = plugin;
        this.database = database;
        this.settings = settings;
        this.messages = messages;
        this.bus = bus;
        this.progress = progress;
        database.createTable(TABLE,
                Column.of("id", Column.Type.STRING).length(32).primaryKey(),
                Column.of("type", Column.Type.STRING).length(16).notNull(),
                Column.of("argument", Column.Type.STRING).length(64),
                Column.of("lines", Column.Type.TEXT),
                Column.of("world", Column.Type.STRING).length(64).notNull(),
                Column.of("x", Column.Type.DOUBLE).notNull(),
                Column.of("y", Column.Type.DOUBLE).notNull(),
                Column.of("z", Column.Type.DOUBLE).notNull());
        bus.on(CHANGED, data -> reload());
    }

    public void start() {
        reload();
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshLive, 40, 100);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshBoards, 60, 20 * 60);
    }

    public void stop() {
        shared.values().forEach(TextDisplay::remove);
        shared.clear();
        personal.values().forEach(map -> map.values().forEach(TextDisplay::remove));
        personal.clear();
    }

    public void reload() {
        Tasks.async(plugin, () -> {
            Map<String, Hologram> fresh = new HashMap<>();
            for (Row row : database.get(TABLE, Map.of())) {
                try {
                    String lines = row.getString("lines");
                    fresh.put(row.getString("id"), new Hologram(row.getString("id"), Type.valueOf(row.getString("type")),
                            row.getString("argument"), lines == null || lines.isEmpty() ? List.of() : List.of(lines.split("\n", -1)),
                            row.getString("world"), row.getDouble("x"), row.getDouble("y"), row.getDouble("z")));
                } catch (IllegalArgumentException ignored) {
                    // type inconnu : ignoré
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                holograms = fresh;
                stop();
                fresh.values().forEach(this::spawn);
                Bukkit.getScheduler().runTaskAsynchronously(plugin, this::refreshBoards);
            });
        }, "Hologrammes non relus");
    }

    private void changed() {
        reload();
        Tasks.async(plugin, () -> bus.publish(CHANGED, new JsonObject()), "Hologrammes non annoncés");
    }

    private Location location(Hologram hologram) {
        World world = Bukkit.getWorld(hologram.world());
        return world == null ? null : new Location(world, hologram.x(), hologram.y(), hologram.z());
    }

    private TextDisplay create(Location at, boolean visibleToAll) {
        return at.getWorld().spawn(at, TextDisplay.class, display -> {
            display.setPersistent(false);
            display.setBillboard(Display.Billboard.CENTER);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setShadowed(true);
            display.setLineWidth(260);
            display.setVisibleByDefault(visibleToAll);
            display.text(Component.empty());
        });
    }

    private void spawn(Hologram hologram) {
        Location at = location(hologram);
        if (at == null || hologram.type() == Type.PROGRESS) {
            if (at != null) {
                Bukkit.getOnlinePlayers().forEach(player -> showProgress(hologram, player));
            }
            return;
        }
        TextDisplay display = create(at, true);
        shared.put(hologram.id(), display);
        display.text(render(hologram));
    }

    private Component render(Hologram hologram) {
        List<Component> lines = new ArrayList<>();
        switch (hologram.type()) {
            case TEXT -> hologram.lines().forEach(line -> lines.add(line(line)));
            case PLAYERS -> {
                if (!hologram.lines().isEmpty()) {
                    lines.add(line(hologram.lines().getFirst()));
                }
                String server = hologram.argument() == null ? "" : hologram.argument();
                int count = server.equals(EterLib.get().getServerName()) ? Bukkit.getOnlinePlayers().size()
                        : EterLib.get().getPlayers().countByServer().getOrDefault(server, 0);
                boolean online = server.equals(EterLib.get().getServerName()) || EterLib.get().getServers().isOnline(server);
                lines.add(messages.render(raw(online ? "hologram.players" : "hologram.offline", "<count> joueurs"),
                        TagResolver.empty(), "count", String.valueOf(count), "server", EterLib.get().getServerDisplayName(server)));
            }
            case BOARD -> {
                if (!hologram.lines().isEmpty()) {
                    lines.add(line(hologram.lines().getFirst()));
                }
                lines.add(boards.getOrDefault(hologram.id(), messages.render(raw("hologram.loading", "..."), TagResolver.empty())));
            }
            default -> {
            }
        }
        return Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines);
    }

    private String raw(String key, String fallback) {
        String raw = messages.raw(Bukkit.getConsoleSender(), key);
        return raw != null ? raw : fallback;
    }

    private Component line(String text) {
        return messages.render(text, TagResolver.empty());
    }

    /** Toutes les 5 s : joueurs par serveur (shared) et progression (personnel). */
    private void refreshLive() {
        for (Hologram hologram : holograms.values()) {
            if (hologram.type() != Type.PROGRESS) {
                TextDisplay display = shared.get(hologram.id());
                if (display == null || !display.isValid()) {
                    // Pas gardé dans le monde : disparu avec son chunk déchargé, il revient quand le chunk est là
                    Location at = location(hologram);
                    if (at != null && at.isChunkLoaded()) {
                        spawn(hologram);
                    }
                } else if (hologram.type() != Type.TEXT) {
                    display.text(render(hologram));
                }
            } else {
                Bukkit.getOnlinePlayers().forEach(player -> showProgress(hologram, player));
            }
        }
    }

    /** Chaque minute, en tâche de fond : les classements des tableaux. */
    private void refreshBoards() {
        for (Hologram hologram : holograms.values()) {
            if (hologram.type() != Type.BOARD || hologram.argument() == null) {
                continue;
            }
            String[] parts = hologram.argument().split(" ");
            String format = parts.length > 1 ? parts[1] : "number";
            Component text = RankingSource.find(parts[0]).map(source -> {
                List<RankingSource.Ranked> top;
                try {
                    top = source.top(Math.max(1, settings.integer("holograms.board-size", 10)));
                } catch (RuntimeException e) {
                    return Component.text("?");
                }
                List<Component> lines = new ArrayList<>();
                for (int i = 0; i < top.size(); i++) {
                    RankingSource.Ranked entry = top.get(i);
                    String label = source.label(entry);
                    String name = label != null ? label : EterLib.get().getPlayers().get(entry.player()).map(found -> found.name()).orElse("?");
                    lines.add(messages.render(raw("hologram.board-line", "<rank>. <player> <value>"), TagResolver.empty(),
                            "rank", String.valueOf(i + 1), "player", name, "value", value(format, entry.value())));
                }
                if (lines.isEmpty()) {
                    lines.add(messages.render(raw("hologram.board-empty", "Personne encore"), TagResolver.empty()));
                }
                return Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines);
            }).orElse(messages.render(raw("hologram.board-missing", "Classement indisponible ici"), TagResolver.empty()));
            boards.put(hologram.id(), text);
        }
    }

    private static String value(String format, long value) {
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "time" -> fr.eternom.eterHub.module.display.TimeFormat.format(value);
            case "grams" -> String.format(Locale.FRANCE, "%.2f kg", value / 1000.0);
            case "minutes" -> value / 60 + " h " + value % 60 + " min";
            default -> String.format(Locale.FRANCE, "%,d", value);
        };
    }

    private void showProgress(Hologram hologram, Player player) {
        Location at = location(hologram);
        if (at == null || !player.getWorld().equals(at.getWorld())) {
            return;
        }
        Map<UUID, TextDisplay> displays = personal.computeIfAbsent(hologram.id(), key -> new HashMap<>());
        TextDisplay display = displays.get(player.getUniqueId());
        if (display == null || !display.isValid()) {
            display = create(at, false);
            displays.put(player.getUniqueId(), display);
            player.showEntity(plugin, display);
        }
        List<Component> lines = new ArrayList<>();
        if (!hologram.lines().isEmpty()) {
            lines.add(line(hologram.lines().getFirst()));
        }
        lines.addAll(progress.apply(player));
        display.text(Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                holograms.values().stream().filter(hologram -> hologram.type() == Type.PROGRESS)
                        .forEach(hologram -> showProgress(hologram, player));
            }
        }, 40);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        personal.values().forEach(map -> {
            TextDisplay display = map.remove(event.getPlayer().getUniqueId());
            if (display != null) {
                display.remove();
            }
        });
    }

    // ---------- Éditeur : /eterhub holo ----------

    @Override
    public void run(Player player, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
        if (sub.equals("list")) {
            messages.send(player, "editor.holo.list", "count", String.valueOf(holograms.size()));
            holograms.values().forEach(hologram -> messages.send(player, "editor.holo.line", "id", hologram.id(),
                    "type", hologram.type().name().toLowerCase(Locale.ROOT), "argument", hologram.argument() == null ? "" : hologram.argument(),
                    "x", String.valueOf(Math.round(hologram.x())), "y", String.valueOf(Math.round(hologram.y())),
                    "z", String.valueOf(Math.round(hologram.z()))));
            return;
        }
        if (args.length < 2) {
            messages.send(player, "editor.holo.usage");
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        Hologram current = holograms.get(id);
        Location at = player.getLocation().add(0, 1.8, 0);
        String author = player.getName();
        switch (sub) {
            case "create" -> {
                Type type;
                try {
                    type = args.length > 2 ? Type.valueOf(args[2].toUpperCase(Locale.ROOT)) : Type.TEXT;
                } catch (IllegalArgumentException e) {
                    messages.send(player, "editor.holo.usage");
                    return;
                }
                if (!id.matches("[a-z0-9_-]{1,32}") || current != null) {
                    messages.send(player, "editor.holo.exists", "id", id);
                    return;
                }
                String argument = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : null;
                List<String> lines = List.of(messages.raw(player, "hologram.default-" + type.name().toLowerCase(Locale.ROOT)) != null
                        ? messages.raw(player, "hologram.default-" + type.name().toLowerCase(Locale.ROOT)) : "<accent>" + id);
                save(player, new Hologram(id, type, argument, lines, at.getWorld().getName(), at.getX(), at.getY(), at.getZ()), author, "créé");
            }
            case "addline", "setline", "removeline", "move", "delete" -> {
                if (current == null) {
                    messages.send(player, "editor.holo.unknown", "id", id);
                    return;
                }
                List<String> lines = new ArrayList<>(current.lines());
                switch (sub) {
                    case "addline" -> lines.add(args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "");
                    case "setline", "removeline" -> {
                        int index;
                        try {
                            index = Integer.parseInt(args.length > 2 ? args[2] : "") - 1;
                        } catch (NumberFormatException e) {
                            messages.send(player, "editor.holo.usage");
                            return;
                        }
                        if (index < 0 || index >= lines.size()) {
                            messages.send(player, "editor.holo.bad-line", "count", String.valueOf(lines.size()));
                            return;
                        }
                        if (sub.equals("setline")) {
                            lines.set(index, args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "");
                        } else {
                            lines.remove(index);
                        }
                    }
                    case "move" -> {
                        save(player, new Hologram(id, current.type(), current.argument(), lines, at.getWorld().getName(), at.getX(), at.getY(), at.getZ()),
                                author, "déplacé");
                        return;
                    }
                    default -> {
                        Tasks.async(plugin, player, () -> {
                            database.delete(TABLE, Map.of("id", id));
                            settings.log("holo:" + id, "hologramme", null, author);
                            return true;
                        }, done -> {
                            messages.send(player, "editor.holo.deleted", "id", id);
                            changed();
                        }, () -> messages.send(player, "error.generic"));
                        return;
                    }
                }
                save(player, new Hologram(id, current.type(), current.argument(), lines, current.world(), current.x(), current.y(), current.z()),
                        author, sub);
            }
            default -> messages.send(player, "editor.holo.usage");
        }
    }

    private void save(Player player, Hologram hologram, String author, String what) {
        Map<String, Object> values = new HashMap<>();
        values.put("id", hologram.id());
        values.put("type", hologram.type().name());
        if (hologram.argument() != null) {
            values.put("argument", hologram.argument());
        }
        values.put("lines", String.join("\n", hologram.lines()));
        values.put("world", hologram.world());
        values.put("x", hologram.x());
        values.put("y", hologram.y());
        values.put("z", hologram.z());
        Tasks.async(plugin, player, () -> {
            database.set(TABLE, values, "id");
            settings.log("holo:" + hologram.id(), null, what, author);
            return true;
        }, done -> {
            messages.send(player, "editor.holo.saved", "id", hologram.id());
            changed();
        }, () -> messages.send(player, "error.generic"));
    }

    @Override
    public List<String> complete(Player player, String[] args) {
        if (args.length == 1) {
            return SubCommand.filter(List.of("list", "create", "addline", "setline", "removeline", "move", "delete"), args[0]);
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("create")) {
            return SubCommand.filter(new ArrayList<>(holograms.keySet()), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            return SubCommand.filter(List.of("text", "players", "board", "progress"), args[2]);
        }
        return List.of();
    }
}
