package fr.eternom.eterHub.module.heads;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonObject;
import fr.eternom.eterHub.api.HubActivityEvent;
import fr.eternom.eterHub.api.HubHeadFoundEvent;
import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.HubStats;
import fr.eternom.eterHub.module.core.Rewards;
import fr.eternom.eterHub.module.zone.ZoneService;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.ranking.RankingSource;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * La chasse aux têtes : des têtes de joueur cachées dans le village, une chasse principale toujours ouverte et des
 * chasses à thème datées (config.yml > heads). Clic droit sur une tête = trouvée (une fois) : nom et texte, son (autre
 * pour une tête rare), récompenses (chaque tête, rare, paliers, fin), annonce sur tous les lobbys de qui finit et à
 * quel rang (une seule récompense finale, même si des têtes sont ajoutées ensuite : le joueur est prévenu qu'il y en a
 * de nouvelles). Indices : /heads hint (chaud / froid), têtes qui scintillent de près pour qui ne les a pas, menu par
 * lieu. Une chasse fermée ne compte plus et ses têtes disparaissent si hide-when-closed (reposées à sa réouverture).
 * Têtes et trouvailles en base, communes à tous les lobbys.
 */
public class HeadService implements Listener {

    private static final String CHANGED = "heads";
    private static final String COMPLETE = "heads-complete";
    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    private final JavaPlugin plugin;
    private final HeadStore store;
    private final ZoneService zones;
    private final HubSettings settings;
    private final HubStats stats;
    private final Messages messages;
    private final NetworkBus bus;
    private final NamespacedKey placeKey;
    private volatile Map<String, Hunt> hunts = Map.of();
    private volatile List<HeadStore.Head> heads = List.of();
    private volatile Map<String, HeadStore.Head> byBlock = Map.of();
    private final Map<UUID, Map<Long, Long>> found = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> finished = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastHint = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClosed = new ConcurrentHashMap<>();

    public HeadService(JavaPlugin plugin, HeadStore store, ZoneService zones, HubSettings settings, HubStats stats,
                       Messages messages, NetworkBus bus) {
        this.plugin = plugin;
        this.store = store;
        this.zones = zones;
        this.settings = settings;
        this.stats = stats;
        this.messages = messages;
        this.bus = bus;
        this.placeKey = new NamespacedKey(plugin, "head-hunt");
        loadHunts();
        bus.on(CHANGED, data -> reload());
        bus.on(COMPLETE, data -> announceHere(data.get("player").getAsString(), data.get("hunt").getAsString(),
                data.get("rank").getAsInt(), data.get("total").getAsInt()));
    }

    public void start() {
        reload();
        Bukkit.getScheduler().runTaskTimer(plugin, this::sparkle, 100, 60);
        Bukkit.getScheduler().runTaskTimer(plugin, this::applyVisibility, 200, 20 * 60);
        Bukkit.getServicesManager().register(RankingSource.class, source("hub_heads", false), plugin, ServicePriority.Normal);
        Bukkit.getServicesManager().register(RankingSource.class, source("hub_heads_finishers", true), plugin, ServicePriority.Normal);
    }

    public void loadHunts() {
        hunts = Hunt.load(plugin.getConfig().getConfigurationSection("heads"), plugin.getLogger());
    }

    public void reload() {
        Tasks.async(plugin, () -> {
            List<HeadStore.Head> fresh = store.all();
            Bukkit.getScheduler().runTask(plugin, () -> {
                heads = List.copyOf(fresh);
                byBlock = fresh.stream().collect(Collectors.toMap(HeadStore.Head::key, head -> head, (a, b) -> a));
                applyVisibility();
            });
        }, "Têtes non relues");
    }

    void changed() {
        reload();
        Tasks.async(plugin, () -> bus.publish(CHANGED, new JsonObject()), "Têtes non annoncées");
    }

    /** Bloquant (arrivée). */
    public void load(UUID player) {
        found.put(player, new ConcurrentHashMap<>(store.found(player)));
        finished.put(player, ConcurrentHashMap.newKeySet());
        finished.get(player).addAll(store.finished(player));
    }

    // ---------- Lecture ----------

    public Map<String, Hunt> hunts() {
        return hunts;
    }

    public List<HeadStore.Head> heads() {
        return heads;
    }

