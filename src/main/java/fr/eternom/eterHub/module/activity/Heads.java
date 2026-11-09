package fr.eternom.eterHub.module.activity;

import com.google.gson.JsonObject;
import fr.eternom.eterHub.api.HubActivityEvent;
import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.HubStats;
import fr.eternom.eterHub.module.core.Rewards;
import fr.eternom.eterHub.module.core.SubCommand;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * La chasse aux têtes : des têtes cachées dans tout le village (posées à la main, puis déclarées avec
 * /eterhub head add en les visant). Un clic droit sur une tête la trouve (une fois par joueur) : compteur, son,
 * heads.reward-each, et heads.reward-all quand toutes sont trouvées. Têtes et trouvailles en base, communes à tous
 * les lobbys (eterhub_heads, eterhub_heads_found).
 */
public class Heads implements Listener, SubCommand {

    private static final String HEADS = "heads";
    private static final String FOUND = "heads_found";
    private static final String CHANGED = "heads";

    private final JavaPlugin plugin;
    private final Database database;
    private final HubSettings settings;
    private final HubStats stats;
    private final Messages messages;
    private final NetworkBus bus;
    /** Bloc -> identifiant de la tête. */
    private volatile Map<String, Long> heads = Map.of();
    private final Map<UUID, Set<Long>> found = new ConcurrentHashMap<>();

    public Heads(JavaPlugin plugin, Database database, HubSettings settings, HubStats stats, Messages messages, NetworkBus bus) {
        this.plugin = plugin;
        this.database = database;
        this.settings = settings;
        this.stats = stats;
        this.messages = messages;
        this.bus = bus;
        database.createTable(HEADS,
                Column.of("id", Column.Type.LONG).primaryKey().autoIncrement(),
                Column.of("world", Column.Type.STRING).length(64).notNull(),
                Column.of("x", Column.Type.INT).notNull(),
                Column.of("y", Column.Type.INT).notNull(),
                Column.of("z", Column.Type.INT).notNull());
        database.createTable(FOUND,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("head", Column.Type.LONG).primaryKey(),
                Column.of("found_at", Column.Type.LONG).notNull());
        bus.on(CHANGED, data -> reload());
    }

    public void reload() {
        Tasks.async(plugin, () -> {
            Map<String, Long> fresh = new HashMap<>();
            database.get(HEADS, Map.of()).forEach(row -> fresh.put(key(row.getString("world"), row.getInt("x"), row.getInt("y"),
                    row.getInt("z")), row.getLong("id")));
            heads = fresh;
        }, "Têtes non relues");
    }

    /** Bloquant (arrivée). */
    public void load(UUID player) {
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        database.get(FOUND, Map.of("uuid", player)).forEach(row -> ids.add(row.getLong("head")));
        found.put(player, ids);
    }

    public int total() {
        return heads.size();
    }

    /** Têtes encore existantes trouvées par le joueur. */
    public int foundBy(UUID player) {
        Set<Long> ids = found.getOrDefault(player, Set.of());
        return (int) heads.values().stream().filter(ids::contains).count();
    }

    private static String key(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    private static boolean isHead(Material type) {
        return type == Material.PLAYER_HEAD || type == Material.PLAYER_WALL_HEAD;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || !isHead(block.getType())) {
            return;
        }
        Long id = heads.get(key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
        if (id == null || !settings.bool("activities.heads", true)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Set<Long> ids = found.get(player.getUniqueId());
        if (ids == null) {
            return;
        }
        if (!ids.add(id)) {
            messages.actionBar(player, "heads.already", "count", String.valueOf(foundBy(player.getUniqueId())), "total", String.valueOf(total()));
            return;
        }
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, () -> database.execute("INSERT IGNORE INTO " + database.table(FOUND) + " (uuid, head, found_at) VALUES (?, ?, ?)",
                uuid, id, System.currentTimeMillis()), "Tête trouvée non gardée");
        int count = foundBy(uuid);
        int total = total();
        player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        player.spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3, 0);
        messages.send(player, "heads.found", "count", String.valueOf(count), "total", String.valueOf(total));
        stats.add("activity:heads");
        Rewards.give(plugin, player, plugin.getConfig().getStringList("heads.reward-each"));
        Bukkit.getPluginManager().callEvent(new HubActivityEvent(player, "head", String.valueOf(id), count, false));
        if (count >= total && total > 0) {
            messages.send(player, "heads.all", "total", String.valueOf(total));
            player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1f);
            Rewards.give(plugin, player, plugin.getConfig().getStringList("heads.reward-all"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        found.remove(event.getPlayer().getUniqueId());
    }

    // ---------- Éditeur : /eterhub head add | remove | count ----------

    @Override
    public void run(Player player, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "count";
        if (sub.equals("count")) {
            messages.send(player, "editor.head.count", "total", String.valueOf(total()));
            return;
        }
        Block block = player.getTargetBlockExact(6);
        if (block == null || !isHead(block.getType())) {
            messages.send(player, "editor.head.look");
            return;
        }
        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        String author = player.getName();
        switch (sub) {
            case "add" -> Tasks.async(plugin, player, () -> {
                if (database.getFirst(HEADS, Map.of("world", world, "x", x, "y", y, "z", z)).isPresent()) {
                    return false;
                }
                database.insert(HEADS, Map.of("world", world, "x", x, "y", y, "z", z));
                settings.log("head", null, x + "," + y + "," + z, author);
                return true;
            }, added -> {
                messages.send(player, added ? "editor.head.added" : "editor.head.exists", "total", String.valueOf(total() + (added ? 1 : 0)));
                changed();
            }, () -> messages.send(player, "error.generic"));
            case "remove" -> Tasks.async(plugin, player, () -> {
                boolean removed = database.delete(HEADS, Map.of("world", world, "x", x, "y", y, "z", z)) > 0;
                if (removed) {
                    settings.log("head", x + "," + y + "," + z, null, author);
                }
                return removed;
            }, removed -> {
                messages.send(player, removed ? "editor.head.removed" : "editor.head.unknown");
                changed();
            }, () -> messages.send(player, "error.generic"));
            default -> messages.send(player, "editor.head.usage");
        }
    }

    private void changed() {
        reload();
        Tasks.async(plugin, () -> bus.publish(CHANGED, new JsonObject()), "Têtes non annoncées");
    }

    @Override
    public List<String> complete(Player player, String[] args) {
        return args.length == 1 ? SubCommand.filter(List.of("add", "remove", "count"), args[0]) : List.of();
    }
}
