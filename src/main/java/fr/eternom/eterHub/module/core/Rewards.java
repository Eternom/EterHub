package fr.eternom.eterHub.module.core;

import fr.eternom.eterLib.EterLib;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Récompenses des activités (config.yml : listes « reward »). Chaque ligne est une commande lancée par la console
 * ({player} = pseudo, ex : « eco give {player} 100 », une commande de succès...), ou une action d'EterLib si elle
 * commence par « action: » (ex : « action: eterlib:title "Bravo !" »). Rien n'entre dans l'inventaire depuis EterHub.
 */
public final class Rewards {

    private Rewards() {
    }

    public static void give(JavaPlugin plugin, Player player, List<String> lines) {
        for (String line : lines) {
            String filled = line.replace("{player}", player.getName()).replace("{uuid}", player.getUniqueId().toString()).strip();
            if (filled.isEmpty()) {
                continue;
            }
            if (filled.startsWith("action:")) {
                EterLib.get().getActions().run(player, List.of(filled.substring("action:".length()).strip()));
            } else {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), filled.startsWith("/") ? filled.substring(1) : filled);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Récompense non donnée (" + filled + ") : " + e.getMessage());
                }
            }
        }
    }
}
