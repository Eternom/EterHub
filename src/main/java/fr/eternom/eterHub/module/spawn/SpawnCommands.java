package fr.eternom.eterHub.module.spawn;

import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.SubCommand;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.List;

/**
 * /spawn (tout le monde : court délai s'il est réglé, puis un effet) et /eterhub spawn set | add | clear | list (staff).
 */
public class SpawnCommands implements TabExecutor, SubCommand {

    private final Spawn spawn;
    private final HubSettings settings;
    private final Messages messages;

    public SpawnCommands(Spawn spawn, HubSettings settings, Messages messages) {
        this.spawn = spawn;
        this.settings = settings;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
            return true;
        }
        goToSpawn(player);
        return true;
    }

    /** /spawn : délai (spawn-command.warmup, via EterLib : annulé si l'on bouge), puis téléportation avec effet. */
    public void goToSpawn(Player player) {
        Runnable go = () -> {
            Location from = player.getLocation();
            player.getWorld().spawnParticle(Particle.CLOUD, from.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
            player.setFallDistance(0);
            player.teleportAsync(spawn.location(), PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(done -> {
                if (done && player.isOnline()) {
                    player.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.4f);
                    player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add(0, 1, 0), 25, 0.4, 0.8, 0.4, 0.03);
                }
            });
        };
        if (settings.bool("spawn-command.warmup", true)) {
            EterLib.get().getWarmup().start(player, messages.plain(player, "spawn.label"), null, go, () -> { });
        } else {
            go.run();
        }
    }

    @Override
    public void run(Player player, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "set" -> {
                spawn.set(player.getLocation());
                messages.send(player, "spawn.set");
            }
            case "add" -> {
                spawn.add(player.getLocation());
                messages.send(player, "spawn.added", "count", String.valueOf(spawn.pointCount()));
            }
            case "clear" -> {
                spawn.clearExtras();
                messages.send(player, "spawn.cleared");
            }
            case "list" -> {
                messages.send(player, "spawn.list", "count", String.valueOf(spawn.pointCount()));
                if (!spawn.missingWorlds().isEmpty()) {
                    messages.send(player, "spawn.missing-world", "points", String.join(", ", spawn.missingWorlds()));
                }
            }
            default -> messages.send(player, "spawn.usage");
        }
    }

    @Override
    public List<String> complete(Player player, String[] args) {
        return args.length == 1 ? SubCommand.filter(List.of("set", "add", "clear", "list"), args[0]) : List.of();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }
}
