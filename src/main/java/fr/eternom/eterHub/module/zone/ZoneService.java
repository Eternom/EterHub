package fr.eternom.eterHub.module.zone;

import com.google.gson.JsonObject;
import fr.eternom.eterHub.api.HubActivityEvent;
import fr.eternom.eterHub.api.HubZoneEnterEvent;
import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.HubStats;
import fr.eternom.eterHub.module.core.Rewards;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.action.ActionLine;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Les lieux du village : où est chaque joueur, ce qui se passe quand il entre dans un lieu (titre, son, heure et météo
 * vues par lui, découverte), l'accès réservé (permission ou condition d'EterLib : le Donjon secret se mérite), et
 * l'ambiance (particules, son qui revient). Une zone n'est vérifiée qu'aux changements de bloc. Thread principal.
 */
public class ZoneService {

    public static final String GRANTED_ACTION = "eterhub:zone-granted";
    public static final String BYPASS = "eterhub.zone.bypass";
    private static final String CHANGED = "zones";

    private final JavaPlugin plugin;
    private final ZoneStore store;
    private final Discoveries discoveries;
    private final HubSettings settings;
    private final HubStats stats;
    private final Messages messages;
    private final NetworkBus bus;
    private volatile Map<String, Zone> zones;
    private volatile Zone wilderness;
    private volatile ZoneIndex index = ZoneIndex.EMPTY;
    private volatile Map<String, ZoneStore.Warp> warps = Map.of();
    private final Map<UUID, String> current = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSound = new ConcurrentHashMap<>();
    private final Map<UUID, Active> passages = new ConcurrentHashMap<>();
    private java.util.function.Consumer<Player> sceneChanged = player -> { };
    private final Map<UUID, Long> lastDenied = new ConcurrentHashMap<>();
    /** Accès accordé par la condition d'EterLib, le temps d'une vérification. */
    private final Map<UUID, String> granted = new ConcurrentHashMap<>();

    public ZoneService(JavaPlugin plugin, ZoneStore store, Discoveries discoveries, HubSettings settings, HubStats stats,
                       Messages messages, NetworkBus bus) {
        this.plugin = plugin;
        this.store = store;
        this.discoveries = discoveries;
        this.settings = settings;
        this.stats = stats;
        this.messages = messages;
        this.bus = bus;
        loadDefinitions();
        bus.on(CHANGED, data -> reload());
        EterLib.get().getActions().register(plugin, GRANTED_ACTION, "Interne : accès accordé à une zone réservée",
                List.of(fr.eternom.eterLib.module.action.Actions.Argument.required("zone")),
                (player, args) -> granted.put(player.getUniqueId(), args.getFirst()));
    }

    /** Relit les descriptions de config.yml (rechargement). */
    public void loadDefinitions() {
        zones = Zone.load(plugin.getConfig(), plugin.getLogger());
        wilderness = Zone.wilderness(plugin.getConfig(), plugin.getLogger());
    }

    public void start() {
        reload();
        Bukkit.getScheduler().runTaskTimer(plugin, this::ambience, 40, 20);
    }

    /** Relit les limites et points d'arrivée en base (démarrage, ou changés sur un autre lobby). */
    public void reload() {
        Tasks.async(plugin, () -> {
            Map<String, Region> regions = store.regions();
            Map<String, ZoneStore.Warp> freshWarps = store.warps();
            Bukkit.getScheduler().runTask(plugin, () -> {
                index = new ZoneIndex(regions);
                warps = freshWarps;
                Bukkit.getOnlinePlayers().forEach(player -> update(player, player.getLocation(), null));
            });
        }, "Zones non relues");
    }

    /** Les zones ont changé ici (éditeur) : relues partout. */
    public void changed() {
        reload();
        Tasks.async(plugin, () -> bus.publish(CHANGED, new JsonObject()), "Zones non annoncées");
    }

    // ---------- Lecture ----------

    public Map<String, Zone> zones() {
        return zones;
    }

    public Optional<Zone> zone(String id) {
        return Optional.ofNullable(zones.get(id));
    }

    public ZoneIndex index() {
        return index;
    }

