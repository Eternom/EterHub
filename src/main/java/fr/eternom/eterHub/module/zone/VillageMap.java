package fr.eternom.eterHub.module.zone;

import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * La carte du village (/map) : chaque lieu, découvert ou non (« ??? »), dans l'ordre de config.yml, avec le compteur
 * « 12/19 lieux découverts ». Un lieu découvert, au point d'arrivée réglé, se rejoint d'un clic (le staff : tous).
 */
public class VillageMap {

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    private static final int BACK = 40;
    private static final int COUNTER = 4;

    private final ZoneService zones;
    private final Messages messages;
    private final HubSettings settings;
    private final BackButton back;

    public VillageMap(ZoneService zones, Messages messages, HubSettings settings, BackButton back) {
        this.zones = zones;
        this.messages = messages;
        this.settings = settings;
        this.back = back;
    }

    public void open(Player player) {
        player.openInventory(new MapMenu(player).getInventory());
    }

    /** Aller à un lieu : découvert (ou staff), avec un point d'arrivée réglé. */
    public void travel(Player player, String id, boolean force) {
        Zone zone = zones.zone(id).orElse(null);
        if (zone == null) {
            messages.send(player, "zone.unknown", "zone", id);
            return;
        }
        boolean staff = player.hasPermission(ZoneService.BYPASS);
        if (!force && !staff && (!settings.bool("discovery.teleport", true) || !zones.discoveries().has(player.getUniqueId(), id))) {
            messages.send(player, "map.not-discovered");
            Sounds.refuse(player);
            return;
        }
        if (!force && !staff && !zones.canEnter(player, zone)) {
            messages.send(player, "zone.denied", "zone", zones.plainName(player, id));
            Sounds.refuse(player);
            return;
        }
        ZoneStore.Warp warp = zones.warp(id).orElse(null);
        World world = warp == null ? null : Bukkit.getWorld(warp.world());
        if (world == null) {
            messages.send(player, "map.no-warp", "zone", zones.plainName(player, id));
            Sounds.refuse(player);
            return;
        }
        Location target = new Location(world, warp.x(), warp.y(), warp.z(), warp.yaw(), warp.pitch());
        player.closeInventory();
        player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.2);
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(done -> {
            if (done) {
                player.playSound(player, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f);
                player.getWorld().spawnParticle(Particle.REVERSE_PORTAL, target.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.05);
            }
        });
    }

    private final class MapMenu implements Menu {

        private final Inventory inventory;
        private final Map<Integer, String> zoneAt = new HashMap<>();

        MapMenu(Player viewer) {
            Set<String> found = zones.discoveries().of(viewer.getUniqueId());
            long total = zones.discoverableCount();
            long count = zones.discoveredCount(viewer.getUniqueId());
            inventory = Bukkit.createInventory(this, 45, messages.get(viewer, "map.title", "count", String.valueOf(count),
                    "total", String.valueOf(total)));
            Frame.draw(inventory, Material.LIME_STAINED_GLASS_PANE);
            boolean staff = viewer.hasPermission(ZoneService.BYPASS);
            int i = 0;
            for (Zone zone : zones.zones().values()) {
                if (i >= SLOTS.length) {
                    break;
                }
                if (!zone.discoverable() && !staff) {
                    continue;
                }
                boolean known = found.contains(zone.id()) || staff;
                boolean here = zone.id().equals(zones.idOf(viewer));
                List<Component> lore = new ArrayList<>();
                if (known) {
                    String description = messages.raw(viewer, "zones." + zone.id() + ".subtitle");
                    if (description != null) {
                        lore.add(messages.get(viewer, "zones." + zone.id() + ".subtitle"));
                    }
                    if (zone.restricted()) {
                        lore.add(messages.get(viewer, "map.restricted"));
                    }
                    lore.add(Component.empty());
                    lore.add(messages.get(viewer, here ? "map.here" : zones.warp(zone.id()).isPresent() ? "map.travel" : "map.no-warp-lore"));
                    inventory.setItem(SLOTS[i], Items.item(zone.icon(), zones.name(viewer, zone.id()), lore, here));
                    zoneAt.put(SLOTS[i], zone.id());
                } else {
                    inventory.setItem(SLOTS[i], Items.item(Material.GRAY_DYE, messages.get(viewer, "map.unknown"),
                            List.of(messages.get(viewer, "map.unknown-lore"))));
                }
                i++;
            }
            inventory.setItem(COUNTER, Items.item(Material.FILLED_MAP, messages.get(viewer, "map.counter", "count",
                    String.valueOf(count), "total", String.valueOf(total)), List.of(messages.get(viewer, "map.counter-lore"))));
            inventory.setItem(BACK, back.item(viewer));
        }

        @Override
        public void onClick(Player player, int slot, ClickType click) {
            if (slot == BACK) {
                back.click(player);
                return;
            }
            String id = zoneAt.get(slot);
            if (id != null) {
                Sounds.click(player);
                travel(player, id, false);
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
