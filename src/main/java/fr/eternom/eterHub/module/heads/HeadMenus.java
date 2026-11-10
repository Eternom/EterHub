package fr.eternom.eterHub.module.heads;

import fr.eternom.eterHub.module.zone.Zone;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Les menus de la chasse aux têtes :
 * - /heads (joueurs) : les chasses ouvertes (s'il y en a plusieurs), puis les lieux avec « 2/5 », puis les têtes d'un
 *   lieu (trouvée : son nom et sa date ; sinon « ??? ») ; un bouton d'indice (chaud / froid) ;
 * - /eterhub head list (staff) : toutes les têtes, les moins trouvées d'abord (lieu, chasse, nom, rare, trouvée par
 *   combien de chasseurs) ; clic = s'y téléporter.
 */
public class HeadMenus {

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.of("Europe/Paris"));

    private final HeadService service;
    private final Messages messages;
    private final BackButton back;

    public HeadMenus(HeadService service, BackButton back) {
        this.service = service;
        this.messages = service.messages();
        this.back = back;
    }

    /** /heads : la chasse principale directement, ou le choix s'il y a des chasses à thème ouvertes. */
    public void open(Player player) {
        List<Hunt> open = service.openHunts().stream().filter(hunt -> service.total(hunt.id()) > 0).toList();
        if (open.isEmpty()) {
            messages.send(player, "heads.none");
            return;
        }
        if (open.size() == 1) {
            player.openInventory(new HuntMenu(player, open.getFirst().id(), false).getInventory());
        } else {
            player.openInventory(new HuntsMenu(player, open).getInventory());
        }
    }

    private String date(long millis) {
        return DATE.format(Instant.ofEpochMilli(millis));
    }

    /** Le choix de la chasse. */
    private final class HuntsMenu implements Menu {

        private final Inventory inventory;
        private final Map<Integer, String> huntAt = new HashMap<>();

        HuntsMenu(Player viewer, List<Hunt> hunts) {
            inventory = Bukkit.createInventory(this, 27, messages.get(viewer, "heads.menu.hunts-title"));
            Frame.draw(inventory, Material.YELLOW_STAINED_GLASS_PANE);
            int[] slots = {11, 13, 15, 12, 14, 10, 16};
            for (int i = 0; i < hunts.size() && i < slots.length; i++) {
                Hunt hunt = hunts.get(i);
                huntAt.put(slots[i], hunt.id());
                inventory.setItem(slots[i], Items.item(hunt.isMain() ? Material.PLAYER_HEAD : Material.JACK_O_LANTERN,
                        messages.get(viewer, "heads.menu.hunt", "hunt", service.huntName(viewer, hunt.id())),
                        List.of(messages.get(viewer, "heads.menu.progress", "count", String.valueOf(service.count(viewer.getUniqueId(), hunt.id())),
                                "total", String.valueOf(service.total(hunt.id()))))));
            }
            inventory.setItem(22, back.item(viewer));
        }

        @Override
        public void onClick(Player player, int slot, ClickType click) {
            if (slot == 22) {
                back.click(player);
            } else if (huntAt.containsKey(slot)) {
                Sounds.page(player);
                player.openInventory(new HuntMenu(player, huntAt.get(slot), true).getInventory());
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Une chasse : ses lieux, avec la progression du joueur dans chacun. */
    private final class HuntMenu implements Menu {

        private static final int COUNTER = 4;
        private static final int BACK = 45;
        private static final int HINT = 49;
        private final Inventory inventory;
        private final String hunt;
        private final boolean fromList;
        private final Map<Integer, String> zoneAt = new HashMap<>();

        HuntMenu(Player viewer, String hunt, boolean fromList) {
            this.hunt = hunt;
            this.fromList = fromList;
            inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "heads.menu.title", "hunt", service.huntName(viewer, hunt)));
            Frame.draw(inventory, Material.YELLOW_STAINED_GLASS_PANE);
            Map<Long, Long> mine = service.foundBy(viewer.getUniqueId());
            Map<String, int[]> byZone = new LinkedHashMap<>();
            // Dans l'ordre des lieux de la carte, puis « hors des lieux »
            service.zones().zones().keySet().forEach(zone -> byZone.put(zone, new int[2]));
            byZone.put("", new int[2]);
            for (HeadStore.Head head : service.headsOf(hunt)) {
                int[] counts = byZone.computeIfAbsent(service.zoneOf(head), key -> new int[2]);
                counts[1]++;
                if (mine.containsKey(head.id())) {
                    counts[0]++;
                }
            }
            int i = 0;
            for (Map.Entry<String, int[]> entry : byZone.entrySet()) {
                if (entry.getValue()[1] == 0 || i >= SLOTS.length) {
                    continue;
                }
                String zone = entry.getKey();
                boolean done = entry.getValue()[0] >= entry.getValue()[1];
                Material icon = zone.isEmpty() ? Material.GRASS_BLOCK : service.zones().zone(zone).map(Zone::icon).orElse(Material.MAP);
                zoneAt.put(SLOTS[i], zone);
                inventory.setItem(SLOTS[i++], Items.item(icon, messages.get(viewer, "heads.menu.zone", "zone", service.zoneName(viewer, zone)),
                        List.of(messages.get(viewer, done ? "heads.menu.zone-done" : "heads.menu.progress",
                                "count", String.valueOf(entry.getValue()[0]), "total", String.valueOf(entry.getValue()[1])),
                                messages.get(viewer, "heads.menu.zone-open")), done));
            }
            inventory.setItem(COUNTER, Items.item(Material.PLAYER_HEAD, messages.get(viewer, "heads.menu.counter",
                    "count", String.valueOf(service.count(viewer.getUniqueId(), hunt)), "total", String.valueOf(service.total(hunt))),
                    List.of(messages.get(viewer, "heads.menu.counter-lore"))));
            inventory.setItem(HINT, Items.item(Material.COMPASS, messages.get(viewer, "heads.menu.hint"),
                    List.of(messages.get(viewer, "heads.menu.hint-lore"))));
            inventory.setItem(BACK, fromList ? Items.item(Material.ARROW, messages.get(viewer, "heads.menu.back"), List.of()) : back.item(viewer));
        }

        @Override
        public void onClick(Player player, int slot, ClickType click) {
            if (slot == BACK) {
                if (fromList) {
                    Sounds.page(player);
                    open(player);
                } else {
                    back.click(player);
                }
            } else if (slot == HINT) {
                player.closeInventory();
                service.hint(player);
            } else if (zoneAt.containsKey(slot)) {
                Sounds.page(player);
                player.openInventory(new ZoneMenu(player, hunt, zoneAt.get(slot), fromList).getInventory());
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Les têtes d'un lieu : trouvées (nom, date) ou « ??? ». */
    private final class ZoneMenu implements Menu {

        private static final int BACK = 49;
        private final Inventory inventory;
        private final String hunt;
        private final boolean fromList;

        ZoneMenu(Player viewer, String hunt, String zone, boolean fromList) {
            this.hunt = hunt;
            this.fromList = fromList;
            inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "heads.menu.zone-title", "zone", service.zoneName(viewer, zone)));
            Frame.draw(inventory, Material.YELLOW_STAINED_GLASS_PANE);
            Map<Long, Long> mine = service.foundBy(viewer.getUniqueId());
            List<HeadStore.Head> heads = service.headsOf(hunt).stream().filter(head -> service.zoneOf(head).equals(zone))
                    .sorted(Comparator.comparingLong(HeadStore.Head::id)).toList();
            for (int i = 0; i < heads.size() && i < SLOTS.length; i++) {
                HeadStore.Head head = heads.get(i);
                Long at = mine.get(head.id());
                String name = head.name() == null || head.name().isBlank()
                        ? messages.plain(viewer, "heads.menu.unnamed", "number", String.valueOf(i + 1)) : head.name();
                List<Component> lore = new ArrayList<>();
                if (head.rare()) {
                    lore.add(messages.get(viewer, "heads.menu.rare"));
                }
                if (at != null) {
                    lore.add(messages.get(viewer, "heads.menu.found-on", "date", date(at)));
                    inventory.setItem(SLOTS[i], service.display(head, messages.get(viewer, "heads.menu.head", "name", name), lore, head.rare()));
                } else {
                    lore.add(messages.get(viewer, "heads.menu.missing-lore"));
                    inventory.setItem(SLOTS[i], Items.item(Material.GRAY_DYE, messages.get(viewer, "heads.menu.missing"), lore));
                }
            }
            inventory.setItem(BACK, Items.item(Material.ARROW, messages.get(viewer, "heads.menu.back"), List.of()));
        }

        @Override
        public void onClick(Player player, int slot, ClickType click) {
            if (slot == BACK) {
                Sounds.page(player);
                player.openInventory(new HuntMenu(player, hunt, fromList).getInventory());
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ---------- Staff ----------

    /** Toutes les têtes, les moins trouvées d'abord ; clic = s'y téléporter. */
    public void openStaffList(Player player) {
        Tasks.async(service.plugin(), player, () -> new Object[]{service.foundCounts(), service.store().hunters()},
                data -> {
                    @SuppressWarnings("unchecked") Map<Long, Integer> counts = (Map<Long, Integer>) data[0];
                    player.openInventory(new StaffList(player, counts, (int) data[1], 0).getInventory());
                }, () -> messages.send(player, "error.generic"));
    }

    private final class StaffList implements Menu {

        private static final int PREVIOUS = 45;
        private static final int NEXT = 53;
        private final Inventory inventory;
        private final Map<Integer, HeadStore.Head> headAt = new HashMap<>();
        private final Map<Long, Integer> counts;
        private final int hunters;
        private final int page;
        private final int pages;

        StaffList(Player viewer, Map<Long, Integer> counts, int hunters, int page) {
            this.counts = counts;
            this.hunters = hunters;
            List<HeadStore.Head> heads = service.heads().stream()
                    .sorted(Comparator.comparingInt((HeadStore.Head head) -> counts.getOrDefault(head.id(), 0)).thenComparingLong(HeadStore.Head::id))
                    .toList();
            this.pages = Math.max(1, (heads.size() + SLOTS.length - 1) / SLOTS.length);
            this.page = Math.clamp(page, 0, pages - 1);
            inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "editor.head.list-title", "count", String.valueOf(heads.size()),
                    "hunters", String.valueOf(hunters)));
            Frame.draw(inventory, Material.RED_STAINED_GLASS_PANE);
            for (int i = 0; i < SLOTS.length; i++) {
                int index = this.page * SLOTS.length + i;
                if (index >= heads.size()) {
                    break;
                }
                HeadStore.Head head = heads.get(index);
                int foundBy = counts.getOrDefault(head.id(), 0);
                headAt.put(SLOTS[i], head);
                inventory.setItem(SLOTS[i], service.display(head,
                        messages.get(viewer, "editor.head.list-name", "id", String.valueOf(head.id()),
                                "name", head.name() == null ? "-" : head.name()),
                        List.of(messages.get(viewer, "editor.head.list-hunt", "hunt", service.huntName(viewer, head.hunt())),
                                messages.get(viewer, "editor.head.list-zone", "zone", service.zoneName(viewer, service.zoneOf(head))),
                                messages.get(viewer, "editor.head.list-found", "count", String.valueOf(foundBy), "hunters", String.valueOf(hunters)),
                                messages.get(viewer, "editor.head.list-at", "x", String.valueOf(head.x()), "y", String.valueOf(head.y()),
                                        "z", String.valueOf(head.z())),
                                Component.empty(), messages.get(viewer, "editor.head.list-tp")), head.rare()));
            }
            if (this.page > 0) {
                inventory.setItem(PREVIOUS, Items.item(Material.ARROW, messages.get(viewer, "heads.menu.previous"), List.of()));
            }
            if (this.page < pages - 1) {
                inventory.setItem(NEXT, Items.item(Material.ARROW, messages.get(viewer, "heads.menu.next"), List.of()));
            }
        }

        @Override
        public void onClick(Player player, int slot, ClickType click) {
            if (slot == PREVIOUS || slot == NEXT) {
                Sounds.page(player);
                player.openInventory(new StaffList(player, counts, hunters, page + (slot == NEXT ? 1 : -1)).getInventory());
                return;
            }
            HeadStore.Head head = headAt.get(slot);
            World world = head == null ? null : Bukkit.getWorld(head.world());
            if (world != null) {
                player.closeInventory();
                player.teleportAsync(new Location(world, head.x() + 0.5, head.y() + 1, head.z() + 0.5, player.getLocation().getYaw(), 30),
                        PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
