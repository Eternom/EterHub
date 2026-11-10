package fr.eternom.eterHub.module.activity;

import fr.eternom.eterHub.api.HubActivityEvent;
import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.HubStats;
import fr.eternom.eterHub.module.core.Rewards;
import fr.eternom.eterHub.module.zone.ZoneRule;
import fr.eternom.eterHub.module.zone.ZoneService;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.task.Tasks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Les fouilles du site d'Archéologie (zones où la règle dig est permise, activities.archaeology) : cliquer plusieurs
 * fois sur du sable ou du gravier suspect le fouille (son du pinceau, poussière). Fini : une trouvaille tirée dans
 * archaeology.finds (fragments de lore, poteries...), et le bloc redevient du sable ordinaire, puis se reforme
 * (archaeology.respawn-seconds). Rien n'entre dans l'inventaire : les trouvailles vont au musée personnel (/museum),
 * gardées en base (eterhub_finds). Collection complète : archaeology.reward-complete.
 */
public class Archaeology implements Listener, TabExecutor {

    private static final String TABLE = "finds";

    private record Progress(String block, int hits, long last) {
    }

    private final JavaPlugin plugin;
    private final ZoneService zones;
    private final Database database;
    private final HubSettings settings;
    private final HubStats stats;
    private final Messages messages;
    private final BackButton back;
    private volatile LootTable table;
    private final Map<UUID, Progress> digging = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Integer>> museums = new ConcurrentHashMap<>();
    /** Blocs fouillés, à reformer : bloc -> son type d'origine. */
    private final Map<Location, Material> resting = new ConcurrentHashMap<>();

    public Archaeology(JavaPlugin plugin, ZoneService zones, Database database, HubSettings settings, HubStats stats,
                       Messages messages, BackButton back) {
        this.plugin = plugin;
        this.zones = zones;
        this.database = database;
        this.settings = settings;
        this.stats = stats;
        this.messages = messages;
        this.back = back;
        database.createTable(TABLE,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("find", Column.Type.STRING).length(48).primaryKey(),
                Column.of("amount", Column.Type.INT).notNull(),
                Column.of("first_at", Column.Type.LONG).notNull());
        reload();
    }

    public void reload() {
        table = LootTable.load(plugin.getConfig().getConfigurationSection("archaeology.finds"));
    }

    /** Bloquant (arrivée). */
    public void load(UUID player) {
        Map<String, Integer> finds = new ConcurrentHashMap<>();
        database.get(TABLE, Map.of("uuid", player)).forEach(row -> finds.put(row.getString("find"), row.getInt("amount")));
        museums.put(player, finds);
    }

    /** Trouvailles différentes / total, pour un joueur connecté ici. */
    public int[] progress(UUID player) {
        return new int[]{museums.getOrDefault(player, Map.of()).size(), table.entries().size()};
    }

