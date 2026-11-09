package fr.eternom.eterHub.module.activity;

import com.google.gson.JsonObject;
import fr.eternom.eterHub.api.HubActivityEvent;
import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.HubStats;
import fr.eternom.eterHub.module.core.Rewards;
import fr.eternom.eterHub.module.core.SubCommand;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.ranking.RankingSource;
import fr.eternom.eterLib.helper.task.Tasks;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les parcours d'aventure (Mines, Grotte de glace, Grotte aux araignées...) : marcher sur le départ lance le chrono
 * (barre d'action), les points de passage se prennent dans l'ordre, l'arrivée donne le temps. Deux accroupissements
 * rapides, une chute (parkour.fall-distance sous le dernier point) ou le vide ramènent au dernier point. Pas de double
 * saut pendant un parcours. Premier parcours fini : parkour.reward-first ; record personnel : parkour.reward-record.
 * Temps en base (eterhub_parkour_times), classement par parcours (/parcours, et la source « hub_parkour_<id> »).
 * Éditeur : /eterhub parkour start|checkpoint|undo|finish|delete|list <parcours> (le bloc où l'on se tient).
 */
public class Parkour implements Listener, SubCommand, TabExecutor {

    private static final String CHANGED = "parkour";

    private static final class Run {
        final ParkourStore.Course course;
        final long started;
        int next;
        Location back;

        Run(ParkourStore.Course course, Location back) {
            this.course = course;
            this.started = System.currentTimeMillis();
            this.back = back;
        }
    }

    private final JavaPlugin plugin;
    private final ParkourStore store;
    private final HubSettings settings;
    private final HubStats stats;
    private final Messages messages;
    private final NetworkBus bus;
    private volatile Map<String, ParkourStore.Course> courses = Map.of();
    /** Bloc -> point (départ, passage ou arrivée). */
    private volatile Map<String, ParkourStore.Point> points = Map.of();
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSneak = new ConcurrentHashMap<>();
    private final Set<String> sources = ConcurrentHashMap.newKeySet();
    /** Téléportations faites par le parcours lui-même (pas un abandon). */
    private final Set<UUID> teleporting = ConcurrentHashMap.newKeySet();

    public Parkour(JavaPlugin plugin, ParkourStore store, HubSettings settings, HubStats stats, Messages messages, NetworkBus bus) {
        this.plugin = plugin;
        this.store = store;
        this.settings = settings;
        this.stats = stats;
        this.messages = messages;
        this.bus = bus;
        bus.on(CHANGED, data -> reload());
    }

    public void start() {
        reload();
        Bukkit.getScheduler().runTaskTimer(plugin, this::timers, 10, 4);
    }

    public void reload() {
        Tasks.async(plugin, () -> {
            Map<String, ParkourStore.Course> fresh = store.courses();
            Map<String, ParkourStore.Point> blocks = new HashMap<>();
            fresh.values().forEach(course -> {
                if (course.start() != null) {
                    blocks.put(course.start().key(), course.start());
                }
                course.checkpoints().forEach(point -> blocks.put(point.key(), point));
                if (course.finish() != null) {
                    blocks.put(course.finish().key(), course.finish());
                }
            });
            Bukkit.getScheduler().runTask(plugin, () -> {
                courses = fresh;
                points = blocks;
                fresh.keySet().forEach(this::registerSource);
            });
        }, "Parcours non relus");
    }

    private void changed() {
        reload();
        Tasks.async(plugin, () -> bus.publish(CHANGED, new JsonObject()), "Parcours non annoncés");
    }

    /** Un classement par parcours pour EterLib (hologrammes, EterReward...) : « hub_parkour_<id> », en millisecondes. */
    private void registerSource(String course) {
        if (!sources.add(course)) {
            return;
        }
        Bukkit.getServicesManager().register(RankingSource.class, new RankingSource() {
            @Override
            public String id() {
                return "hub_parkour_" + course;
            }

            @Override
            public List<Ranked> top(int limit) {
                return store.top(course, limit).stream().map(time -> new Ranked(time.player(), time.best())).toList();
            }

            @Override
            public long[] of(UUID player) {
                return new long[]{store.rank(player, course), store.personal(player, course)[0]};
            }
        }, plugin, ServicePriority.Normal);
    }

    public boolean running(Player player) {
        return runs.containsKey(player.getUniqueId());
    }

    public Map<String, ParkourStore.Course> courses() {
        return courses;
    }

    public String courseName(Player viewer, String id) {
        String raw = messages.raw(viewer, "parkour.courses." + id);
        return raw != null ? messages.plain(viewer, "parkour.courses." + id) : id;
    }

    // ---------- Course ----------

    private ParkourStore.Point pointAt(Location at) {
        World world = at.getWorld();
        ParkourStore.Point point = points.get(world.getName() + ":" + at.getBlockX() + ":" + at.getBlockY() + ":" + at.getBlockZ());
        return point != null ? point : points.get(world.getName() + ":" + at.getBlockX() + ":" + (at.getBlockY() - 1) + ":" + at.getBlockZ());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Run run = runs.get(player.getUniqueId());
        if (run != null && event.getTo().getY() < run.back.getY() - settings.number("parkour.fall-distance", 6)) {
            back(player);
            return;
        }
        if (!event.hasChangedBlock() || !settings.bool("activities.parkour", true)) {
            return;
        }
        ParkourStore.Point point = pointAt(event.getTo());
        if (point == null) {
            return;
        }
        ParkourStore.Course course = courses.get(point.course());
        if (course == null || !course.complete()) {
            return;
        }
        if (point.index() == 0) {
            begin(player, course, event.getTo());
        } else if (run != null && run.course.id().equals(course.id())) {
            if (point.index() < 0) {
                if (run.next >= course.checkpoints().size()) {
                    finish(player, run);
                } else {
                    messages.actionBar(player, "parkour.missed", "count", String.valueOf(course.checkpoints().size() - run.next));
                }
            } else if (run.next < course.checkpoints().size() && course.checkpoints().get(run.next).index() == point.index()) {
                run.next++;
                run.back = event.getTo().clone();
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.6f);
                messages.send(player, "parkour.checkpoint", "count", String.valueOf(run.next),
                        "total", String.valueOf(course.checkpoints().size()), "time", ParkourTime.format(System.currentTimeMillis() - run.started));
            }
        }
    }

    private void begin(Player player, ParkourStore.Course course, Location at) {
        Run current = runs.get(player.getUniqueId());
        if (current != null && current.course.id().equals(course.id()) && System.currentTimeMillis() - current.started < 1500) {
            return;
        }
        runs.put(player.getUniqueId(), new Run(course, at.clone()));
        player.setFlying(false);
        player.setAllowFlight(false);
        player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.2f);
        messages.send(player, "parkour.started", "course", courseName(player, course.id()),
                "count", String.valueOf(course.checkpoints().size()));
        stats.add("parkour-start:" + course.id());
    }

    private void finish(Player player, Run run) {
        runs.remove(player.getUniqueId());
        long time = System.currentTimeMillis() - run.started;
        String id = run.course.id();
        UUID uuid = player.getUniqueId();
        stats.add("parkour:" + id);
        Tasks.async(plugin, player, () -> {
            long[] before = store.personal(uuid, id);
            store.finish(uuid, id, time);
            return before;
        }, before -> {
            boolean first = before[1] == 0;
            boolean record = ParkourTime.isRecord(before[0], time);
            String formatted = ParkourTime.format(time);
            player.showTitle(Title.title(messages.get(player, "parkour.finish-title"),
                    messages.get(player, record ? "parkour.finish-record" : "parkour.finish-subtitle", "time", formatted)));
            player.playSound(player, record ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.1f);
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 40, 0.5, 1, 0.5, 0.2);
            messages.send(player, record ? "parkour.record" : "parkour.finished", "course", courseName(player, id), "time", formatted,
                    "best", ParkourTime.format(before[0]));
            if (first) {
                Rewards.give(plugin, player, plugin.getConfig().getStringList("parkour.reward-first"));
            } else if (record) {
                Rewards.give(plugin, player, plugin.getConfig().getStringList("parkour.reward-record"));
            }
            Bukkit.getPluginManager().callEvent(new HubActivityEvent(player, "parkour", id, time, record));
        }, () -> messages.send(player, "error.generic"));
    }

    /** Retour au dernier point de passage (ou au départ). */
    public void back(Player player) {
        Run run = runs.get(player.getUniqueId());
        if (run == null) {
            return;
        }
        teleporting.add(player.getUniqueId());
        player.setFallDistance(0);
        player.teleportAsync(run.back.clone().add(0, 0.1, 0), PlayerTeleportEvent.TeleportCause.PLUGIN)
                .whenComplete((done, error) -> teleporting.remove(player.getUniqueId()));
        player.playSound(player, Sound.ENTITY_CHICKEN_EGG, 0.6f, 0.8f);
    }

    public void quit(Player player, boolean tell) {
        if (runs.remove(player.getUniqueId()) != null && tell) {
            messages.send(player, "parkour.quit");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking() || !running(event.getPlayer())) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastSneak.put(event.getPlayer().getUniqueId(), now);
        if (last != null && now - last < 450) {
            lastSneak.remove(event.getPlayer().getUniqueId());
            back(event.getPlayer());
        }
    }

    /** Une téléportation qui ne vient pas du parcours (commande, /spawn, carte...) l'abandonne. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (running(event.getPlayer()) && !teleporting.contains(event.getPlayer().getUniqueId())) {
            quit(event.getPlayer(), true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlight(PlayerToggleFlightEvent event) {
        if (event.isFlying() && running(event.getPlayer()) && !event.isCancelled()) {
            quit(event.getPlayer(), true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        runs.remove(event.getPlayer().getUniqueId());
        lastSneak.remove(event.getPlayer().getUniqueId());
    }

    private void timers() {
        long now = System.currentTimeMillis();
        runs.forEach((uuid, run) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                messages.actionBar(player, "parkour.timer", "time", ParkourTime.format(now - run.started),
                        "count", String.valueOf(run.next), "total", String.valueOf(run.course.checkpoints().size()));
            }
        });
    }

    // ---------- /parcours ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("quitter") || sub.equals("quit")) {
            if (running(player)) {
                quit(player, true);
            } else {
                messages.send(player, "parkour.not-running");
            }
            return true;
        }
        if (sub.equals("retour") || sub.equals("back")) {
            back(player);
            return true;
        }
        String course = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : args.length == 1 ? sub : null;
        if (course != null && sub.equals("top")) {
            course = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null;
        }
        if (course == null || !courses.containsKey(course)) {
            messages.send(player, "parkour.list", "courses", String.join(", ", courses.keySet()));
            return true;
        }
        String id = course;
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> new Object[]{store.top(id, 10), store.personal(uuid, id), store.rank(uuid, id)}, data -> {
            @SuppressWarnings("unchecked") List<ParkourStore.Time> top = (List<ParkourStore.Time>) data[0];
            long[] personal = (long[]) data[1];
            messages.send(player, "parkour.top-title", "course", courseName(player, id));
            for (int i = 0; i < top.size(); i++) {
                String name = EterLib.get().getPlayers().get(top.get(i).player()).map(found -> found.name()).orElse("?");
                messages.send(player, "parkour.top-line", "rank", String.valueOf(i + 1), "player", name,
                        "time", ParkourTime.format(top.get(i).best()));
            }
            messages.send(player, personal[0] > 0 ? "parkour.top-you" : "parkour.top-none", "time", ParkourTime.format(personal[0]),
                    "rank", String.valueOf(data[2]), "runs", String.valueOf(personal[1]));
        }, () -> messages.send(player, "error.generic"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("quitter", "retour", "top"));
            options.addAll(courses.keySet());
            return SubCommand.filter(options, args[0]);
        }
        return args.length == 2 ? SubCommand.filter(new ArrayList<>(courses.keySet()), args[1]) : List.of();
    }

    // ---------- Éditeur : /eterhub parkour ----------

    @Override
    public void run(Player player, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
        if (sub.equals("list")) {
            messages.send(player, "editor.parkour.list", "count", String.valueOf(courses.size()));
            courses.values().forEach(course -> messages.send(player, course.complete() ? "editor.parkour.line" : "editor.parkour.line-incomplete",
                    "course", course.id(), "count", String.valueOf(course.checkpoints().size())));
            return;
        }
        if (args.length < 2 || !args[1].toLowerCase(Locale.ROOT).matches("[a-z0-9_-]{1,32}")) {
            messages.send(player, "editor.parkour.usage");
            return;
        }
        String course = args[1].toLowerCase(Locale.ROOT);
        Block feet = player.getLocation().getBlock();
        Block block = feet.getType().isAir() ? feet.getRelative(0, -1, 0) : feet;
        String world = block.getWorld().getName();
        String author = player.getName();
        switch (sub) {
            case "start", "checkpoint", "finish" -> {
                int index = sub.equals("start") ? 0 : sub.equals("finish") ? -1 : 1;
                Tasks.async(plugin, player, () -> {
                    int saved = store.setPoint(course, index, world, block.getX(), block.getY(), block.getZ());
                    settings.log("parkour:" + course, null, sub + " " + block.getX() + "," + block.getY() + "," + block.getZ(), author);
                    return saved;
                }, saved -> {
                    messages.send(player, "editor.parkour.saved", "course", course, "point", sub.equals("checkpoint") ? "#" + saved : sub);
                    changed();
                }, () -> messages.send(player, "error.generic"));
            }
            case "undo" -> Tasks.async(plugin, player, () -> store.removeLastCheckpoint(course), removed -> {
                messages.send(player, removed ? "editor.parkour.undone" : "editor.parkour.none", "course", course);
                changed();
            }, () -> messages.send(player, "error.generic"));
            case "delete" -> Tasks.async(plugin, player, () -> {
                boolean deleted = store.delete(course);
                if (deleted) {
                    settings.log("parkour:" + course, "parcours", null, author);
                }
                return deleted;
            }, deleted -> {
                messages.send(player, deleted ? "editor.parkour.deleted" : "editor.parkour.none", "course", course);
                changed();
            }, () -> messages.send(player, "error.generic"));
            default -> messages.send(player, "editor.parkour.usage");
        }
    }

    @Override
    public List<String> complete(Player player, String[] args) {
        if (args.length == 1) {
            return SubCommand.filter(List.of("list", "start", "checkpoint", "undo", "finish", "delete"), args[0]);
        }
        return args.length == 2 ? SubCommand.filter(new ArrayList<>(courses.keySet()), args[1]) : List.of();
    }
}
