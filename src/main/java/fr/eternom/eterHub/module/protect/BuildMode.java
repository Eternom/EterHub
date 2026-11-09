package fr.eternom.eterHub.module.protect;

import fr.eternom.eterHub.module.core.SubCommand;
import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mode construction du staff (/eterhub build) : seul ce mode permet de casser, poser, ouvrir les coffres, frapper
 * les entités... La permission eterhub.build ne donne que le droit de l'activer : un staff qui se promène au lobby ne
 * casse rien par mégarde. Coupé à la déconnexion.
 */
public class BuildMode implements SubCommand, Listener {

    public static final String PERMISSION = "eterhub.build";

    private final Messages messages;
    private final Set<UUID> building = ConcurrentHashMap.newKeySet();

    public BuildMode(Messages messages) {
        this.messages = messages;
    }

    public boolean isBuilding(Player player) {
        return building.contains(player.getUniqueId());
    }

    @Override
    public void run(Player player, String[] args) {
        if (!player.hasPermission(PERMISSION)) {
            messages.send(player, "build.no-permission");
            return;
        }
        boolean on = building.add(player.getUniqueId());
        if (!on) {
            building.remove(player.getUniqueId());
        }
        messages.send(player, on ? "build.on" : "build.off");
    }

    @Override
    public List<String> complete(Player player, String[] args) {
        return List.of();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        building.remove(event.getPlayer().getUniqueId());
    }
}
