package fr.eternom.eterHub.module.spawn;

import fr.eternom.eterHub.api.HubActivityEvent;
import fr.eternom.eterHub.api.HubZoneEnterEvent;
import fr.eternom.eterHub.module.core.HubPlayers;
import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.HubStats;
import fr.eternom.eterHub.module.core.Rewards;
import fr.eternom.eterHub.module.zone.ZoneService;
import fr.eternom.eterHub.module.zone.ZoneStore;
import fr.eternom.eterLib.helper.message.Messages;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * La visite guidée du village (première venue, ou /visite) : une suite de lieux (config.yml > tour.steps), un à la
 * fois, dans une bossbar (« Étape 2/5 : la Banque »), avec un chemin de particules vers le point d'arrivée du lieu.
 * Entrer dans le lieu passe à l'étape suivante ; à la fin, une récompense (tour.reward). /visite passer l'arrête,
 * /visite la reprend. L'étape est gardée en base (reprise sur n'importe quel lobby). Elle passera plus tard par
 * BetonQuest (HubZoneEnterEvent suffit à suivre les lieux).
 */
public class Tour implements Listener, TabExecutor {

    private final JavaPlugin plugin;
    private final HubPlayers players;
    private final ZoneService zones;
    private final HubSettings settings;
    private final HubStats stats;
    private final Messages messages;
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    public Tour(JavaPlugin plugin, HubPlayers players, ZoneService zones, HubSettings settings, HubStats stats, Messages messages) {
        this.plugin = plugin;
        this.players = players;
        this.zones = zones;
        this.settings = settings;
        this.stats = stats;
        this.messages = messages;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::guide, 40, 20);
    }

    private List<String> steps() {
        return plugin.getConfig().getStringList("tour.steps").stream().filter(zones.zones()::containsKey).toList();
    }

    public boolean running(Player player) {
        return bars.containsKey(player.getUniqueId());
    }

    /** Commence (ou reprend) la visite. */
    public void begin(Player player, boolean fromStart) {
        List<String> steps = steps();
        if (steps.isEmpty() || !settings.bool("activities.tour", true)) {
            messages.send(player, "tour.unavailable");
            return;
        }
        int step = players.of(player).tourStep();
        if (fromStart || step < 0 || step >= steps.size()) {
            step = 0;
            players.setTourStep(player, 0);
        }
        messages.send(player, "tour.start", "count", String.valueOf(steps.size()));
        show(player, step, steps);
    }

    public void stop(Player player, boolean skipped) {
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
        if (skipped) {
            players.setTourStep(player, HubPlayers.TOUR_DONE);
            messages.send(player, "tour.skipped");
        }
    }

    private void show(Player player, int step, List<String> steps) {
        String zone = steps.get(step);
        BossBar bar = bars.computeIfAbsent(player.getUniqueId(), uuid -> {
            BossBar created = BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
            player.showBossBar(created);
            return created;
        });
        bar.name(messages.get(player, "tour.bar", "step", String.valueOf(step + 1), "count", String.valueOf(steps.size()),
                "zone", zones.plainName(player, zone)));
        bar.progress(Math.min(1f, (float) step / steps.size()));
        String hint = messages.raw(player, "tour.steps." + zone);
        if (hint != null) {
            messages.send(player, "tour.steps." + zone);
        }
    }

    @EventHandler
    public void onZone(HubZoneEnterEvent event) {
        Player player = event.getPlayer();
        if (!running(player)) {
            return;
        }
        List<String> steps = steps();
        int step = players.of(player).tourStep();
        if (step < 0 || step >= steps.size() || !steps.get(step).equals(event.getZone())) {
            return;
        }
        step++;
        player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
        if (step >= steps.size()) {
            finish(player);
        } else {
            players.setTourStep(player, step);
            show(player, step, steps);
        }
    }

    private void finish(Player player) {
        stop(player, false);
        players.setTourStep(player, HubPlayers.TOUR_DONE);
        messages.send(player, "tour.done");
        player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1f);
        stats.add("activity:tour");
        Rewards.give(plugin, player, plugin.getConfig().getStringList("tour.reward"));
        Bukkit.getPluginManager().callEvent(new HubActivityEvent(player, "tour", "", 0, false));
    }

    /** Chaque seconde : un chemin de particules vers le lieu de l'étape (s'il a un point d'arrivée). */
    private void guide() {
        List<String> steps = steps();
        for (UUID uuid : bars.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                continue;
            }
            int step = players.of(player).tourStep();
            if (step < 0 || step >= steps.size()) {
                continue;
            }
            ZoneStore.Warp warp = zones.warp(steps.get(step)).orElse(null);
            if (warp == null || !player.getWorld().getName().equals(warp.world())) {
                continue;
            }
            Location eyes = player.getLocation().add(0, 1.2, 0);
            Vector direction = new Vector(warp.x(), warp.y() + 1, warp.z()).subtract(eyes.toVector());
            if (direction.lengthSquared() < 9) {
                continue;
            }
            direction.normalize();
            for (int i = 2; i <= 6; i++) {
                player.spawnParticle(Particle.HAPPY_VILLAGER, eyes.clone().add(direction.clone().multiply(i)), 1, 0, 0, 0, 0);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bars.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "passer", "skip", "stop" -> stop(player, true);
            case "recommencer", "restart" -> begin(player, true);
            default -> {
                if (running(player)) {
                    messages.send(player, "tour.already");
                } else {
                    begin(player, false);
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return args.length == 1 ? fr.eternom.eterHub.module.core.SubCommand.filter(List.of("passer", "recommencer"), args[0]) : List.of();
    }
}
