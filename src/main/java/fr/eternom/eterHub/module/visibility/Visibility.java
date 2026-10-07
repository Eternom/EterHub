package fr.eternom.eterHub.module.visibility;

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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Masquer les autres joueurs au lobby (/visibility) : choix gardé en base (eterhub_preferences), donc le même sur
 * tous les lobbys. Ne concerne que l'affichage pour ce joueur : les autres le voient toujours.
 */
public class Visibility implements Listener, TabExecutor {

    private static final String TABLE = "preferences";

    private final JavaPlugin plugin;
    private final Database database;
    private final Messages messages;
    /** Joueurs connectés ici qui masquent les autres. */
    private final Set<UUID> hiding = ConcurrentHashMap.newKeySet();

    public Visibility(JavaPlugin plugin, Database database, Messages messages) {
        this.plugin = plugin;
        this.database = database;
        this.messages = messages;
        database.createTable(TABLE,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("hide_players", Column.Type.BOOLEAN).notNull());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
            return true;
        }
        boolean hide = !hiding.contains(player.getUniqueId());
        apply(player, hide);
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, () -> database.set(TABLE, Map.of("uuid", uuid, "hide_players", hide), "uuid"),
                "Choix de visibilité non enregistré pour " + player.getName());
        messages.send(player, hide ? "visibility.hidden" : "visibility.shown");
        player.playSound(player, Sound.UI_BUTTON_CLICK, 0.5f, hide ? 0.8f : 1.2f);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        // Ceux qui masquent les autres ne voient pas non plus le nouveau venu
        hiding.stream().map(Bukkit::getPlayer).filter(viewer -> viewer != null && viewer != joined)
                .forEach(viewer -> viewer.hidePlayer(plugin, joined));
        UUID uuid = joined.getUniqueId();
        Tasks.async(plugin, joined, () -> database.getFirst(TABLE, Map.of("uuid", uuid))
                        .map(row -> row.getBoolean("hide_players")).orElse(false),
                hide -> {
                    if (hide) {
                        apply(joined, true);
                    }
                }, () -> { });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hiding.remove(event.getPlayer().getUniqueId());
    }

    private void apply(Player viewer, boolean hide) {
        if (hide) {
            hiding.add(viewer.getUniqueId());
        } else {
            hiding.remove(viewer.getUniqueId());
        }
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other == viewer) {
                continue;
            }
            if (hide) {
                viewer.hidePlayer(plugin, other);
            } else {
                viewer.showPlayer(plugin, other);
            }
        }
    }
}
