package fr.eternom.eterHub.module.network;

import fr.eternom.eterHub.module.network.NetworkGui.Entry;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sélecteur de serveurs, 5 lignes : chaque serveur de selector.servers à sa case (icône, nom, description de lang/
 * selector.description.<serveur>, remplissage, amis, état) ; « Reprendre » (dernier serveur joué) ; retour.
 * Rafraîchi toutes les 5 s tant qu'il est ouvert.
 */
class ServerMenu implements Menu {

    static final int SIZE = 45;
    static final int BACK = 40;
    static final int RESUME = 36;

    private final NetworkGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Map<String, List<String>> friends;
    private final Inventory inventory;
    private final Map<Integer, Entry> entryAt = new HashMap<>();

    ServerMenu(NetworkGui gui, Player viewer, Map<String, List<String>> friends) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.friends = friends;
        this.inventory = Bukkit.createInventory(this, SIZE, messages.get(viewer, "selector.title"));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Entry entry = entryAt.get(slot);
        if (entry != null) {
            Sounds.click(player);
            gui.choose(player, entry);
        } else if (slot == RESUME) {
            gui.resume(player).ifPresent(server -> {
                Sounds.click(player);
                gui.connect(player, server, 0);
            });
        } else if (slot == BACK) {
            gui.selectorBack().click(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        entryAt.clear();
        for (Entry entry : gui.entries()) {
            entryAt.put(entry.slot(), entry);
            inventory.setItem(entry.slot(), Items.item(entry.soon() ? Material.GRAY_DYE : entry.icon(),
                    messages.get(viewer, "selector.name", "server", gui.displayName(entry.server())),
                    lore(entry), gui.isHere(entry.server())));
        }
        gui.resume(viewer).ifPresent(server -> inventory.setItem(RESUME, Items.item(Material.RECOVERY_COMPASS,
                messages.get(viewer, "selector.resume", "server", gui.displayName(server)),
                List.of(messages.get(viewer, "selector.resume-lore", "server", gui.displayName(server))))));
        inventory.setItem(BACK, gui.selectorBack().item(viewer));
    }

    private List<Component> lore(Entry entry) {
        List<Component> lore = new ArrayList<>();
        String description = messages.raw(viewer, "selector.description." + entry.server());
        if (description != null) {
            for (String line : description.split("\n")) {
                lore.add(messages.render(line, TagResolver.empty()));
            }
            lore.add(Component.empty());
        }
        lore.addAll(gui.state(viewer, entry.server(), entry.capacity(), entry.soon(), friends.get(entry.server())));
        return lore;
    }
}
