package fr.eternom.eterHub.module.social;

import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Qui l'on voit au lobby (/visibility, ou l'action eterhub:visibility) : tous, amis et clan, staff seulement, personne.
 * Choix gardé en base (eterhub_preferences), le même sur tous les lobbys. Ne concerne que l'affichage pour ce joueur.
 * Les amis viennent d'EterFriend, le clan d'EterClan (facultatifs) ; relu à chaque arrivée et toutes les 30 s.
 */
public class Visibility implements Listener, TabExecutor {

    public static final String STAFF = "eterhub.staff";
    private static final String TABLE = "preferences";

    public enum Mode {
        ALL, FRIENDS, STAFF, NONE;

        Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final JavaPlugin plugin;
    private final Database database;
    private final Messages messages;
    private final FriendsView friends;
    private final Map<UUID, Mode> modes = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> clans = new ConcurrentHashMap<>();

    public Visibility(JavaPlugin plugin, Database database, Messages messages, FriendsView friends) {
        this.plugin = plugin;
        this.database = database;
        this.messages = messages;
        this.friends = friends;
        database.createTable(TABLE,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("hide_players", Column.Type.BOOLEAN).notNull());
        database.addColumn(TABLE, Column.of("mode", Column.Type.INT));
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::applyAll, 200, 600);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> Bukkit.getOnlinePlayers()
                .forEach(player -> clans.put(player.getUniqueId(), friends.clanMates(player.getUniqueId()))), 400, 20 * 60);
    }

    public Mode mode(Player player) {
        return modes.getOrDefault(player.getUniqueId(), Mode.ALL);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
            return true;
        }
        Mode next = mode(player).next();
        if (args.length > 0) {
            for (Mode mode : Mode.values()) {
                if (mode.key().startsWith(args[0].toLowerCase(Locale.ROOT)) || messages.plain(player, "visibility.mode." + mode.key())
                        .toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    next = mode;
                }
            }
        }
        set(player, next);
        return true;
    }

    public void cycle(Player player) {
        set(player, mode(player).next());
    }

    private void set(Player player, Mode mode) {
        modes.put(player.getUniqueId(), mode);
        apply(player);
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, () -> database.set(TABLE, Map.of("uuid", uuid, "hide_players", mode == Mode.NONE, "mode", mode.ordinal()), "uuid"),
                "Choix de visibilité non enregistré pour " + player.getName());
        messages.send(player, "visibility.changed", "mode", messages.plain(player, "visibility.mode." + mode.key()));
        player.playSound(player, Sound.UI_BUTTON_CLICK, 0.5f, 1f + mode.ordinal() * 0.15f);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return args.length == 1 ? fr.eternom.eterHub.module.core.SubCommand.filter(
                java.util.Arrays.stream(Mode.values()).map(Mode::key).toList(), args[0]) : List.of();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        UUID uuid = joined.getUniqueId();
        // Les autres voient (ou non) le nouveau venu selon leur choix
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer != joined) {
                show(viewer, joined, sees(viewer, joined));
            }
        }
        Tasks.async(plugin, joined, () -> {
            clans.put(uuid, friends.clanMates(uuid));
            return database.getFirst(TABLE, Map.of("uuid", uuid)).map(row -> {
                Object mode = row.get("mode");
                if (mode instanceof Number number && number.intValue() >= 0 && number.intValue() < Mode.values().length) {
                    return Mode.values()[number.intValue()];
                }
                return row.getBoolean("hide_players") ? Mode.NONE : Mode.ALL;
            }).orElse(Mode.ALL);
        }, mode -> {
            modes.put(uuid, mode);
            apply(joined);
            // Ses amis et son clan sont connus maintenant : les autres le revoient selon leur choix
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer != joined) {
                    show(viewer, joined, sees(viewer, joined));
                }
            }
        }, () -> { });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        modes.remove(event.getPlayer().getUniqueId());
        clans.remove(event.getPlayer().getUniqueId());
    }

    /** Ce que voit viewer de other, selon son mode. */
    private boolean sees(Player viewer, Player other) {
        return switch (mode(viewer)) {
            case ALL -> true;
            case NONE -> false;
            case STAFF -> other.hasPermission(STAFF);
            case FRIENDS -> other.hasPermission(STAFF) || friends.friends(viewer.getUniqueId()).contains(other.getUniqueId())
                    || clans.getOrDefault(viewer.getUniqueId(), Set.of()).contains(other.getUniqueId());
        };
    }

    private void apply(Player viewer) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other != viewer) {
                show(viewer, other, sees(viewer, other));
            }
        }
    }

    private void applyAll() {
        Bukkit.getOnlinePlayers().forEach(this::apply);
    }

    private void show(Player viewer, Player other, boolean visible) {
        if (visible) {
            viewer.showPlayer(plugin, other);
        } else {
            viewer.hidePlayer(plugin, other);
        }
    }
}
