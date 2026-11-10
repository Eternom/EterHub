package fr.eternom.eterHub.module.farm;

import fr.eternom.eterHub.api.HubActivityEvent;
import fr.eternom.eterHub.api.HubZoneEnterEvent;
import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.HubStats;
import fr.eternom.eterHub.module.zone.Zone;
import fr.eternom.eterHub.module.zone.ZoneRule;
import fr.eternom.eterHub.module.zone.ZoneService;
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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Les récoltes des Champs (activities.farming), dans les parcelles (lieux où la règle harvest est permise) :
 * - casser une culture mûre (blé, carottes, patates, betteraves) la récolte : elle se replante et repousse étape par
 *   étape (le champ n'est jamais rasé) ; pas mûre, rien ne se passe ; les chemins restent protégés ;
 * - un clic droit sur les vignes (feuilles d'azalée fleurie) cueille une grappe : les fleurs tombent, puis reviennent ;
 * - chaque récolte donne un vrai objet du village, de qualité normale, belle ou dorée (données gardées dans l'objet) ;
 * - plafond quotidien par culture et par joueur, sur tout le réseau (en base) : une activité du jour, pas une ferme ;
 * - la parcelle du jour (la même partout, change à minuit) : ses récoltes valent double et sont plus souvent dorées.
 * Le fermier et le vigneron les rachètent (FarmMarket). /harvest : le carnet des récoltes.
 */
public class Farming implements Listener, TabExecutor {

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    /** Une récolte du village, lue dans l'objet. */
    public record Produce(String crop, Quality quality, boolean bonus) {
    }

    private final JavaPlugin plugin;
    private final ZoneService zones;
    private final FarmStore store;
    private final HubSettings settings;
    private final HubStats stats;
    private final Messages messages;
    private final BackButton back;
    private final NamespacedKey cropKey;
    private final NamespacedKey qualityKey;
    private final NamespacedKey bonusKey;
    private volatile Map<String, Crop> crops = Map.of();
    /** Récoltes du jour de chaque joueur connecté ici : jour -> culture -> nombre. */
    private final Map<UUID, Map<String, Integer>> today = new ConcurrentHashMap<>();
    private final Map<UUID, String> loadedDay = new ConcurrentHashMap<>();
    private final Map<Location, BukkitTask> growing = new HashMap<>();
    private final Map<Location, BlockData> picked = new HashMap<>();
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

    public Farming(JavaPlugin plugin, ZoneService zones, FarmStore store, HubSettings settings, HubStats stats, Messages messages,
                   BackButton back) {
        this.plugin = plugin;
        this.zones = zones;
        this.store = store;
        this.settings = settings;
        this.stats = stats;
        this.messages = messages;
        this.back = back;
        this.cropKey = new NamespacedKey(plugin, "crop");
        this.qualityKey = new NamespacedKey(plugin, "quality");
        this.bonusKey = new NamespacedKey(plugin, "bonus");
        reload();
    }

    public void reload() {
        crops = Crop.load(plugin.getConfig().getConfigurationSection("farming"), plugin.getLogger());
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> store.purge(LocalDate.now(ZONE).minusDays(7).toString()),
                20 * 60, 20 * 60 * 60 * 6);
    }

    public Map<String, Crop> crops() {
        return crops;
    }

    private static String day() {
        return LocalDate.now(ZONE).toString();
    }

    /** Bloquant (arrivée). */
    public void load(UUID player) {
        String day = day();
        today.put(player, new ConcurrentHashMap<>(store.today(player, day)));
        loadedDay.put(player, day);
    }

    private int count(UUID player, String crop) {
        if (!day().equals(loadedDay.get(player))) {
            // Minuit est passé : un nouveau jour commence
            today.put(player, new ConcurrentHashMap<>());
            loadedDay.put(player, day());
        }
        return today.getOrDefault(player, Map.of()).getOrDefault(crop, 0);
    }

    // ---------- Parcelles ----------

    /** Les parcelles : les lieux où la récolte est permise, dans l'ordre de config.yml. */
    public List<String> parcels() {
        return zones.zones().values().stream().filter(zone -> zone.allows(ZoneRule.HARVEST)).map(Zone::id).toList();
    }

    public Optional<String> parcelOfTheDay() {
        return Quality.ofTheDay(LocalDate.now(ZONE), parcels());
    }

    private boolean enabled(Location location) {
        return settings.bool("activities.farming", true) && zones.allows(location, ZoneRule.HARVEST);
    }

    /** En arrivant dans une parcelle (ou aux Champs) : son nom, et la parcelle du jour. */
    @EventHandler
    public void onZone(HubZoneEnterEvent event) {
        if (event.getZone().isEmpty() || !settings.bool("activities.farming", true)) {
            return;
        }
        Player player = event.getPlayer();
        Zone zone = zones.zone(event.getZone()).orElse(null);
        if (zone == null) {
            return;
        }
        String daily = parcelOfTheDay().orElse("");
        if (zone.allows(ZoneRule.HARVEST)) {
            messages.actionBar(player, zone.id().equals(daily) ? "farming.parcel-daily" : "farming.parcel",
                    "parcel", zones.plainName(player, zone.id()));
        } else if (!daily.isEmpty() && zones.zone(daily).map(parcel -> parcel.parent().equals(zone.id())).orElse(false)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    messages.actionBar(player, "farming.daily-hint", "parcel", zones.plainName(player, daily));
                }
            }, 50);
        }
    }

    // ---------- Récolter ----------

    /** Casser une culture mûre dans une parcelle. Le staff en mode construction casse normalement (non annulé). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent event) {
        if (!event.isCancelled()) {
            return;
        }
        Block block = event.getBlock();
        Crop crop = crops.values().stream().filter(candidate -> !candidate.pickable() && candidate.block() == block.getType())
                .findFirst().orElse(null);
        if (crop == null || !enabled(block.getLocation())) {
            return;
        }
        Player player = event.getPlayer();
        if (!(block.getBlockData() instanceof Ageable ageable) || ageable.getAge() < ageable.getMaximumAge()) {
            notice(player, "farming.not-ripe");
            return;
        }
        if (!allowed(player, crop)) {
            return;
        }
        harvest(player, block, crop);
        regrow(block, crop);
    }

    /** Cueillir les vignes (clic droit). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPick(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Crop crop = crops.values().stream().filter(candidate -> candidate.pickable() && candidate.block() == block.getType())
                .findFirst().orElse(null);
        if (crop == null || !enabled(block.getLocation())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!allowed(player, crop)) {
            return;
        }
        harvest(player, block, crop);
        pick(block, crop);
    }

    /** Le plafond du jour (tout le réseau). */
    private boolean allowed(Player player, Crop crop) {
        if (!today.containsKey(player.getUniqueId())) {
            return false;
        }
        if (count(player.getUniqueId(), crop.id()) >= crop.dailyCap()) {
            notice(player, "farming.cap-reached", "crop", cropName(player, crop.id()), "cap", String.valueOf(crop.dailyCap()));
            return false;
        }
        return true;
    }

    private void notice(Player player, String key, String... placeholders) {
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(player.getUniqueId());
        if (last == null || now - last > 1500) {
            lastNotice.put(player.getUniqueId(), now);
            messages.actionBar(player, key, placeholders);
        }
    }

    private void harvest(Player player, Block block, Crop crop) {
        String zone = zones.at(block.getLocation()).id();
        boolean bonus = zone.equals(parcelOfTheDay().orElse(""));
        double goldenBoost = bonus ? settings.number("farming.daily-golden-multiplier", 2) : 1;
        Quality quality = Quality.roll(ThreadLocalRandom.current().nextDouble(), settings.number("farming.quality.fine.chance", 0.15),
                settings.number("farming.quality.golden.chance", 0.02) * goldenBoost);
        ItemStack item = item(player, crop, quality, bonus);
        player.getInventory().addItem(item).values().forEach(left -> {
            org.bukkit.entity.Item dropped = player.getWorld().dropItem(player.getLocation(), left);
            dropped.setOwner(player.getUniqueId());
        });
        UUID uuid = player.getUniqueId();
        int count = count(uuid, crop.id()) + 1;
        today.get(uuid).put(crop.id(), count);
        String day = loadedDay.get(uuid);
        Tasks.async(plugin, () -> store.add(uuid, day, crop.id(), quality), "Récolte non gardée");
        stats.add("activity:harvest");
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        if (crop.pickable()) {
            player.playSound(center, Sound.BLOCK_SWEET_BERRY_BUSH_PICK_BERRIES, 0.7f, 1.1f);
            player.spawnParticle(Particle.SPORE_BLOSSOM_AIR, center, 8, 0.3, 0.3, 0.3, 0);
        } else {
            player.playSound(center, Sound.BLOCK_CROP_BREAK, 0.8f, 1f);
            player.spawnParticle(Particle.HAPPY_VILLAGER, center, 5, 0.3, 0.2, 0.3, 0);
        }
        messages.actionBar(player, "farming.harvested-" + quality.key(), "crop", cropName(player, crop.id()),
                "count", String.valueOf(count), "cap", String.valueOf(crop.dailyCap()));
        if (quality == Quality.GOLDEN) {
            player.showTitle(Title.title(Component.empty(), messages.get(player, "farming.golden-title", "crop", cropName(player, crop.id()))));
            player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
            player.spawnParticle(Particle.WAX_OFF, center, 15, 0.3, 0.3, 0.3, 0);
        }
        if (count == crop.dailyCap()) {
            messages.send(player, "farming.cap-now", "crop", cropName(player, crop.id()), "cap", String.valueOf(crop.dailyCap()));
        }
        Bukkit.getPluginManager().callEvent(new HubActivityEvent(player, "harvest", crop.id(), quality.ordinal(), false));
    }

    /** La culture se replante (âge 0) puis repousse étape par étape jusqu'à mûrir. */
    private void regrow(Block block, Crop crop) {
        if (!(block.getBlockData() instanceof Ageable ageable)) {
            return;
        }
        int max = ageable.getMaximumAge();
        ageable.setAge(0);
        block.setBlockData(ageable, false);
        Location location = block.getLocation();
        long step = Math.max(20, crop.regrowSeconds() * 20L / Math.max(1, max));
        BukkitTask previous = growing.remove(location);
        if (previous != null) {
            previous.cancel();
        }
        growing.put(location, Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Block current = location.getBlock();
            if (current.getType() != crop.block() || !(current.getBlockData() instanceof Ageable data)) {
                stopGrowing(location);
                return;
            }
            data.setAge(Math.min(max, data.getAge() + 1));
            current.setBlockData(data, false);
            if (data.getAge() >= max) {
                stopGrowing(location);
            }
        }, step, step));
    }

    private void stopGrowing(Location location) {
        BukkitTask task = growing.remove(location);
        if (task != null) {
            task.cancel();
        }
    }

    /** Les fleurs tombent (le bloc devient picked, sans jamais se décomposer), puis reviennent. */
    private void pick(Block block, Crop crop) {
        Location location = block.getLocation();
        BlockData original = block.getBlockData().clone();
        BlockData bare = crop.picked().createBlockData();
        if (bare instanceof Leaves leaves) {
            leaves.setPersistent(true);
        }
        block.setBlockData(bare, false);
        picked.put(location, original);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            BlockData back = picked.remove(location);
            if (back != null && location.getBlock().getType() == crop.picked()) {
                location.getBlock().setBlockData(back, false);
            }
        }, crop.regrowSeconds() * 20L);
    }

    /** Arrêt : tout repousse d'un coup, les vignes refleurissent (le monde reste beau). */
    public void restoreAll() {
        new ArrayList<>(growing.keySet()).forEach(location -> {
            stopGrowing(location);
            if (location.getBlock().getBlockData() instanceof Ageable data) {
                data.setAge(data.getMaximumAge());
                location.getBlock().setBlockData(data, false);
            }
        });
        picked.forEach((location, data) -> location.getBlock().setBlockData(data, false));
        picked.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        today.remove(uuid);
        loadedDay.remove(uuid);
        lastNotice.remove(uuid);
    }

    /** Une récolte tombée faute de place : seul celui qui l'a récoltée la ramasse. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && read(event.getItem().getItemStack()).isPresent()
                && player.getUniqueId().equals(event.getItem().getOwner())) {
            event.setCancelled(false);
        }
    }

    // ---------- Objets ----------

    private ItemStack item(Player player, Crop crop, Quality quality, boolean bonus) {
        ItemStack stack = ItemStack.of(crop.item());
        stack.editMeta(meta -> {
            meta.customName(messages.get(player, "farming.item.name-" + quality.key(), "crop", cropName(player, crop.id()))
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(messages.get(player, "farming.quality." + quality.key()).decoration(TextDecoration.ITALIC, false));
            if (bonus) {
                lore.add(messages.get(player, "farming.item.bonus").decoration(TextDecoration.ITALIC, false));
            }
            lore.add(messages.get(player, "farming.item.origin").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.set(cropKey, PersistentDataType.STRING, crop.id());
            data.set(qualityKey, PersistentDataType.STRING, quality.key());
            if (bonus) {
                data.set(bonusKey, PersistentDataType.BYTE, (byte) 1);
            }
            if (quality == Quality.GOLDEN) {
                meta.setEnchantmentGlintOverride(true);
            }
        });
        return stack;
    }

    /** Une récolte du village (vide pour un objet ordinaire). */
    public Optional<Produce> read(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        PersistentDataContainer data = stack.getItemMeta().getPersistentDataContainer();
        String crop = data.get(cropKey, PersistentDataType.STRING);
        if (crop == null) {
            return Optional.empty();
        }
        return Optional.of(new Produce(crop, Quality.parse(data.get(qualityKey, PersistentDataType.STRING)), data.has(bonusKey)));
    }

    public String cropName(Player viewer, String crop) {
        String raw = messages.raw(viewer, "farming.crops." + crop);
        return raw != null ? messages.plain(viewer, "farming.crops." + crop) : crop;
    }

    Messages messages() {
        return messages;
    }

    HubSettings settings() {
        return settings;
    }

    HubStats stats() {
        return stats;
    }

    JavaPlugin plugin() {
        return plugin;
    }

    ZoneService zones() {
        return zones;
    }

    // ---------- /harvest : le carnet des récoltes ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) {
            openJournal(player);
        } else {
            messages.send(sender, "command.players-only");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }

    public void openJournal(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> store.totals(uuid), totals -> player.openInventory(new Journal(player, totals).getInventory()),
                () -> messages.send(player, "error.generic"));
    }

    private final class Journal implements Menu {

        private static final int[] SLOTS = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};
        private static final int DAILY = 4;
        private static final int BACK = 31;
        private final Inventory inventory;

        Journal(Player viewer, Map<String, long[]> totals) {
            inventory = Bukkit.createInventory(this, 36, messages.get(viewer, "farming.journal.title"));
            Frame.draw(inventory, Material.LIME_STAINED_GLASS_PANE);
            int i = 0;
            for (Crop crop : crops.values()) {
                if (i >= SLOTS.length) {
                    break;
                }
                long[] counts = totals.getOrDefault(crop.id(), new long[3]);
                int done = count(viewer.getUniqueId(), crop.id());
                inventory.setItem(SLOTS[i++], Items.item(crop.item(), messages.get(viewer, "farming.journal.crop", "crop", cropName(viewer, crop.id())),
                        List.of(messages.get(viewer, done >= crop.dailyCap() ? "farming.journal.today-full" : "farming.journal.today",
                                        "count", String.valueOf(done), "cap", String.valueOf(crop.dailyCap())),
                                messages.get(viewer, "farming.journal.total", "count", String.valueOf(counts[0] + counts[1] + counts[2])),
                                messages.get(viewer, "farming.journal.fine", "count", String.valueOf(counts[1])),
                                messages.get(viewer, "farming.journal.golden", "count", String.valueOf(counts[2])))));
            }
            String daily = parcelOfTheDay().orElse("");
            inventory.setItem(DAILY, Items.item(Material.HAY_BLOCK, messages.get(viewer, "farming.journal.daily",
                    "parcel", daily.isEmpty() ? "-" : zones.plainName(viewer, daily)), List.of(messages.get(viewer, "farming.journal.daily-lore")), true));
            inventory.setItem(BACK, back.item(viewer));
        }

        @Override
        public void onClick(Player player, int slot, ClickType click) {
            if (slot == BACK) {
                back.click(player);
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Pour le vendeur : la culture d'une récolte. */
    Optional<Crop> crop(String id) {
        return Optional.ofNullable(crops.get(id));
    }
}
