package fr.eternom.eterHub.module.network;

import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Choix du lobby, 5 lignes : les lobbys en ligne (ceux où EterHub tourne), le moins rempli marqué « conseillé », les
 * amis présents indiqués ; celui où l'on est brille. « Suivre un ami » ouvre les amis (EterFriend : clic = le
 * rejoindre). Rafraîchi toutes les 5 s tant qu'il est ouvert.
 */
class LobbyMenu implements Menu {

    private static final int BACK = 40;
    private static final int FRIENDS = 38;
    private static final List<Integer> SLOTS = IntStream.of(19, 20, 21, 22, 23, 24, 25, 10, 11, 12, 13, 14, 15, 16,
            28, 29, 30, 31, 32, 33, 34).boxed().toList();

    private final NetworkGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Map<String, List<String>> friends;
    private final Inventory inventory;
    private final Map<Integer, String> lobbyAt = new HashMap<>();

    LobbyMenu(NetworkGui gui, Player viewer, Map<String, List<String>> friends) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.friends = friends;
        this.inventory = Bukkit.createInventory(this, 45, messages.get(viewer, "lobbies.title"));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        String lobby = lobbyAt.get(slot);
        if (lobby != null) {
            Sounds.click(player);
            gui.connect(player, lobby, gui.settings().integer("lobbies.capacity", 0));
        } else if (slot == FRIENDS && gui.friends().friendsAvailable()) {
            Sounds.page(player);
            gui.friends().openFriends(player);
        } else if (slot == BACK) {
            gui.lobbiesBack().click(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        lobbyAt.clear();
        List<String> lobbies = gui.lobbies().stream().filter(gui::isOnline).toList();
        String advised = lobbies.stream().filter(lobby -> !gui.isHere(lobby)).min(Comparator.comparingInt(gui::players)).orElse(null);
        int capacity = gui.settings().integer("lobbies.capacity", 0);
        for (int i = 0; i < lobbies.size() && i < SLOTS.size(); i++) {
            String lobby = lobbies.get(i);
            lobbyAt.put(SLOTS.get(i), lobby);
            List<Component> lore = new ArrayList<>(gui.state(viewer, lobby, capacity, false, friends.get(lobby)));
            if (lobby.equals(advised) && lobbies.size() > 1) {
                lore.addFirst(messages.get(viewer, "lobbies.advised"));
            }
            inventory.setItem(SLOTS.get(i), Items.item(gui.lobbyIcon(),
                    messages.get(viewer, "lobbies.name", "server", gui.displayName(lobby)), lore, gui.isHere(lobby)));
        }
        if (gui.friends().friendsAvailable()) {
            inventory.setItem(FRIENDS, Items.item(Material.PLAYER_HEAD, messages.get(viewer, "lobbies.follow"),
                    List.of(messages.get(viewer, "lobbies.follow-lore"))));
        }
        inventory.setItem(BACK, gui.lobbiesBack().item(viewer));
    }
}