    HeadStore store() {
        return store;
    }

    Messages messages() {
        return messages;
    }

    JavaPlugin plugin() {
        return plugin;
    }

    ZoneService zones() {
        return zones;
    }

    NamespacedKey placeKey() {
        return placeKey;
    }

    public boolean isOpen(String hunt) {
        Hunt known = hunts.get(hunt);
        return known != null && known.openOn(LocalDate.now(ZONE));
    }

    /** Les chasses ouvertes aujourd'hui, la principale d'abord. */
    public List<Hunt> openHunts() {
        return hunts.values().stream().filter(hunt -> isOpen(hunt.id())).toList();
    }

    public List<HeadStore.Head> headsOf(String hunt) {
        return heads.stream().filter(head -> head.hunt().equals(hunt)).toList();
    }

    public int total(String hunt) {
        return (int) heads.stream().filter(head -> head.hunt().equals(hunt)).count();
    }

    public int count(UUID player, String hunt) {
        Map<Long, Long> mine = found.getOrDefault(player, Map.of());
        return (int) heads.stream().filter(head -> head.hunt().equals(hunt) && mine.containsKey(head.id())).count();
    }

    public Map<Long, Long> foundBy(UUID player) {
        return found.getOrDefault(player, Map.of());
    }

    /** Le lieu du village d'une tête (vide hors lieu). */
    public String zoneOf(HeadStore.Head head) {
        return zones.index().at(head.world(), head.x(), head.y(), head.z()).orElse("");
    }

    public String huntName(Player viewer, String hunt) {
        String raw = messages.raw(viewer, "heads.hunts." + hunt);
        return raw != null ? messages.plain(viewer, "heads.hunts." + hunt) : hunt;
    }

    public String zoneName(Player viewer, String zone) {
        return zone.isEmpty() ? messages.plain(viewer, "heads.no-zone") : zones.plainName(viewer, zone);
    }

