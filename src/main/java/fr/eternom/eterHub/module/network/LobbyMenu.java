package fr.eternom.eterHub.module.network;

import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Choix du lobby, 5 lignes : les lobbys EN LIGNE (ceux où EterHub est installé) sur la ligne du milieu, puis au-dessus et
 * en dessous (21 au plus), avec leurs joueurs et leur état ; celui où l'on est brille. Clic = y aller.
 */
class LobbyMenu implements Menu {

    private static final int BACK = 40;
    /** Ligne du milieu d'abord, puis celle du dessus, puis celle du dessous. */
    private static final List<Integer> SLOTS = IntStream.of(19, 20, 21, 22, 23, 24, 25, 10, 11, 12, 13, 14, 15, 16,
            28, 29, 30, 31, 32, 33, 34).boxed().toList();

    private final NetworkGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, String> lobbyAtSlot = new HashMap<>();

    LobbyMenu(NetworkGui gui, Player viewer) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, 45, messages.get(viewer, "lobbies.title"));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        String lobby = lobbyAtSlot.get(slot);
        if (lobby != null) {
            Sounds.click(player);
            gui.connect(player, lobby);
        } else if (slot == BACK) {
            gui.lobbiesBack().click(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        // Seulement ceux qui tournent : un lobby arrêté ou un ancien nom (server-name changé) n'encombre pas le menu
        List<String> lobbies = gui.lobbies().stream().filter(gui::isOnline).toList();
        for (int i = 0; i < lobbies.size() && i < SLOTS.size(); i++) {
            String lobby = lobbies.get(i);
            lobbyAtSlot.put(SLOTS.get(i), lobby);
            inventory.setItem(SLOTS.get(i), Items.item(gui.lobbyIcon(),
                    messages.get(viewer, "lobbies.name", "server", gui.displayName(lobby)), gui.state(viewer, lobby), gui.isHere(lobby)));
        }
        inventory.setItem(BACK, gui.lobbiesBack().item(viewer));
    }
}