    public Optional<ZoneStore.Warp> warp(String id) {
        return Optional.ofNullable(warps.get(id));
    }

    /** La zone où est le joueur (wilderness hors zone). */
    public Zone of(Player player) {
        String id = current.get(player.getUniqueId());
        return id == null ? wilderness : zones.getOrDefault(id, wilderness);
    }

    public String idOf(Player player) {
        return current.getOrDefault(player.getUniqueId(), "");
    }

    /** La zone d'une position (wilderness hors zone, ou zone tracée mais retirée de config.yml). */
    public Zone at(Location location) {
        return index.at(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ())
                .map(id -> zones.getOrDefault(id, wilderness)).orElse(wilderness);
    }

    public boolean allows(Player player, ZoneRule rule) {
        return of(player).allows(rule);
    }

    public boolean allows(Location location, ZoneRule rule) {
        return at(location).allows(rule);
    }

    /** Nom affiché d'une zone (lang/ zones.<id>.name, sinon l'identifiant). */
    public Component name(Player viewer, String id) {
        String raw = messages.raw(viewer, "zones." + id + ".name");
        return raw != null ? messages.get(viewer, "zones." + id + ".name") : Component.text(id);
    }

    public String plainName(Player viewer, String id) {
        String raw = messages.raw(viewer, "zones." + id + ".name");
        return raw != null ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(messages.get(viewer, "zones." + id + ".name")) : id;
    }

    /** Les zones à découvrir (discoverable). */
    public long discoverableCount() {
        return zones.values().stream().filter(Zone::discoverable).count();
    }

    public long discoveredCount(UUID player) {
        return discoveries.of(player).stream().filter(id -> zones.containsKey(id) && zones.get(id).discoverable()).count();
    }

    public Discoveries discoveries() {
        return discoveries;
    }

    // ---------- Accès ----------

    /** Le joueur peut entrer dans cette zone (permission, condition d'EterLib, ou staff). */
    public boolean canEnter(Player player, Zone zone) {
        if (!zone.restricted() || player.hasPermission(BYPASS)) {
            return true;
        }
        if (!zone.permission().isEmpty() && !player.hasPermission(zone.permission())) {
            return false;
        }
        if (!zone.condition().isEmpty()) {
            return testCondition(player, zone);
        }
        return true;
    }

    /**
     * La condition d'EterLib (ex : « ?eterjob:rank mineur expert ») est lancée suivie de eterhub:zone-granted : si la
     * condition est remplie, la suite s'exécute et l'accès est noté. Une condition fournie seulement par un autre
     * serveur n'est pas lancée (elle enverrait le joueur ailleurs) : accès refusé.
     */
    private boolean testCondition(Player player, Zone zone) {
        String condition = zone.condition().startsWith("?") ? zone.condition() : "?" + zone.condition();
        try {
            ActionLine line = ActionLine.parse(condition);
            if (!EterLib.get().getActions().isLocal(line.id())) {
                return false;
            }
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("zones." + zone.id() + ".access.condition illisible : " + e.getMessage());
            return false;
        }
        granted.remove(player.getUniqueId());
        EterLib.get().getActions().run(player, List.of(condition, GRANTED_ACTION + " " + zone.id()));
        return zone.id().equals(granted.remove(player.getUniqueId()));
    }

    // ---------- Suivi ----------

    /**
     * Le joueur va en to (changement de bloc, téléportation, arrivée). false = entrée refusée (zone réservée) : l'appelant
     * annule le mouvement. from : d'où il vient (null à l'arrivée). Ici : à pied.
     */
    public boolean update(Player player, Location to, Location from) {
        return update(player, to, from, false);
    }