    /** Arrêt : les blocs fouillés se reforment tout de suite. */
    public void restoreAll() {
        resting.forEach((location, type) -> location.getBlock().setType(type, false));
        resting.clear();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK
                || block.getType() != Material.SUSPICIOUS_SAND && block.getType() != Material.SUSPICIOUS_GRAVEL) {
            return;
        }
        Player player = event.getPlayer();
        if (!settings.bool("activities.archaeology", true) || !zones.allows(block.getLocation(), ZoneRule.DIG)) {
            return;
        }
        event.setCancelled(true);
        String key = block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
        long now = System.currentTimeMillis();
        Progress progress = digging.get(player.getUniqueId());
        int hits = progress != null && progress.block().equals(key) && now - progress.last() < 3000 ? progress.hits() + 1 : 1;
        int needed = Math.max(1, settings.integer("archaeology.hits", 6));
        boolean sand = block.getType() == Material.SUSPICIOUS_SAND;
        Location center = block.getLocation().add(0.5, 1.0, 0.5);
        player.playSound(center, sand ? Sound.ITEM_BRUSH_BRUSHING_SAND : Sound.ITEM_BRUSH_BRUSHING_GRAVEL, 0.8f, 1f);
        player.spawnParticle(Particle.BLOCK, center, 8, 0.3, 0.1, 0.3, 0, block.getBlockData());
        if (hits < needed) {
            digging.put(player.getUniqueId(), new Progress(key, hits, now));
            messages.actionBar(player, "archaeology.progress", "bar", bar(hits, needed));
            return;
        }
        digging.remove(player.getUniqueId());
        Material original = block.getType();
        block.setType(sand ? Material.SAND : Material.GRAVEL, false);
        Location location = block.getLocation();
        resting.put(location, original);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (resting.remove(location) != null) {
                location.getBlock().setType(original, false);
                location.getWorld().spawnParticle(Particle.DUST_PLUME, location.clone().add(0.5, 1, 0.5), 6, 0.2, 0.1, 0.2, 0);
            }
        }, Math.max(20, (long) (settings.number("archaeology.respawn-seconds", 60) * 20)));
        player.playSound(center, sand ? Sound.ITEM_BRUSH_BRUSHING_SAND_COMPLETE : Sound.ITEM_BRUSH_BRUSHING_GRAVEL_COMPLETE, 1f, 1f);
        found(player);
    }

    private static String bar(int hits, int needed) {
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < needed; i++) {
            bar.append(i < hits ? "■" : "□");
        }
        return bar.toString();
    }

    private void found(Player player) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        LootTable.Draw draw = table.roll(random.nextDouble(), 0).orElse(null);
        if (draw == null) {
            return;
        }
        String id = draw.entry().id();
        UUID uuid = player.getUniqueId();
        Map<String, Integer> museum = museums.computeIfAbsent(uuid, key -> new ConcurrentHashMap<>());
        boolean first = !museum.containsKey(id);
        museum.merge(id, 1, Integer::sum);
        String name = findName(player, id);
        LootTable.Rarity rarity = draw.entry().rarity();
        messages.send(player, first ? "archaeology.found-new" : "archaeology.found", "find", name, "rarity",
                messages.plain(player, "rarity." + rarity.key()), "count", String.valueOf(museum.size()),
                "total", String.valueOf(table.entries().size()));
        if (first) {
            player.showTitle(Title.title(messages.get(player, "archaeology.title"), Component.text(name)));
            player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
        }
        stats.add("activity:archaeology");
        Tasks.async(plugin, () -> database.execute("INSERT INTO " + database.table(TABLE)
                + " (uuid, find, amount, first_at) VALUES (?, ?, 1, ?) ON DUPLICATE KEY UPDATE amount = amount + 1",
                uuid, id, System.currentTimeMillis()), "Trouvaille non gardée");
        Rewards.give(plugin, player, plugin.getConfig().getStringList("archaeology.reward-" + rarity.key()));
        Bukkit.getPluginManager().callEvent(new HubActivityEvent(player, "dig", id, rarity.ordinal(), first));
        if (first && museum.keySet().containsAll(table.entries().stream().map(LootTable.Entry::id).toList())) {
            messages.send(player, "archaeology.complete");
            Rewards.give(plugin, player, plugin.getConfig().getStringList("archaeology.reward-complete"));
        }
    }

    private String findName(Player viewer, String id) {
        String raw = messages.raw(viewer, "archaeology.finds." + id + ".name");
        return raw != null ? messages.plain(viewer, "archaeology.finds." + id + ".name") : id;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        digging.remove(event.getPlayer().getUniqueId());
        museums.remove(event.getPlayer().getUniqueId());
    }

    // ---------- Musée : /museum ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) {
            openMuseum(player);
        } else {
            messages.send(sender, "command.players-only");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }

    public void openMuseum(Player player) {
        player.openInventory(new Museum(player).getInventory());
    }

    private final class Museum implements Menu {

        private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        private final Inventory inventory;

        Museum(Player viewer) {
            Map<String, Integer> found = new HashMap<>(museums.getOrDefault(viewer.getUniqueId(), Map.of()));
            inventory = Bukkit.createInventory(this, 45, messages.get(viewer, "archaeology.museum.title",
                    "count", String.valueOf(found.size()), "total", String.valueOf(table.entries().size())));
            Frame.draw(inventory, Material.BROWN_STAINED_GLASS_PANE);
            int i = 0;
            for (LootTable.Entry entry : table.entries()) {
                if (i >= SLOTS.length) {
                    break;
                }
                Integer amount = found.get(entry.id());
                if (amount == null) {
                    inventory.setItem(SLOTS[i++], Items.item(Material.GRAY_DYE, messages.get(viewer, "archaeology.museum.unknown"),
                            List.of(messages.get(viewer, "rarity." + entry.rarity().key()))));
                    continue;
                }
                List<Component> lore = new ArrayList<>();
                lore.add(messages.get(viewer, "rarity." + entry.rarity().key()));
                String text = messages.raw(viewer, "archaeology.finds." + entry.id() + ".lore");
                if (text != null) {
                    lore.add(Component.empty());
                    for (String line : text.split("\n")) {
                        lore.add(messages.render(line, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.empty()));
                    }
                }
                lore.add(Component.empty());
                lore.add(messages.get(viewer, "archaeology.museum.amount", "count", String.valueOf(amount)));
                inventory.setItem(SLOTS[i++], Items.item(entry.icon(), messages.get(viewer, "archaeology.museum.name",
                        "find", findName(viewer, entry.id())), lore));
            }
            inventory.setItem(40, back.item(viewer));
        }

        @Override
        public void onClick(Player player, int slot, ClickType click) {
            if (slot == 40) {
                back.click(player);
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
