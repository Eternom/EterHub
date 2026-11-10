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
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * La pêche au Lac, à la vraie canne de Minecraft : lancer la ligne n'est possible que dans les lieux où la règle
 * fishing est permise (activities.fishing). Ce qui mord est remplacé par un poisson du lac tiré dans fishing.fish
 * (rareté, poids) : un vrai objet, nommé, avec sa rareté, son poids et la date dans sa description, et ses données
 * gardées dans l'objet (eterhub:fish, rarity, grams : pour un PNJ qui les reprendra plus tard). Seul le pêcheur peut le
 * ramasser. Titre pour les rares, annonce sur tous les lobbys pour les légendaires (et les rares si
 * fishing.announce-rare), classement du jour et du mois (/fishing, « hub_fishing »).
 */
public class Fishing implements Listener, TabExecutor {

    private static final String CAUGHT = "fishing-catch";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JavaPlugin plugin;
    private final ZoneService zones;
    private final FishingStore store;
    private final HubSettings settings;
    private final HubStats stats;
    private final Messages messages;
    private final NetworkBus bus;
    private final BackButton back;
    private final NamespacedKey fishKey;
    private final NamespacedKey rarityKey;
    private final NamespacedKey gramsKey;
    private volatile LootTable table;
    private final Map<UUID, Long> lastRefusal = new ConcurrentHashMap<>();

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
        this.fishKey = new NamespacedKey(plugin, "fish");
        this.rarityKey = new NamespacedKey(plugin, "rarity");
        this.gramsKey = new NamespacedKey(plugin, "grams");
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

    private boolean enabled(Player player) {
        return settings.bool("activities.fishing", true) && zones.allows(player, ZoneRule.FISHING);
    }

    // ---------- La canne ----------

    /** La ligne lancée hors du lac est retenue par la protection : on dit pourquoi (une fois toutes les 3 s). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCast(ProjectileLaunchEvent event) {
        if (!event.isCancelled() || !(event.getEntity() instanceof FishHook hook) || !(hook.getShooter() instanceof Player player)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastRefusal.get(player.getUniqueId());
        if (last == null || now - last > 3000) {
            lastRefusal.put(player.getUniqueId(), now);
            messages.actionBar(player, settings.bool("activities.fishing", true) ? "fishing.only-lake" : "fishing.closed");
        }
    }

    /** Une prise : l'objet qui sort de l'eau devient un poisson du lac. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item item)) {
            return;
        }
        Player player = event.getPlayer();
        event.setExpToDrop(0);
        if (!enabled(player)) {
            item.remove();
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        LootTable.Draw draw = table.roll(random.nextDouble(), random.nextDouble()).orElse(null);
        if (draw == null) {
            return;
        }
        item.setItemStack(fishItem(player, draw));
        item.setOwner(player.getUniqueId());
        caught(player, draw);
    }

    /** Seul le pêcheur ramasse son poisson (la protection du lobby refuse les autres ramassages). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isFish(event.getItem().getItemStack())
                && player.getUniqueId().equals(event.getItem().getOwner())) {
            event.setCancelled(false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastRefusal.remove(event.getPlayer().getUniqueId());
    }

    private boolean isFish(ItemStack stack) {
        return stack != null && stack.hasItemMeta() && stack.getItemMeta().getPersistentDataContainer().has(fishKey);
    }

    /** Le poisson : nom selon la rareté, rareté, poids, date ; ses données dans l'objet. */
    private ItemStack fishItem(Player player, LootTable.Draw draw) {
        LootTable.Entry entry = draw.entry();
        String weight = String.format(Locale.FRANCE, "%.2f", draw.weight());
        ItemStack stack = ItemStack.of(entry.icon() == null ? Material.COD : entry.icon());
        stack.editMeta(meta -> {
            meta.customName(messages.get(player, "fishing.item.name-" + entry.rarity().key(), "fish", fishName(player, entry.id()))
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    messages.get(player, "rarity." + entry.rarity().key()).decoration(TextDecoration.ITALIC, false),
                    messages.get(player, "fishing.item.weight", "weight", weight).decoration(TextDecoration.ITALIC, false),
                    messages.get(player, "fishing.item.caught", "player", player.getName(), "date", DATE.format(LocalDate.now()))
                            .decoration(TextDecoration.ITALIC, false)));
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.set(fishKey, PersistentDataType.STRING, entry.id());
            data.set(rarityKey, PersistentDataType.STRING, entry.rarity().key());
            data.set(gramsKey, PersistentDataType.LONG, Math.round(draw.weight() * 1000));
            if (entry.rarity() == LootTable.Rarity.LEGENDARY) {
                meta.setEnchantmentGlintOverride(true);
            }
        });
        return stack;
    }

    private void caught(Player player, LootTable.Draw draw) {
        LootTable.Rarity rarity = draw.entry().rarity();
        String weight = String.format(Locale.FRANCE, "%.2f", draw.weight());
        String name = fishName(player, draw.entry().id());
        messages.send(player, "fishing.caught-" + rarity.key(), "fish", name, "weight", weight);
        if (rarity.ordinal() >= LootTable.Rarity.RARE.ordinal()) {
            player.showTitle(Title.title(messages.get(player, "fishing.title-" + rarity.key()),
                    messages.get(player, "fishing.subtitle", "fish", name, "weight", weight)));
            player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
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

    // ---------- /fishing ----------

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
