package fr.eternom.eterHub.module.admin;

import fr.eternom.eterHub.module.activity.Archaeology;
import fr.eternom.eterHub.module.activity.Fishing;
import fr.eternom.eterHub.module.activity.Fishmonger;
import fr.eternom.eterHub.module.network.NetworkGui;
import fr.eternom.eterHub.module.social.Visibility;
import fr.eternom.eterHub.module.spawn.SpawnCommands;
import fr.eternom.eterHub.module.spawn.Tour;
import fr.eternom.eterHub.module.zone.VillageMap;
import fr.eternom.eterHub.module.zone.ZoneService;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.module.action.Actions;
import fr.eternom.eterLib.module.action.Actions.Argument;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Les actions d'EterHub, pour les PNJ d'EterNpc, les panneaux, les menus :
 * <pre>
 *   eterhub:servers            le sélecteur de serveurs
 *   eterhub:lobbies            le choix du lobby
 *   eterhub:spawn              /spawn
 *   eterhub:server <nom>       aller sur un serveur (maintenance et capacité respectées)
 *   eterhub:zone <lieu>        aller à un lieu du village (même non découvert : le PNJ y emmène)
 *   eterhub:map                la carte du village
 *   eterhub:visibility         changer qui l'on voit
 *   eterhub:fishing            pêche : classements et règles (/fishing)
 *   eterhub:fishmonger         le poissonnier du lac (rachète les poissons)
 *   eterhub:fishing-journal    le carnet de pêche
 *   eterhub:museum             le musée des fouilles (/museum)
 *   eterhub:tour [restart]     la visite guidée
 *   eterhub:soon <quoi>        « bientôt » (Étals, Maisons et boutiques...)
 *   eterhub:door <lieu>        porte d'un donjon : le serveur de config.yml > doors, fermée sinon
 *   ?eterhub:discovered <lieu> condition : le joueur a découvert ce lieu
 *   ?eterhub:in-zone <lieu>    condition : le joueur est dans ce lieu
 * </pre>
 */
public final class HubActions {

    private HubActions() {
    }

    public static void register(JavaPlugin plugin, Messages messages, NetworkGui network, SpawnCommands spawn, ZoneService zones,
                                VillageMap map, Visibility visibility, Fishing fishing, Fishmonger fishmonger, Archaeology archaeology,
                                Tour tour) {
        Actions actions = EterLib.get().getActions();
        Argument zone = Argument.required("lieu", typed -> new ArrayList<>(zones.zones().keySet()));
        actions.register(plugin, "eterhub:servers", "Le sélecteur de serveurs", List.of(), (player, args) -> network.openServers(player));
        actions.register(plugin, "eterhub:lobbies", "Le choix du lobby", List.of(), (player, args) -> network.openLobbies(player));
        actions.register(plugin, "eterhub:spawn", "Retour au spawn du lobby", List.of(), (player, args) -> spawn.goToSpawn(player));
        actions.register(plugin, "eterhub:server", "Aller sur un serveur", List.of(Argument.required("serveur")),
                (player, args) -> network.connect(player, args.getFirst(), 0));
        actions.register(plugin, "eterhub:zone", "Aller à un lieu du village", List.of(zone),
                (player, args) -> map.travel(player, args.getFirst().toLowerCase(), true));
        actions.register(plugin, "eterhub:map", "La carte du village", List.of(), (player, args) -> map.open(player));
        actions.register(plugin, "eterhub:visibility", "Changer qui l'on voit au lobby", List.of(), (player, args) -> visibility.cycle(player));
        actions.register(plugin, "eterhub:fishing", "La pêche : classements et règles", List.of(), (player, args) -> fishing.openMenu(player));
        actions.register(plugin, "eterhub:fishmonger", "Le poissonnier du lac : il rachète les poissons", List.of(),
                (player, args) -> fishmonger.open(player));
        actions.register(plugin, "eterhub:fishing-journal", "Le carnet de pêche", List.of(), (player, args) -> fishing.openJournal(player));
        actions.register(plugin, "eterhub:museum", "Le musée des fouilles", List.of(), (player, args) -> archaeology.openMuseum(player));
        actions.register(plugin, "eterhub:tour", "La visite guidée du village", List.of(Argument.optional("restart")),
                (player, args) -> tour.begin(player, !args.isEmpty()));
        actions.register(plugin, "eterhub:soon", "Un lieu bientôt ouvert", List.of(Argument.optional("quoi")), (player, args) -> {
            messages.send(player, "soon.message", "what", args.isEmpty() ? "" : String.join(" ", args));
            Sounds.refuse(player);
        });
        actions.register(plugin, "eterhub:door", "Porte d'un donjon (config.yml > doors)", List.of(zone), (player, args) -> {
            String target = plugin.getConfig().getString("doors." + args.getFirst().toLowerCase(), "");
            if (target.isEmpty()) {
                messages.send(player, "door.closed", "zone", zones.plainName(player, args.getFirst().toLowerCase()));
                Sounds.refuse(player);
            } else {
                network.connect(player, target, 0);
            }
        });
        actions.registerCondition(plugin, "eterhub:discovered", "Le joueur a découvert ce lieu", List.of(zone),
                (player, args) -> zones.discoveries().has(player.getUniqueId(), args.getFirst().toLowerCase()));
        actions.registerCondition(plugin, "eterhub:in-zone", "Le joueur est dans ce lieu", List.of(zone),
                (player, args) -> zones.idOf(player).equalsIgnoreCase(args.getFirst()));
    }
}
