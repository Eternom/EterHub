package fr.eternom.eterHub.module.activity;

import com.google.gson.JsonObject;
import fr.eternom.eterHub.api.HubActivityEvent;
import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.HubStats;
import fr.eternom.eterHub.module.core.Rewards;
import fr.eternom.eterHub.module.zone.ZoneRule;
import fr.eternom.eterHub.module.zone.ZoneService;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * La pêche au Lac (zones où la règle fishing est permise, activities.fishing). Rien n'entre dans l'inventaire (EterSync
 * peut le synchroniser) : les prises sont des trophées, gardées en base (eterhub_catches).
 * - Sans canne : s'accroupir en regardant l'eau (8 blocs) lance la ligne ; garder Maj enfoncé ; « Ça mord ! » : lâcher
 *   Maj dans la seconde ferre le poisson. Lâcher trop tôt rentre la ligne.
 * - Avec une canne : le poisson pris devient un trophée (l'objet est retiré).
 * Prise : poisson tiré dans fishing.fish (rareté, poids), titre pour les rares, annonce sur tous les lobbys pour les
 * légendaires (et les rares si fishing.announce-rare), classement du jour et du mois (/peche, « hub_fishing »).
 */
public class Fishing implements Listener, TabExecutor {

    private static final String CAUGHT = "fishing-catch";

    private enum Phase { WAITING, BITE }

    private static final class Line {
        final Location water;
        Phase phase = Phase.WAITING;
        long next;

        Line(Location water, long next) {
            this.water = water;
            this.next = next;
        }
    }

    private final JavaPlugin plugin;
    private final ZoneService zones;
    private final FishingStore store;
    private final HubSettings settings;
    private final HubStats stats;
    private final Messages messages;
    private final NetworkBus bus;
    private final BackButton back;
    private volatile LootTable table;
    private final Map<UUID, Line> lines = new ConcurrentHashMap<>();

    public Fishing(JavaPlugin plugin, ZoneService zones, FishingStore store, HubSettings settings, HubStats stats,
                   Messages messages, NetworkBus bus, BackButton back) {
        this.plugin = plugin;
        this.zones = zones;
        this.store = store;
        this.settings = settings;
        this.stats = stats;
        this.messages = messages;
        this.bus = bus;
        this.back = back;
        reload();
        bus.on(CAUGHT, data -> {
            String key = "fishing.announce-" + data.get("rarity").getAsString();
            for (Player player : Bukkit.getOnlinePlayers()) {
                messages.send(player, key, "player", data.get("player").getAsString(),
                        "fish", fishName(player, data.get("fish").getAsString()), "weight", data.get("weight").getAsString());
            }
        });
    }

    public void reload() {
        table = LootTable.load(plugin.getConfig().getConfigurationSection("fishing.fish"));
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5, 5);
    }

    public boolean fishing(Player player) {
        return lines.containsKey(player.getUniqueId());
    }

    private boolean enabled(Player player) {
        return settings.bool("activities.fishing", true) && zones.allows(player, ZoneRule.FISHING);
    }

    // ---------- Sans canne ----------

    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Line line = lines.get(uuid);
        if (!event.isSneaking()) {
            if (line == null) {
                return;
            }
            lines.remove(uuid);
            if (line.phase == Phase.BITE) {
                Location water = line.water;
                player.getWorld().spawnParticle(Particle.SPLASH, water, 30, 0.3, 0.1, 0.3, 0.1);
                player.playSound(player, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.8f, 1f);
                caught(player);
            } else {
                messages.actionBar(player, "fishing.reeled");
            }
            return;
        }
        if (line != null || !enabled(player) || player.getInventory().getItemInMainHand().getType() == Material.FISHING_ROD) {
            return;
        }
        Block target = player.getTargetBlockExact(8, FluidCollisionMode.ALWAYS);
        if (target == null || target.getType() != Material.WATER) {
            return;
        }
        Location water = target.getLocation().add(0.5, 1.0, 0.5);
        lines.put(uuid, new Line(water, System.currentTimeMillis() + waitMillis()));
        player.playSound(player, Sound.ENTITY_FISHING_BOBBER_THROW, 0.7f, 1f);
        player.spawnParticle(Particle.SPLASH, water, 15, 0.2, 0.05, 0.2, 0.05);
        messages.actionBar(player, "fishing.cast");
    }

    private long waitMillis() {
        long min = (long) (settings.number("fishing.wait-min-seconds", 4) * 1000);
        long max = Math.max(min + 1, (long) (settings.number("fishing.wait-max-seconds", 12) * 1000));
        return ThreadLocalRandom.current().nextLong(min, max);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Line> entry : lines.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Line line = entry.getValue();
            if (player == null || !player.isSneaking() || !enabled(player)
                    || player.getLocation().distanceSquared(line.water) > 15 * 15) {
                lines.remove(entry.getKey());
                continue;
            }
            if (line.phase == Phase.WAITING) {
                player.spawnParticle(Particle.FISHING, line.water, 2, 0.1, 0, 0.1, 0);
                if (now >= line.next) {
                    line.phase = Phase.BITE;
                    line.next = now + (long) (settings.number("fishing.bite-window-seconds", 1.2) * 1000);
                    player.showTitle(Title.title(Component.empty(), messages.get(player, "fishing.bite"),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(200))));
                    player.playSound(player, Sound.ENTITY_FISHING_BOBBER_SPLASH, 1f, 1f);
                    player.spawnParticle(Particle.SPLASH, line.water, 25, 0.2, 0.1, 0.2, 0.1);
                }
            } else if (now > line.next) {
                lines.remove(entry.getKey());
                messages.actionBar(player, "fishing.escaped");
                player.playSound(player, Sound.ENTITY_FISH_SWIM, 0.7f, 1f);
            }
        }
    }

    // ---------- Avec une canne ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        event.setExpToDrop(0);
        if (event.getCaught() instanceof Item item) {
            item.remove();
        }
        if (enabled(event.getPlayer())) {
            caught(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lines.remove(event.getPlayer().getUniqueId());
    }

    // ---------- Prise ----------

    private void caught(Player player) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        LootTable.Draw draw = table.roll(random.nextDouble(), random.nextDouble()).orElse(null);
        if (draw == null) {
            return;
        }
        LootTable.Rarity rarity = draw.entry().rarity();
        String weight = String.format(Locale.FRANCE, "%.2f", draw.weight());
        String name = fishName(player, draw.entry().id());
        messages.send(player, "fishing.caught-" + rarity.key(), "fish", name, "weight", weight);
        if (rarity.ordinal() >= LootTable.Rarity.RARE.ordinal()) {
            player.showTitle(Title.title(messages.get(player, "fishing.title-" + rarity.key()),
                    messages.get(player, "fishing.subtitle", "fish", name, "weight", weight)));
            player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
        } else {
            player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
        }
        stats.add("activity:fishing");
        long grams = Math.round(draw.weight() * 1000);
        UUID uuid = player.getUniqueId();
        String fish = draw.entry().id();
        Tasks.async(plugin, () -> store.add(uuid, fish, rarity.key(), grams), "Prise non gardée");
        boolean announce = rarity == LootTable.Rarity.LEGENDARY
                || rarity == LootTable.Rarity.RARE && settings.bool("fishing.announce-rare", false);
        if (announce) {
            JsonObject data = new JsonObject();
            data.addProperty("player", player.getName());
            data.addProperty("fish", fish);
            data.addProperty("rarity", rarity.key());
            data.addProperty("weight", weight);
            String key = "fishing.announce-" + rarity.key();
            Bukkit.getOnlinePlayers().forEach(viewer -> messages.send(viewer, key, "player", player.getName(),
                    "fish", fishName(viewer, fish), "weight", weight));
            Tasks.async(plugin, () -> bus.publish(CAUGHT, data), "Prise non annoncée");
        }
        Rewards.give(plugin, player, plugin.getConfig().getStringList("fishing.reward-" + rarity.key()));
        Bukkit.getPluginManager().callEvent(new HubActivityEvent(player, "fishing", fish, rarity.ordinal(), false));
    }

    private String fishName(Player viewer, String id) {
        String raw = messages.raw(viewer, "fishing.fish." + id);
        return raw != null ? raw : id;
    }

    // ---------- /peche ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) {
            openMenu(player);
        } else {
            messages.send(sender, "command.players-only");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }

    /** Classements du jour et du mois, sa plus belle prise et ses espèces. */
    public void openMenu(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> new Object[]{store.top(FishingStore.since(false), 10), store.top(FishingStore.since(true), 10),
                store.personal(uuid)}, data -> {
            @SuppressWarnings("unchecked") List<FishingStore.Ranked> day = (List<FishingStore.Ranked>) data[0];
            @SuppressWarnings("unchecked") List<FishingStore.Ranked> month = (List<FishingStore.Ranked>) data[1];
            player.openInventory(new FishingMenu(player, day, month, (long[]) data[2]).getInventory());
        }, () -> messages.send(player, "error.generic"));
    }

    private final class FishingMenu implements Menu {

        private final Inventory inventory;

        FishingMenu(Player viewer, List<FishingStore.Ranked> day, List<FishingStore.Ranked> month, long[] personal) {
            inventory = Bukkit.createInventory(this, 27, messages.get(viewer, "fishing.menu.title"));
            Frame.draw(inventory, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
            inventory.setItem(11, Items.item(Material.CLOCK, messages.get(viewer, "fishing.menu.day"), lines(viewer, day)));
            inventory.setItem(13, Items.item(Material.FISHING_ROD, messages.get(viewer, "fishing.menu.how"),
                    List.of(messages.get(viewer, "fishing.menu.how-1"), messages.get(viewer, "fishing.menu.how-2"),
                            messages.get(viewer, "fishing.menu.how-3"), Component.empty(),
                            messages.get(viewer, "fishing.menu.best", "weight", String.format(Locale.FRANCE, "%.2f", personal[0] / 1000.0)),
                            messages.get(viewer, "fishing.menu.species", "count", String.valueOf(personal[1]),
                                    "total", String.valueOf(table.entries().size())))));
            inventory.setItem(15, Items.item(Material.GOLD_INGOT, messages.get(viewer, "fishing.menu.month"), lines(viewer, month)));
            inventory.setItem(22, back.item(viewer));
        }

        private List<Component> lines(Player viewer, List<FishingStore.Ranked> top) {
            List<Component> lore = new ArrayList<>();
            if (top.isEmpty()) {
                lore.add(messages.get(viewer, "fishing.menu.empty"));
            }
            for (int i = 0; i < top.size(); i++) {
                FishingStore.Ranked entry = top.get(i);
                String name = EterLib.get().getPlayers().get(entry.player()).map(found -> found.name()).orElse("?");
                lore.add(messages.get(viewer, "fishing.menu.line", "rank", String.valueOf(i + 1), "player", name,
                        "fish", fishName(viewer, entry.fish()), "weight", String.format(Locale.FRANCE, "%.2f", entry.grams() / 1000.0)));
            }
            return lore;
        }

        @Override
        public void onClick(Player player, int slot, ClickType click) {
            if (slot == 22) {
                back.click(player);
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
