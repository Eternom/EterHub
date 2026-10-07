package fr.eternom.eterHub.module.network;

import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/** /servers (sélecteur de serveurs) et /lobbies (choix du lobby). */
public class NetworkCommand implements TabExecutor {

    private final NetworkGui gui;
    private final Messages messages;

    public NetworkCommand(NetworkGui gui, Messages messages) {
        this.gui = gui;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
        } else if (command.getName().equals("servers")) {
            gui.openServers(player);
        } else {
            gui.openLobbies(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }
}
