package fr.eternom.eterHub.module.zone;

import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.SubCommand;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Éditeur des lieux, /eterhub zone. Deux façons de tracer :
 * <pre>
 *   point                ajoute un point de la surface : là où l'on est (en marchant le long du bord, dans l'ordre)
 *   undo | clear         retire le dernier point / toute la sélection
 *   ymin | ymax          hauteur basse / haute : celle où l'on est (facultatif : sinon toute la hauteur)
 *   height off           plus de hauteur
 *   pos1 | pos2          ou un pavé : coin 1 / 2 (bloc visé à 5 blocs, sinon sous les pieds)
 *   set <lieu>           le lieu prend la sélection (3 points ou plus : la surface ; sinon le pavé pos1 → pos2)
 * </pre>
 * Et : list, warp <lieu> (point d'arrivée de la carte, ici), show <lieu> (limites en particules, 10 s), tp <lieu>,
 * remove <lieu>, here.
 */
public class ZoneCommand implements SubCommand, Listener {

    private static final List<String> SUBS = List.of("list", "point", "undo", "clear", "ymin", "ymax", "height", "pos1", "pos2",
            "set", "warp", "show", "tp", "remove", "here");

    /** La sélection d'un membre du staff. */
    private static final class Selection {
        final Location[] corners = new Location[2];
        final List<int[]> points = new ArrayList<>();
        String world;
        Integer minY;
        Integer maxY;
    }

    private final JavaPlugin plugin;
    private final ZoneService zones;
    private final ZoneStore store;
    private final VillageMap map;
    private final HubSettings settings;
    private final Messages messages;
    private final Map<UUID, Selection> selections = new HashMap<>();

    public ZoneCommand(JavaPlugin plugin, ZoneService zones, ZoneStore store, VillageMap map, HubSettings settings, Messages messages) {
        this.plugin = plugin;
        this.zones = zones;
        this.store = store;
        this.map = map;
        this.settings = settings;
        this.messages = messages;
    }

    private Selection selection(Player player) {
        return selections.computeIfAbsent(player.getUniqueId(), uuid -> new Selection());
    }

    @Override
    public void run(Player player, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "list";
        String id = args.length > 1 ? args[1].toLowerCase() : null;
        switch (sub) {
            case "pos1", "pos2" -> corner(player, sub.equals("pos1") ? 0 : 1);
            case "point" -> point(player);
            case "undo" -> {
                Selection selection = selection(player);
                if (!selection.points.isEmpty()) {
                    selection.points.removeLast();
                }
                messages.send(player, "editor.zone.points", "count", String.valueOf(selection.points.size()));
                preview(player, selection);
            }
            case "clear" -> {
                selections.remove(player.getUniqueId());
                messages.send(player, "editor.zone.cleared");
            }
            case "ymin", "ymax" -> {
                Selection selection = selection(player);
                int y = player.getLocation().getBlockY();
                if (sub.equals("ymin")) {
                    selection.minY = y;
                } else {
                    selection.maxY = y;
                }
                messages.send(player, "editor.zone.height", "min", selection.minY == null ? "-" : String.valueOf(selection.minY),
                        "max", selection.maxY == null ? "-" : String.valueOf(selection.maxY));
            }
            case "height" -> {
                Selection selection = selection(player);
                selection.minY = null;
                selection.maxY = null;
                messages.send(player, "editor.zone.height-off");
            }
            case "list" -> list(player);
            case "here" -> {
                String here = zones.idOf(player);
                messages.send(player, here.isEmpty() ? "editor.zone.here-none" : "editor.zone.here", "zone", here);
            }
            default -> {
                if (id == null || !zones.zones().containsKey(id)) {
                    messages.send(player, "editor.zone.unknown", "zones", String.join(", ", zones.zones().keySet()));
                    return;
                }
                switch (sub) {
                    case "set" -> define(player, id);
                    case "warp" -> warp(player, id);
                    case "show" -> zones.index().region(id).ifPresentOrElse(region -> show(player, id, region),
                            () -> messages.send(player, "editor.zone.not-drawn", "zone", id));
                    case "tp" -> map.travel(player, id, true);
                    case "remove" -> remove(player, id);
                    default -> messages.send(player, "editor.zone.usage");
                }
            }
        }
    }

    private void corner(Player player, int corner) {
        Block target = player.getTargetBlockExact(5);
        Location at = (target != null ? target.getLocation() : player.getLocation().getBlock().getLocation()).clone();
        selection(player).corners[corner] = at;
        messages.send(player, "editor.zone.corner", "corner", String.valueOf(corner + 1),
                "x", String.valueOf(at.getBlockX()), "y", String.valueOf(at.getBlockY()), "z", String.valueOf(at.getBlockZ()));
        player.spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0.5, 1.2, 0.5), 15, 0.3, 0.3, 0.3, 0);
    }

    private void point(Player player) {
        Selection selection = selection(player);
        Location at = player.getLocation();
        if (selection.world != null && !selection.world.equals(at.getWorld().getName())) {
            selection.points.clear();
        }
        selection.world = at.getWorld().getName();
        selection.points.add(new int[]{at.getBlockX(), at.getBlockZ()});
        messages.send(player, "editor.zone.point", "count", String.valueOf(selection.points.size()),
                "x", String.valueOf(at.getBlockX()), "z", String.valueOf(at.getBlockZ()));
        preview(player, selection);
    }

    /** Les côtés de la surface en cours, à la hauteur du joueur (5 s). */
    private void preview(Player player, Selection selection) {
        if (selection.points.isEmpty() || selection.world == null) {
            return;
        }
        World world = Bukkit.getWorld(selection.world);
        if (world == null) {
            return;
        }
        List<Location> points = new ArrayList<>();
        double y = player.getLocation().getY() + 0.3;
        int n = selection.points.size();
        for (int i = 0; i < n; i++) {
            int[] a = selection.points.get(i);
            int[] b = selection.points.get((i + 1) % n);
            if (n < 3 && i == n - 1) {
                points.add(new Location(world, a[0] + 0.5, y, a[1] + 0.5));
                break;
            }
            line(points, world, a[0] + 0.5, a[1] + 0.5, b[0] + 0.5, b[1] + 0.5, y);
        }
        particles(player, points, 5, Particle.HAPPY_VILLAGER);
    }

    private static void line(List<Location> points, World world, double x1, double z1, double x2, double z2, double y) {
        double length = Math.hypot(x2 - x1, z2 - z1);
        int steps = Math.max(1, (int) Math.ceil(length));
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            points.add(new Location(world, x1 + (x2 - x1) * t, y, z1 + (z2 - z1) * t));
        }
    }

    private void define(Player player, String id) {
        Selection selection = selections.get(player.getUniqueId());
        Region region;
        if (selection != null && selection.points.size() >= 3) {
            int n = selection.points.size();
            int[] xs = new int[n];
            int[] zs = new int[n];
            for (int i = 0; i < n; i++) {
                xs[i] = selection.points.get(i)[0];
                zs[i] = selection.points.get(i)[1];
            }
            region = Region.polygon(selection.world, xs, zs, selection.minY == null ? Region.NO_MIN : selection.minY,
                    selection.maxY == null ? Region.NO_MAX : selection.maxY);
        } else if (selection != null && selection.corners[0] != null && selection.corners[1] != null) {
            Location[] corners = selection.corners;
            if (corners[0].getWorld() != corners[1].getWorld()) {
                messages.send(player, "editor.zone.two-worlds");
                return;
            }
            region = Region.of(corners[0].getWorld().getName(), corners[0].getBlockX(), corners[0].getBlockY(), corners[0].getBlockZ(),
                    corners[1].getBlockX(), corners[1].getBlockY(), corners[1].getBlockZ());
        } else {
            messages.send(player, "editor.zone.no-selection");
            return;
        }
        String author = player.getName();
        Region saved = region;
        Tasks.async(plugin, player, () -> {
            Region old = store.regions().get(id);
            store.saveRegion(id, saved);
            settings.log("zone:" + id, old == null ? null : old.describe(), saved.describe(), author);
            return true;
        }, done -> {
            zones.changed();
            selections.remove(player.getUniqueId());
            messages.send(player, "editor.zone.saved", "zone", id, "region", saved.describe(), "volume", String.valueOf(saved.volume()));
            show(player, id, saved);
            if (zones.warp(id).isEmpty()) {
                messages.send(player, "editor.zone.warp-hint", "zone", id);
            }
        }, () -> messages.send(player, "error.generic"));
    }

    private void warp(Player player, String id) {
        Location at = player.getLocation();
        ZoneStore.Warp warp = new ZoneStore.Warp(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
        String author = player.getName();
        Tasks.async(plugin, player, () -> {
            boolean saved = store.saveWarp(id, warp);
            if (saved) {
                settings.log("zone-warp:" + id, null, warp.world() + " " + Math.round(warp.x()) + "," + Math.round(warp.y()) + ","
                        + Math.round(warp.z()), author);
            }
            return saved;
        }, saved -> {
            if (saved) {
                zones.changed();
                messages.send(player, "editor.zone.warp-saved", "zone", id);
            } else {
                messages.send(player, "editor.zone.not-drawn", "zone", id);
            }
        }, () -> messages.send(player, "error.generic"));
    }

    private void remove(Player player, String id) {
        String author = player.getName();
        Tasks.async(plugin, player, () -> {
            boolean removed = store.remove(id);
            if (removed) {
                settings.log("zone:" + id, "tracée", null, author);
            }
            return removed;
        }, removed -> {
            zones.changed();
            messages.send(player, removed ? "editor.zone.removed" : "editor.zone.not-drawn", "zone", id);
        }, () -> messages.send(player, "error.generic"));
    }

    /** Les limites du lieu en particules, pour ce joueur, pendant 10 s. */
    private void show(Player player, String id, Region region) {
        World world = Bukkit.getWorld(region.world());
        if (world == null) {
            messages.send(player, "editor.zone.not-drawn", "zone", id);
            return;
        }
        List<Location> points = new ArrayList<>();
        if (region.isPolygon()) {
            // Le contour, à sa hauteur basse et haute si elle est réglée, sinon à hauteur du joueur
            List<Double> levels = region.hasHeight() ? List.of((double) region.minY(), region.maxY() + 1.0)
                    : List.of(player.getLocation().getY() + 0.3);
            int n = region.xs().length;
            for (double y : levels) {
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    line(points, world, region.xs()[i] + 0.5, region.zs()[i] + 0.5, region.xs()[j] + 0.5, region.zs()[j] + 0.5, y);
                }
            }
        } else {
            double x1 = region.minX(), y1 = region.minY(), z1 = region.minZ();
            double x2 = region.maxX() + 1, y2 = region.maxY() + 1, z2 = region.maxZ() + 1;
            double step = Math.max(1, Math.max(x2 - x1, Math.max(y2 - y1, z2 - z1)) / 60);
            for (double x = x1; x <= x2; x += step) {
                for (double[] yz : new double[][]{{y1, z1}, {y1, z2}, {y2, z1}, {y2, z2}}) {
                    points.add(new Location(world, x, yz[0], yz[1]));
                }
            }
            for (double y = y1; y <= y2; y += step) {
                for (double[] xz : new double[][]{{x1, z1}, {x1, z2}, {x2, z1}, {x2, z2}}) {
                    points.add(new Location(world, xz[0], y, xz[1]));
                }
            }
            for (double z = z1; z <= z2; z += step) {
                for (double[] xy : new double[][]{{x1, y1}, {x1, y2}, {x2, y1}, {x2, y2}}) {
                    points.add(new Location(world, xy[0], xy[1], z));
                }
            }
        }
        messages.send(player, "editor.zone.showing", "zone", id, "region", region.describe());
        particles(player, points, 10, Particle.END_ROD);
    }

    private void particles(Player player, List<Location> points, int seconds, Particle particle) {
        for (int tick = 0; tick < seconds; tick++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    points.stream().filter(point -> point.getWorld().equals(player.getWorld())
                                    && point.distanceSquared(player.getLocation()) < 128 * 128)
                            .forEach(point -> player.spawnParticle(particle, point, 1, 0, 0, 0, 0));
                }
            }, tick * 20L);
        }
    }

    private void list(Player player) {
        messages.send(player, "editor.zone.list-title", "count", String.valueOf(zones.index().size()),
                "total", String.valueOf(zones.zones().size()));
        for (Zone zone : zones.zones().values()) {
            boolean drawn = zones.index().region(zone.id()).isPresent();
            boolean warp = zones.warp(zone.id()).isPresent();
            messages.send(player, drawn ? (warp ? "editor.zone.line-ready" : "editor.zone.line-no-warp") : "editor.zone.line-missing",
                    "zone", zone.id(), "name", zones.plainName(player, zone.id()));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selections.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public List<String> complete(Player player, String[] args) {
        if (args.length == 1) {
            return SubCommand.filter(SUBS, args[0]);
        }
        if (args.length == 2 && List.of("set", "warp", "show", "tp", "remove").contains(args[0].toLowerCase())) {
            return SubCommand.filter(new ArrayList<>(zones.zones().keySet()), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("height")) {
            return SubCommand.filter(List.of("off"), args[1]);
        }
        return List.of();
    }
}
