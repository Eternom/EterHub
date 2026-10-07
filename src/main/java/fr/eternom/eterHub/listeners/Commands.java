package fr.eternom.eterHub.listeners;

import fr.eternom.eterHub.Main;
import fr.eternom.eterHub.module.network.NetworkCommand;
import fr.eternom.eterHub.module.spawn.SpawnCommands;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;

import java.util.Objects;

public class Commands {

    public Commands(Main main) {
        SpawnCommands spawn = new SpawnCommands(main.getSpawn(), main.getMessages());
        NetworkCommand network = new NetworkCommand(main.getNetwork(), main.getMessages());
        register(main, "spawn", spawn);
        register(main, "eterhub", spawn);
        register(main, "servers", network);
        register(main, "lobbies", network);
        register(main, "visibility", main.getVisibility());
    }

    private static void register(Main main, String name, TabExecutor executor) {
        PluginCommand command = Objects.requireNonNull(main.getCommand(name), "Commande absente du plugin.yml : " + name);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }
}
