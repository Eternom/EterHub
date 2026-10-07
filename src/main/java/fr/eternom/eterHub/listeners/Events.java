package fr.eternom.eterHub.listeners;

import fr.eternom.eterHub.Main;
import fr.eternom.eterHub.module.movement.MovementListener;
import fr.eternom.eterHub.module.network.PortalListener;
import fr.eternom.eterHub.module.protect.ProtectionListener;
import org.bukkit.event.Listener;

public class Events {

    public Events(Main main) {
        register(main, new ProtectionListener(main, main.getSpawn()));
        register(main, new MovementListener(main));
        register(main, main.getVisibility());
        if (main.getConfig().getBoolean("portals.open-selector", true)) {
            register(main, new PortalListener(main, main.getNetwork()));
        }
    }

    private static void register(Main main, Listener listener) {
        main.getServer().getPluginManager().registerEvents(listener, main);
    }
}