    /** teleport : le titre du lieu d'arrivée s'affiche toujours (même parcelle -> parent, même sortie de la Crypte). */
    public boolean update(Player player, Location to, Location from, boolean teleport) {
        UUID uuid = player.getUniqueId();
        String before = current.getOrDefault(uuid, "");
        String after = index.at(to.getWorld().getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ())
                .filter(zones::containsKey).orElse("");
        if (before.equals(after)) {
            return true;
        }
        Zone zone = after.isEmpty() ? wilderness : zones.get(after);
        if (!canEnter(player, zone)) {
            if (from == null) {
                // Arrivée dans une zone interdite (ex : dernière position) : au spawn
                return false;
            }
            long now = System.currentTimeMillis();
            Long last = lastDenied.get(uuid);
            if (last == null || now - last > 2000) {
                lastDenied.put(uuid, now);
                messages.send(player, "zone.denied", "zone", plainName(player, after));
                player.playSound(Sound.sound(Key.key("minecraft:block.iron_door.close"), Sound.Source.MASTER, 0.6f, 1f));
            }
            // Retour en douceur : un petit pas en arrière
            Vector back = from.toVector().subtract(to.toVector()).setY(0);
            if (back.lengthSquared() > 0) {
                Bukkit.getScheduler().runTask(plugin, () -> player.setVelocity(back.normalize().multiply(0.4).setY(0.2)));
            }
            return false;
        }
        if (after.isEmpty()) {
            current.remove(uuid);
        } else {
            current.put(uuid, after);
        }
        Zone old = before.isEmpty() ? wilderness : zones.getOrDefault(before, wilderness);
        leave(player, old, zone);
        scene(player, old, zone);
        // À pied, d'une parcelle à son lieu parent (ou l'inverse), ou en ressortant d'un lieu vers celui qui l'entoure
        // (inside : la Crypte -> le Cimetière) : pas de nouveau titre ni de son d'entrée
        boolean sameFamily = !teleport && !before.isEmpty() && !after.isEmpty()
                && (old.root().equals(zone.root()) || old.inside().contains(after));
        boolean first = false;
        if (!after.isEmpty()) {
            stats.add("zone:" + after);
            enter(player, zone, !sameFamily);
            if (zone.discoverable() && settings.bool("activities.discovery", true) && discoveries.isLoaded(uuid)) {
                first = discoveries.discover(uuid, after);
                if (first) {
                    discovered(player, zone);
                }
            }
        }
        Bukkit.getPluginManager().callEvent(new HubZoneEnterEvent(player, before, after, first));
        return true;
    }

    public void quit(Player player) {
        current.remove(player.getUniqueId());
        String prefix = player.getUniqueId() + "#";
        lastSound.keySet().removeIf(key -> key.startsWith(prefix));
        lastDenied.remove(player.getUniqueId());
    }

    private void leave(Player player, Zone old, Zone next) {
        if (old.time() >= 0 && next.time() < 0) {
            player.resetPlayerTime();
        }
        if (!old.weather().isEmpty() && next.weather().isEmpty()) {
            player.resetPlayerWeather();
        }
    }