    /** Le lieu où il manque le plus de têtes au joueur (chasses ouvertes), vide s'il ne lui en manque aucune. */
    public String mostMissingZone(UUID player) {
        Map<Long, Long> mine = found.getOrDefault(player, Map.of());
        return heads.stream().filter(head -> isOpen(head.hunt()) && !mine.containsKey(head.id()))
                .collect(Collectors.groupingBy(this::zoneOf, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
    }

    /** Lignes de progression (profil, hologramme) : la principale, puis les chasses à thème ouvertes. */
    public List<net.kyori.adventure.text.Component> progress(Player player) {
        List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
        for (Hunt hunt : openHunts()) {
            int total = total(hunt.id());
            if (total == 0) {
                continue;
            }
            lines.add(messages.get(player, hunt.isMain() ? "progress.heads" : "progress.heads-hunt", "count",
                    String.valueOf(count(player.getUniqueId(), hunt.id())), "total", String.valueOf(total), "hunt", huntName(player, hunt.id())));
        }
        return lines;
    }

    // ---------- Trouver ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || !isHead(block.getType())) {
            return;
        }
        HeadStore.Head head = byBlock.get(block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ());
        if (head == null || !settings.bool("activities.heads", true)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!isOpen(head.hunt())) {
            long now = System.currentTimeMillis();
            Long last = lastClosed.get(player.getUniqueId());
            if (last == null || now - last > 2000) {
                lastClosed.put(player.getUniqueId(), now);
                messages.actionBar(player, "heads.closed", "hunt", huntName(player, head.hunt()));
            }
            return;
        }
        Map<Long, Long> mine = found.get(player.getUniqueId());
        if (mine == null) {
            return;
        }
        String hunt = head.hunt();
        if (mine.containsKey(head.id())) {
            messages.actionBar(player, "heads.already", "count", String.valueOf(count(player.getUniqueId(), hunt)),
                    "total", String.valueOf(total(hunt)));
            return;
        }
        long now = System.currentTimeMillis();
        mine.put(head.id(), now);
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, () -> store.markFound(uuid, head.id(), now), "Tête trouvée non gardée");
        found(player, head, block);
    }

    private void found(Player player, HeadStore.Head head, Block block) {
        Hunt hunt = hunts.getOrDefault(head.hunt(), hunts.get(Hunt.MAIN));
        UUID uuid = player.getUniqueId();
        int count = count(uuid, head.hunt());
        int total = total(head.hunt());
        String zone = zoneOf(head);
        // Le message : nom et texte de la tête s'il y en a un, sinon le compteur seul
        if (head.name() != null && !head.name().isBlank()) {
            messages.send(player, head.rare() ? "heads.found-rare-named" : "heads.found-named", "name", head.name(),
                    "count", String.valueOf(count), "total", String.valueOf(total));
        } else {
            messages.send(player, head.rare() ? "heads.found-rare" : "heads.found", "count", String.valueOf(count),
                    "total", String.valueOf(total));
        }
        if (head.rare()) {
            player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.4f);
            player.spawnParticle(Particle.TOTEM_OF_UNDYING, block.getLocation().add(0.5, 0.5, 0.5), 30, 0.3, 0.3, 0.3, 0.2);
            Rewards.give(plugin, player, rewards(hunt.rewardRare(), "heads.reward-rare"));
        } else {
            player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
            player.spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3, 0);
        }
        stats.add("activity:heads");
        Rewards.give(plugin, player, hunt.isMain() ? plugin.getConfig().getStringList("heads.reward-each") : hunt.rewardEach());
        List<String> milestone = hunt.milestones().get(count);
        if (milestone != null && count < total) {
            messages.send(player, "heads.milestone", "count", String.valueOf(count), "hunt", huntName(player, hunt.id()));
            Rewards.give(plugin, player, milestone);
        }
        Bukkit.getPluginManager().callEvent(new HubHeadFoundEvent(player, head.id(), head.name() == null ? "" : head.name(), zone,
                head.hunt(), head.rare(), count, total));
        Bukkit.getPluginManager().callEvent(new HubActivityEvent(player, "head", head.hunt() + ":" + head.id(), count, false));
        if (count >= total && total > 0) {
            complete(player, hunt, total);
        }
    }

    private List<String> rewards(List<String> own, String fallbackPath) {
        return own.isEmpty() ? plugin.getConfig().getStringList(fallbackPath) : own;
    }

    /** Chasse finie : la récompense finale une seule fois, et l'annonce avec le rang d'arrivée. */
    private void complete(Player player, Hunt hunt, int total) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Tasks.async(plugin, player, () -> store.finish(uuid, hunt.id()), rank -> {
            finished.computeIfAbsent(uuid, key -> ConcurrentHashMap.newKeySet()).add(hunt.id());
            if (rank == 0) {
                // Déjà finie avant l'ajout de nouvelles têtes : pas de deuxième récompense finale
                messages.send(player, "heads.all-again", "hunt", huntName(player, hunt.id()));
                return;
            }
            messages.send(player, "heads.all", "total", String.valueOf(total), "hunt", huntName(player, hunt.id()),
                    "rank", String.valueOf(rank));
            player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1f);
            Rewards.give(plugin, player, hunt.isMain() ? plugin.getConfig().getStringList("heads.reward-all") : hunt.rewardAll());
            if (settings.bool("heads.announce-complete", true)) {
                announceHere(name, hunt.id(), rank, total);
                JsonObject data = new JsonObject();
                data.addProperty("player", name);
                data.addProperty("hunt", hunt.id());
                data.addProperty("rank", rank);
                data.addProperty("total", total);
                Tasks.async(plugin, () -> bus.publish(COMPLETE, data), "Fin de chasse non annoncée");
            }
        }, () -> { });
    }

    private void announceHere(String name, String hunt, int rank, int total) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            messages.send(viewer, "heads.announce", "player", name, "hunt", huntName(viewer, hunt), "rank", String.valueOf(rank),
                    "total", String.valueOf(total));
        }
    }

    /** Arrivée : s'il avait fini une chasse et que des têtes y ont été ajoutées, il est prévenu. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !settings.bool("activities.heads", true)) {
                return;
            }
            Set<String> done = finished.getOrDefault(player.getUniqueId(), Set.of());
            for (Hunt hunt : openHunts()) {
                int missing = total(hunt.id()) - count(player.getUniqueId(), hunt.id());
                if (done.contains(hunt.id()) && missing > 0) {
                    messages.send(player, "heads.new-heads", "count", String.valueOf(missing), "hunt", huntName(player, hunt.id()));
                }
            }
        }, 80);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        found.remove(uuid);
        finished.remove(uuid);
        lastHint.remove(uuid);
        lastClosed.remove(uuid);
    }

    // ---------- Indices ----------

    /** Chaud / froid : la tête non trouvée la plus proche (chasses ouvertes, ce monde), sans dire où. */
    public void hint(Player player) {
        if (!settings.bool("heads.hint.enabled", true)) {
            messages.send(player, "heads.hint-off");
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastHint.get(player.getUniqueId());
        long cooldown = (long) (settings.number("heads.hint.cooldown-seconds", 60) * 1000);
        if (last != null && now - last < cooldown) {
            messages.send(player, "heads.hint-wait", "seconds", String.valueOf((cooldown - (now - last) + 999) / 1000));
            return;
        }
        Map<Long, Long> mine = found.getOrDefault(player.getUniqueId(), Map.of());
        List<HeadStore.Head> missing = heads.stream().filter(head -> isOpen(head.hunt()) && !mine.containsKey(head.id())).toList();
        if (missing.isEmpty()) {
            messages.send(player, "heads.hint-none");
            return;
        }
        int maxMissing = settings.integer("heads.hint.max-missing", 5);
        if (maxMissing > 0 && missing.size() > maxMissing) {
            messages.send(player, "heads.hint-too-many", "max", String.valueOf(maxMissing), "zone",
                    zoneName(player, mostMissingZone(player.getUniqueId())));
            return;
        }
        Location at = player.getLocation();
        Optional<HeadStore.Head> nearest = missing.stream().filter(head -> head.world().equals(at.getWorld().getName()))
                .min(Comparator.comparingDouble(head -> distanceSquared(head, at)));
        lastHint.put(player.getUniqueId(), now);
        if (nearest.isEmpty()) {
            messages.send(player, "heads.hint-elsewhere");
            return;
        }
        double distance = Math.sqrt(distanceSquared(nearest.get(), at));
        String band = distance < 8 ? "burning" : distance < 20 ? "hot" : distance < 45 ? "warm" : distance < 90 ? "cool" : "cold";
        messages.send(player, "heads.hint." + band);
        player.playSound(player, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, distance < 8 ? 2f : distance < 20 ? 1.6f : distance < 45 ? 1.2f : 0.8f);
    }

    private static double distanceSquared(HeadStore.Head head, Location at) {
        double dx = head.x() + 0.5 - at.getX();
        double dy = head.y() + 0.5 - at.getY();
        double dz = head.z() + 0.5 - at.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Toutes les 3 s : les têtes proches non trouvées scintillent, pour ce joueur seulement. */
    private void sparkle() {
        if (!settings.bool("heads.sparkle.enabled", true) || !settings.bool("activities.heads", true)) {
            return;
        }
        double distance = settings.number("heads.sparkle.distance", 10);
        double max = distance * distance;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<Long, Long> mine = found.get(player.getUniqueId());
            if (mine == null) {
                continue;
            }
            Location at = player.getLocation();
            String world = at.getWorld().getName();
            for (HeadStore.Head head : heads) {
                if (head.world().equals(world) && !mine.containsKey(head.id()) && isOpen(head.hunt()) && distanceSquared(head, at) <= max) {
                    player.spawnParticle(head.rare() ? Particle.END_ROD : Particle.WAX_OFF,
                            new Location(at.getWorld(), head.x() + 0.5, head.y() + 0.6, head.z() + 0.5), 2, 0.25, 0.2, 0.25, 0);
                }
            }
        }
    }

    // ---------- Chasses fermées : têtes cachées, reposées à la réouverture ----------

    /** Toutes les minutes (et au démarrage) : seulement les chunks déjà chargés, jamais de chargement forcé. */
    private void applyVisibility() {
        for (HeadStore.Head head : heads) {
            Hunt hunt = hunts.get(head.hunt());
            World world = Bukkit.getWorld(head.world());
            if (hunt == null || world == null || !world.isChunkLoaded(head.x() >> 4, head.z() >> 4)) {
                continue;
            }
            Block block = world.getBlockAt(head.x(), head.y(), head.z());
            boolean hide = hunt.hideWhenClosed() && !hunt.openOn(LocalDate.now(ZONE));
            if (hide && isHead(block.getType())) {
                block.setType(Material.AIR, false);
            } else if (!hide && block.getType().isAir() && head.blockData() != null) {
                restore(block, head);
            }
        }
    }

    private void restore(Block block, HeadStore.Head head) {
        try {
            block.setBlockData(Bukkit.createBlockData(head.blockData()), false);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (head.texture() != null && block.getState(false) instanceof Skull skull) {
            PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(head.texture().getBytes()), "");
            profile.setProperty(new ProfileProperty("textures", head.texture(), head.signature()));
            skull.setPlayerProfile(profile);
            skull.update(true, false);
        }
    }

    // ---------- Pose par le staff ----------

    /** Ajoute la tête de ce bloc à une chasse (bloc et texture gardés). Thread principal ; base en tâche de fond. */
    void add(Player player, Block block, String hunt, Runnable after) {
        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        String data = block.getBlockData().getAsString();
        String texture = null;
        String signature = null;
        if (block.getState(false) instanceof Skull skull && skull.getPlayerProfile() != null) {
            for (ProfileProperty property : skull.getPlayerProfile().getProperties()) {
                if ("textures".equals(property.getName())) {
                    texture = property.getValue();
                    signature = property.getSignature();
                }
            }
        }
        HeadStore.Head head = new HeadStore.Head(0, world, x, y, z, hunt, null, false, data, texture, signature);
        String author = player.getName();
        Tasks.async(plugin, player, () -> {
            if (store.exists(world, x, y, z)) {
                return false;
            }
            store.add(head);
            settings.log("head", null, hunt + " " + x + "," + y + "," + z, author);
            return true;
        }, added -> {
            messages.send(player, added ? "editor.head.added" : "editor.head.exists", "total", String.valueOf(total(hunt) + (added ? 1 : 0)),
                    "hunt", huntName(player, hunt));
            changed();
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    /** Une tête donnée par /eterhub head give, posée en mode construction : ajoutée tout de suite à sa chasse. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        String hunt = event.getItemInHand().hasItemMeta()
                ? event.getItemInHand().getItemMeta().getPersistentDataContainer().get(placeKey, PersistentDataType.STRING) : null;
        if (hunt == null || !isHead(event.getBlockPlaced().getType())) {
            return;
        }
        Block block = event.getBlockPlaced();
        Bukkit.getScheduler().runTask(plugin, () -> add(event.getPlayer(), block, hunts.containsKey(hunt) ? hunt : Hunt.MAIN, () -> { }));
    }

    /** Une tête prête à poser pour une chasse : texture de la config, ou celle d'un joueur. */
    ItemStack headItem(String texture, String owner, String hunt) {
        ItemStack item = ItemStack.of(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> {
            PlayerProfile profile;
            if (texture != null) {
                profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(texture.getBytes()), "");
                profile.setProperty(new ProfileProperty("textures", texture));
            } else {
                profile = Bukkit.createProfile(owner);
            }
            meta.setPlayerProfile(profile);
            meta.getPersistentDataContainer().set(placeKey, PersistentDataType.STRING, hunt);
            meta.lore(List.of(messages.get(Bukkit.getConsoleSender(), "editor.head.item-lore", "hunt", hunt)));
        });
        return item;
    }

    static boolean isHead(Material type) {
        return type == Material.PLAYER_HEAD || type == Material.PLAYER_WALL_HEAD;
    }

    HeadStore.Head at(Block block) {
        return byBlock.get(block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ());
    }

    // ---------- Classements ----------

    private RankingSource source(String id, boolean finishers) {
        return new RankingSource() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<Ranked> top(int limit) {
                List<HeadStore.Ranked> top = finishers ? store.finishers(Hunt.MAIN, limit) : store.topFound(Hunt.MAIN, limit);
                return top.stream().map(entry -> new Ranked(entry.player(), entry.value())).toList();
            }

            @Override
            public long[] of(UUID player) {
                List<HeadStore.Ranked> all = finishers ? store.finishers(Hunt.MAIN, 1000) : store.topFound(Hunt.MAIN, 1000);
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i).player().equals(player)) {
                        return new long[]{i + 1, all.get(i).value()};
                    }
                }
                return new long[]{0, 0};
            }
        };
    }

    /** Pour le menu du staff : combien de joueurs ont trouvé chaque tête, et combien chassent. Bloquant. */
    Map<Long, Integer> foundCounts() {
        return store.foundCounts();
    }
}
