package fr.eternom.eterHub.api;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.Set;

/**
 * Ce qu'EterHub offre aux autres plugins, sur un lobby : spawn, menus, lieux du village, découvertes, activités. Pour
 * EterQuest / BetonQuest : HubZoneEnterEvent et HubActivityEvent, plus les lectures ci-dessous. Personne d'autre ne
 * lit les tables eterhub_* : on demande ici.
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

    // ---------- Depuis 2.0.0 ----------

    /** Le lieu où est le joueur (identifiant de config.yml > zones), vide hors de tout lieu. */
    String zoneOf(Player player);

    /** Tous les lieux décrits (config.yml), dans leur ordre. */
    Set<String> zones();

    /** Les lieux découverts par un joueur connecté ici. */
    Set<String> discovered(Player player);

    /** Envoie le joueur au point d'arrivée d'un lieu (force : même non découvert). Thread principal. */
    void teleportToZone(Player player, String zone, boolean force);

    /** La carte du village. Thread principal. */
    void openMap(Player player);

    /** Têtes trouvées / total, pour un joueur connecté ici. */
    int[] heads(Player player);

    /** Le joueur est occupé par l'activité d'un autre plugin (ex : un parcours) : l'un d'eux le dit. Depuis 2.15.0. */
    boolean isBusy(Player player);

    /**
     * Un plugin déclare quand un joueur est occupé chez lui (ex : pendant un parcours) : pas de double saut ni de
     * plaques, cosmétiques cachés, astuces en pause. Thread principal. Depuis 2.15.0.
     */
    void addBusy(java.util.function.Predicate<Player> busy);

    /**
     * Chute dans le vide : handler renvoie true s'il s'en occupe (ex : retour au dernier point d'un parcours) ; sinon
     * (aucun), retour au spawn. Thread principal. Depuis 2.15.0.
     */
    void addVoidHandler(java.util.function.Predicate<Player> handler);

    /** La visite guidée (depuis le début si restart). Thread principal. */
    void startTour(Player player, boolean restart);
}
