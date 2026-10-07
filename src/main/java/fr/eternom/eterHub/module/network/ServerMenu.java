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
 * Sélecteur de serveurs, 5 lignes : chaque serveur de selector.servers à sa case (icône, nom affiché, description
 * de lang/ selector.description.<serveur>, joueurs, en ligne ou non) ; « = retour ou fermer. Clic = y aller.
 */
class ServerMenu implements Menu {

    static final int SIZE = 45;
    private static final int BACK = 40;

    private final NetworkGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, String> serverAtSlot = new HashMap<>();

    ServerMenu(NetworkGui gui, Player viewer) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, SIZE, messages.get(viewer, "selector.title"));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        String server = serverAtSlot.get(slot);
        if (server != null) {
            Sounds.click(player);
            gui.connect(player, server);
        } else if (slot == BACK) {
            gui.selectorBack().click(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        for (Entry entry : gui.entries()) {
            serverAtSlot.put(entry.slot(), entry.server());
            inventory.setItem(entry.slot(), Items.item(entry.icon(),
                    messages.get(viewer, "selector.name", "server", gui.displayName(entry.server())),
                    lore(entry.server()), gui.isHere(entry.server())));
        }
        inventory.setItem(BACK, gui.selectorBack().item(viewer));
    }

    private List<Component> lore(String server) {
        List<Component> lore = new ArrayList<>();
        String description = messages.raw(viewer, "selector.description." + server);
        if (description != null) {
            for (String line : description.split("\n")) {
                lore.add(messages.render(line, TagResolver.empty()));
            }
            lore.add(Component.empty());
        }
        lore.addAll(gui.state(viewer, server));
        return lore;
    }
}
