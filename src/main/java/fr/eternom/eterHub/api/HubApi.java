package fr.eternom.eterHub.api;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * Ce qu'EterHub offre aux autres plugins, sur un lobby : son spawn et ses menus (sélecteur de serveurs, choix du lobby),
 * par exemple pour les relier à un PNJ. Personne d'autre ne lit les tables eterhub_* : on demande ici.
 * <pre>
 *     // compileOnly("com.github.Eternom:EterHub:&lt;tag&gt;") ; plugin.yml : softdepend: [EterHub]
 * </pre>
 */
public interface HubApi {

    /** L'API d'EterHub si le plugin tourne sur ce serveur (donc si c'est un lobby). */
    static Optional<HubApi> get() {
        return Optional.ofNullable(Bukkit.getServicesManager().load(HubApi.class));
    }

    /** Le spawn des lobbys (commun à tous), sur ce lobby. */
    Location spawn();

    /** Ramener le joueur au spawn. Thread principal. */
    void teleportToSpawn(Player player);

    /** Le sélecteur de serveurs. Thread principal. */
    void openServers(Player player);

    /** Le choix du lobby. Thread principal. */
    void openLobbies(Player player);
}
