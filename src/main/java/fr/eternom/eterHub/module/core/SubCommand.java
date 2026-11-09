package fr.eternom.eterHub.module.core;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** Une partie de /eterhub (zone, pad, holo...) : args sans le nom de la partie. Thread principal. */
public interface SubCommand {

    void run(Player player, String[] args);

    List<String> complete(Player player, String[] args);

    /** Les propositions qui commencent par ce qui est tapé. */
    static List<String> filter(List<String> options, String typed) {
        String start = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(start)).toList();
    }
}
