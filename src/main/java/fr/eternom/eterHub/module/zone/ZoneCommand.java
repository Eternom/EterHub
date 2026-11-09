package fr.eternom.eterHub.module.zone;

import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.SubCommand;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Éditeur des zones, /eterhub zone :
 * <pre>
 *   list                 les zones de config.yml, tracées ou non
 *   pos1 | pos2          coin 1 / 2 : le bloc visé (5 blocs), sinon sous les pieds
 *   set <zone>           la zone devient la sélection (pos1 → pos2)
 *   warp <zone>          son point d'arrivée (carte du village) : ici
 *   show <zone>          ses limites en particules (10 s)
 *   tp <zone>            y aller
 *   remove <zone>        effacer ses limites
 *   here                 la zone où l'on est
 * </pre>
 */
public class ZoneCommand implements SubCommand {

    private static final List<String> SUBS = List.of("list", "pos1", "pos2", "set", "warp", "show", "tp", "remove", "here");

    private final JavaPlugin plugin;
    private final ZoneService zones;
    private final ZoneStore store;
    private final VillageMap map;
    private final HubSettings settings;
    private final Messages messages;
    private final Map<UUID, Location[]> selections = new HashMap<>();

    public ZoneCommand(JavaPlugin plugin, ZoneService zones, ZoneStore store, VillageMap map, HubSettings settings, Messages messages) {
        this.plugin = plugin;
        this.zones = zones;
        this.store = store;
        this.map = map;
        this.settings = settings;
        this.messages = messages;
    }

    @Override
    public void run(Player player, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "list";
        String id = args.length > 1 ? args[1].toLowerCase() : null;
        switch (sub) {
            case "pos1", "pos2" -> select(player, sub.equals("pos1") ? 0 : 1);
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
                    case "show" -> show(player, id);
                    case "tp" -> map.travel(player, id, true);
                    case "remove" -> remove(player, id);
                    default -> messages.send(player, "editor.zone.usage");
                }
            }
        }
    }

    private void select(Player player, int corner) {
        Block target = player.getTargetBlockExact(5);
        Location at = (target != null ? target.getLocation() : player.getLocation().getBlock().getLocation()).clone();
        selections.computeIfAbsent(player.getUniqueId(), uuid -> new Location[2])[corner] = at;
        messages.send(player, "editor.zone.corner", "corner", String.valueOf(corner + 1),
                "x", String.valueOf(at.getBlockX()), "y", String.valueOf(at.getBlockY()), "z", String.valueOf(at.getBlockZ()));
        player.spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0.5, 1.2, 0.5), 15, 0.3, 0.3, 0.3, 0);
    }

    private void define(Player player, String id) {
        Location[] selection = selections.get(player.getUniqueId());
        if (selection == null || selection[0] == null || selection[1] == null) {
            messages.send(player, "editor.zone.no-selection");
            return;
        }
        if (selection[0].getWorld() != selection[1].getWorld()) {
            messages.send(player, "editor.zone.two-worlds");
            return;
        }
        Region region = Region.of(selection[0].getWorld().getName(), selection[0].getBlockX(), selection[0].getBlockY(),
                selection[0].getBlockZ(), selection[1].getBlockX(), selection[1].getBlockY(), selection[1].getBlockZ());
        String author = player.getName();
        Tasks.async(plugin, player, () -> {
            String old = store.regions().containsKey(id) ? store.regions().get(id).describe() : null;
            store.saveRegion(id, region);
            settings.log("zone:" + id, old, region.describe(), author);
            return true;
        }, done -> {
            zones.changed();
            messages.send(player, "editor.zone.saved", "zone", id, "region", region.describe(), "volume", String.valueOf(region.volume()));
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

    /** Les arêtes de la zone en particules, pour ce joueur, pendant 10 s. */
    private void show(Player player, String id) {
        Region region = zones.index().region(id).orElse(null);
        if (region == null || Bukkit.getWorld(region.world()) == null) {
            messages.send(player, "editor.zone.not-drawn", "zone", id);
            return;
        }
        org.bukkit.World world = Bukkit.getWorld(region.world());
        List<Location> points = new ArrayList<>();
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
        messages.send(player, "editor.zone.showing", "zone", id, "region", region.describe());
        for (int tick = 0; tick < 10; tick++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    points.stream().filter(point -> point.distanceSquared(player.getLocation()) < 128 * 128)
                            .forEach(point -> player.spawnParticle(Particle.END_ROD, point, 1, 0, 0, 0, 0));
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

    @Override
    public List<String> complete(Player player, String[] args) {
        if (args.length == 1) {
            return SubCommand.filter(SUBS, args[0]);
        }
        if (args.length == 2 && List.of("set", "warp", "show", "tp", "remove").contains(args[0].toLowerCase())) {
            return SubCommand.filter(new ArrayList<>(zones.zones().keySet()), args[1]);
        }
        return List.of();
    }
}
