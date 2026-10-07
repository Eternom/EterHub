package fr.eternom.eterHub.module.spawn;

import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/** /spawn (tout le monde) et /eterhub setspawn (staff, eterhub.admin). */
public class SpawnCommands implements TabExecutor {

    private final Spawn spawn;
    private final Messages messages;

    public SpawnCommands(Spawn spawn, Messages messages) {
        this.spawn = spawn;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
            return true;
        }
        if (command.getName().equals("spawn")) {
            spawn.teleport(player);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("setspawn")) {
            spawn.set(player.getLocation());
            messages.send(player, "spawn.set");
        } else {
            messages.send(player, "eterhub.usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return command.getName().equals("eterhub") && args.length == 1 && "setspawn".startsWith(args[0].toLowerCase())
                ? List.of("setspawn") : List.of();
    }
}
