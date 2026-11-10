package fr.eternom.eterHub.module.activity;

import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.SubCommand;
import fr.eternom.eterHub.module.zone.Region;
import fr.eternom.eterHub.module.zone.ZoneCommand;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * /eterhub fishing add|remove [n°]|list|show : les coins de pêche sans nom. add : la surface sélectionnée comme un lieu
 * (/eterhub zone point, pos1 / pos2, ymin / ymax) ; remove : celui où l'on se tient, ou son numéro ; show : celui où
 * l'on se tient, ou tous ceux du monde.
 */
public class FishingSpotCommand implements SubCommand {

    private static final List<String> SUBS = List.of("add", "remove", "list", "show");

    private final FishingSpots spots;
    private final ZoneCommand zones;
    private final HubSettings settings;
    private final Messages messages;

    public FishingSpotCommand(FishingSpots spots, ZoneCommand zones, HubSettings settings, Messages messages) {
        this.spots = spots;
        this.zones = zones;
        this.settings = settings;
        this.messages = messages;
    }

    @Override
    public void run(Player player, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
        switch (sub) {
            case "add" -> add(player);
            case "remove" -> remove(player, args.length > 1 ? args[1] : null);
            case "show" -> show(player);
            case "list" -> {
                messages.send(player, "editor.fishing.list", "count", String.valueOf(spots.all().size()));
                spots.describe().forEach(line -> messages.send(player, "editor.fishing.line", "spot", line));
            }
            default -> messages.send(player, "editor.fishing.usage");
        }
    }

    private void add(Player player) {
        Optional<Region> region = zones.selected(player);
        if (region.isEmpty()) {
            return;
        }
        String author = player.getName();
        Tasks.async(spots.plugin(), player, () -> {
            long id = spots.add(region.get());
            settings.log("fishing-spot:" + id, null, region.get().describe(), author);
            return id;
        }, id -> {
            zones.clearSelection(player);
            messages.send(player, "editor.fishing.added", "id", String.valueOf(id), "region", region.get().describe());
            zones.show(player, "#" + id, region.get());
        }, () -> messages.send(player, "error.generic"));
    }

    private void remove(Player player, String number) {
        Long id = null;
        if (number != null) {
            try {
                id = Long.parseLong(number.replace("#", ""));
            } catch (NumberFormatException ignored) {
                // pas un numéro : celui où l'on se tient
            }
        }
        if (id == null) {
            id = spots.at(player.getLocation()).map(Map.Entry::getKey).orElse(null);
        }
        if (id == null) {
            messages.send(player, "editor.fishing.none-here");
            return;
        }
        long chosen = id;
        String author = player.getName();
        Tasks.async(spots.plugin(), player, () -> {
            boolean removed = spots.remove(chosen);
            if (removed) {
                settings.log("fishing-spot:" + chosen, "tracé", null, author);
            }
            return removed;
        }, removed -> messages.send(player, removed ? "editor.fishing.removed" : "editor.fishing.unknown", "id", String.valueOf(chosen)),
                () -> messages.send(player, "error.generic"));
    }

    private void show(Player player) {
        Optional<Map.Entry<Long, Region>> here = spots.at(player.getLocation());
        if (here.isPresent()) {
            zones.show(player, "#" + here.get().getKey(), here.get().getValue());
            return;
        }
        String world = player.getWorld().getName();
        List<Map.Entry<Long, Region>> inWorld = spots.all().entrySet().stream().filter(entry -> entry.getValue().world().equals(world)).toList();
        if (inWorld.isEmpty()) {
            messages.send(player, "editor.fishing.none-here");
            return;
        }
        inWorld.forEach(entry -> zones.show(player, "#" + entry.getKey(), entry.getValue()));
    }

    @Override
    public List<String> complete(Player player, String[] args) {
        if (args.length == 1) {
            return SubCommand.filter(SUBS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            return SubCommand.filter(spots.all().keySet().stream().map(String::valueOf).toList(), args[1]);
        }
        return List.of();
    }
}