    private void enter(Player player, Zone zone, boolean announce) {
        if (zone.title() && announce) {
            String subtitle = messages.raw(player, "zones." + zone.id() + ".subtitle");
            player.showTitle(Title.title(name(player, zone.id()),
                    subtitle != null ? messages.get(player, "zones." + zone.id() + ".subtitle") : Component.empty(),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(1800), Duration.ofMillis(600))));
        }
        if (announce) {
            play(player, zone.enterSound(), 0.8f);
        }
        if (zone.time() >= 0) {
            player.setPlayerTime(zone.time(), false);
        }
        switch (zone.weather()) {
            case "rain", "snow", "downfall" -> player.setPlayerWeather(WeatherType.DOWNFALL);
            case "clear" -> player.setPlayerWeather(WeatherType.CLEAR);
            default -> {
            }
        }
    }

    private void discovered(Player player, Zone zone) {
        long total = discoverableCount();
        long found = discoveredCount(player.getUniqueId());
        messages.send(player, "zone.discovered", "zone", plainName(player, zone.id()), "count", String.valueOf(found),
                "total", String.valueOf(total));
        player.playSound(Sound.sound(Key.key("minecraft:ui.toast.challenge_complete"), Sound.Source.MASTER, 0.5f, 1.4f));
        stats.add("discovery:" + zone.id());
        Rewards.give(plugin, player, plugin.getConfig().getStringList("discovery.reward-each"));
        if (found >= total && total > 0) {
            messages.send(player, "zone.all-discovered", "total", String.valueOf(total));
            Rewards.give(plugin, player, plugin.getConfig().getStringList("discovery.reward-all"));
            Bukkit.getPluginManager().callEvent(new HubActivityEvent(player, "discovery-complete", "", total, false));
        }
    }

    // ---------- Ambiance ----------

    /** Secondes écoulées (rythme des couches : every). */
    private long second;

    /** Chaque seconde : les couches de particules et les sons du lieu de chaque joueur (hors lieu : zone-defaults). */
    private void ambience() {
        second++;
        long now = System.currentTimeMillis();
        boolean halloween = halloween();
        boolean showParticles = settings.bool("cosmetics.zone-particles", true);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Player player : Bukkit.getOnlinePlayers()) {
            String id = current.get(player.getUniqueId());
            Active active = passages.get(player.getUniqueId());
            if (active != null && now - active.started() > active.source().scene().passage().timeout() * 1000L) {
                endPassage(player, "zone.passage-timeout");
                active = null;
            }
            // Pendant un passage : l'ambiance et la mise en scène du lieu de départ (la Crypte) suivent le joueur
            Zone zone = active != null ? active.source() : id == null ? wilderness : zones.getOrDefault(id, wilderness);
            if (showParticles) {
                for (Zone.Effect effect : zone.particles()) {
                    if (second % effect.every() == 0 && random.nextDouble() < effect.chance()) {
                        particles(player, effect);
                    }
                }
            }
            for (Zone.Status status : zone.scene().effects()) {
                if (second % status.every() == 0 && random.nextDouble() < status.chance()) {
                    player.addPotionEffect(new org.bukkit.potion.PotionEffect(status.type(), status.seconds() * 20, status.amplifier(), true, false, false));
                }
            }
            if (zone.halloween() && halloween) {
                particles(player, new Zone.Effect(Particle.SOUL, 3, 10, 0, 4, 1, 1, 0));
                if (random.nextInt(20) == 0) {
                    play(player, "minecraft:ambient.cave", 0.5f);
                }
            }
            List<Zone.Ambient> sounds = zone.sounds();
            for (int i = 0; i < sounds.size(); i++) {
                Zone.Ambient sound = sounds.get(i);
                String key = player.getUniqueId() + "#" + zone.id() + "#" + i;
                Long last = lastSound.get(key);
                if (last != null && now - last < sound.every() * 1000L) {
                    continue;
                }
                lastSound.put(key, now);
                if (last != null && random.nextDouble() < sound.chance()) {
                    ambient(player, sound);
                }
            }
        }
    }

    /** Particules d'une couche, vues par ce joueur seulement, autour de lui. */
    private static void particles(Player player, Zone.Effect effect) {
        World world = player.getWorld();
        Location base = player.getLocation();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double radius = effect.radius();
        double minY = Math.min(effect.minY(), effect.maxY());
        double maxY = Math.max(effect.minY(), effect.maxY()) + 0.01;
        for (int i = 0; i < effect.count(); i++) {
            Location at = base.clone().add(random.nextDouble(-radius, radius), random.nextDouble(minY, maxY), random.nextDouble(-radius, radius));
            if (world == at.getWorld() && at.getBlock().isPassable()) {
                player.spawnParticle(effect.type(), at, 1, 0, 0, 0, effect.speed());
            }
        }
    }

    /** Un son d'ambiance, un peu autour du joueur (il semble venir des alentours), hauteur légèrement variée. */
    private void ambient(Player player, Zone.Ambient sound) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        float pitch = (float) Math.max(0.5, Math.min(2, sound.pitch() + random.nextDouble(-sound.pitchSpread(), sound.pitchSpread() + 1.0E-6)));
        Location at = player.getLocation().add(random.nextDouble(-6, 6), random.nextDouble(0, 3), random.nextDouble(-6, 6));
        try {
            player.playSound(Sound.sound(Key.key(sound.sound()), Sound.Source.AMBIENT, sound.volume(), pitch), at.getX(), at.getY(), at.getZ());
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Son d'ambiance invalide : " + sound.sound());
        }
    }

    /** Mise en scène : actions de sortie de l'ancien lieu, effets retirés, actions d'entrée du nouveau. */
    private void scene(Player player, Zone old, Zone next) {
        if (old == next) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Active active = passages.get(uuid);
        if (active != null) {
            Zone.Passage passage = active.source().scene().passage();
            if (next.id().equals(active.source().id()) || next.id().isEmpty() || passage.keep().contains(next.id())) {
                return; // toujours sur le chemin : la mise en scène continue
            }
            // L'arrivée (until) ou un autre lieu : le passage s'arrête
            endPassage(player, null);
            if (!next.scene().onEnter().isEmpty()) {
                EterLib.get().getActions().run(player, next.scene().onEnter());
            }
            return;
        }
        Zone.Passage passage = old.scene().passage();
        if (passage != null && (next.id().isEmpty() || passage.keep().contains(next.id()))) {
            // Sortie de la Crypte par le couloir : la mise en scène colle au joueur jusqu'à l'arrivée
            passages.put(uuid, new Active(old, System.currentTimeMillis()));
            return;
        }
        if (!old.scene().onLeave().isEmpty()) {
            EterLib.get().getActions().run(player, old.scene().onLeave());
        }
        for (Zone.Status status : old.scene().effects()) {
            if (next.scene().effects().stream().noneMatch(kept -> kept.type().equals(status.type()))) {
                player.removePotionEffect(status.type());
            }
        }
        if (!next.scene().onEnter().isEmpty()) {
            EterLib.get().getActions().run(player, next.scene().onEnter());
        }
    }

    /** Un passage en cours : la mise en scène du lieu de départ, et quand il a commencé. */
    private record Active(Zone source, long started) {
    }

    /** Fin d'un passage : effets retirés, actions de sortie du lieu de départ, visibilité remise à jour. */
    private void endPassage(Player player, String messageKey) {
        Active active = passages.remove(player.getUniqueId());
        if (active == null) {
            return;
        }
        active.source().scene().effects().forEach(status -> player.removePotionEffect(status.type()));
        if (!active.source().scene().onLeave().isEmpty()) {
            EterLib.get().getActions().run(player, active.source().scene().onLeave());
        }
        if (messageKey != null) {
            messages.send(player, messageKey);
        }
        sceneChanged.accept(player);
    }

    /** La mise en scène qui s'applique au joueur : celle de son passage en cours, sinon celle de son lieu. */
    private Zone.Scene sceneOf(Player player) {
        Active active = passages.get(player.getUniqueId());
        return active != null ? active.source().scene() : of(player).scene();
    }

    /** Appelé quand la mise en scène d'un joueur change hors d'un changement de lieu (fin de passage) : la visibilité. */
    public void setSceneChanged(java.util.function.Consumer<Player> listener) {
        this.sceneChanged = listener;
    }

    /** Le joueur est dans un lieu isolé (il ne voit personne, personne ne le voit). */
    public boolean isolated(Player player) {
        return sceneOf(player).isolation();
    }

    /** Le joueur est dans un lieu d'où l'on ne peut pas se téléporter. */
    public boolean blocksTeleport(Player player) {
        return sceneOf(player).noTeleport();
    }

    /** Départ : les effets du lieu sont retirés avant qu'EterSync ne garde le joueur. */
    public void clearEffects(Player player) {
        sceneOf(player).effects().forEach(status -> player.removePotionEffect(status.type()));
        passages.remove(player.getUniqueId());
    }

    /** Du 20 octobre au 2 novembre. */
    public static boolean halloween() {
        LocalDate today = LocalDate.now();
        return today.getMonth() == Month.OCTOBER && today.getDayOfMonth() >= 20
                || today.getMonth() == Month.NOVEMBER && today.getDayOfMonth() <= 2;
    }

    private void play(Player player, String sound, float volume) {
        if (sound == null || sound.isEmpty()) {
            return;
        }
        try {
            player.playSound(Sound.sound(Key.key(sound), Sound.Source.AMBIENT, volume, 1f));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Son de zone invalide : " + sound);
        }
    }
}
