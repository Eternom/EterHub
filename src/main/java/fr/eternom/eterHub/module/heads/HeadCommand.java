package fr.eternom.eterHub.module.heads;

import fr.eternom.eterHub.module.core.SubCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/** /heads [hint] : le menu de la chasse aux têtes, ou un indice chaud / froid. */
public class HeadCommand implements TabExecutor {

    private final HeadService service;
    private final HeadMenus menus;

    public HeadCommand(HeadService service, HeadMenus menus) {
        this.service = service;
        this.menus = menus;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            service.messages().send(sender, "command.players-only");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("hint")) {
            service.hint(player);
        } else {
            menus.open(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return args.length == 1 ? SubCommand.filter(List.of("hint"), args[0]) : List.of();
    }
}
