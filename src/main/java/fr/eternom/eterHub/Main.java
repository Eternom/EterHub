package fr.eternom.eterHub;

import fr.eternom.eterHub.listeners.Commands;
import fr.eternom.eterHub.listeners.Events;
import fr.eternom.eterHub.module.network.NetworkGui;
import fr.eternom.eterHub.module.spawn.Spawn;
import fr.eternom.eterHub.module.visibility.Visibility;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sql.Database;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Le contenu des lobbys (à installer seulement sur eux) : endroit protégé, spawn, sélecteur de serveurs, choix du
 * lobby, masquer les joueurs, double saut et plaques de lancement. Le côté proxy (arrivée, /lobby, renvoi au lobby)
 * est EterVelocityLobby. Ne touche jamais à l'inventaire ni au mode de jeu : EterSync peut tourner sur un lobby.
 */
public final class Main extends JavaPlugin {

    /** Version minimale d'EterLib : état des serveurs, joueurs par serveur et connect depuis 1.7.0. */
    private static final String REQUIRED_ETERLIB = "1.7.0";

    /** Préfixe des tables d'EterHub dans la base commune : eterhub_lobbies, eterhub_preferences, eterhub_spawn. */
    private static final String TABLE_PREFIX = "eterhub_";

    private Messages messages;
    private Spawn spawn;
    private NetworkGui network;
    private Visibility visibility;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // En premier : vérifie la version d'EterLib (un EterLib < 1.3.0 n'a pas requireVersion, d'où le catch)
        try {
            if (!EterLib.requireVersion(this, REQUIRED_ETERLIB)) {
                return;
            }
        } catch (LinkageError tooOld) {
            getLogger().severe("EterLib " + REQUIRED_ETERLIB + " ou plus récent est nécessaire.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        EterLib lib = EterLib.get();
        messages = lib.messages(this, "en_us", "fr_fr");
        Database database = lib.database(TABLE_PREFIX);

        // Messages entre lobbys : un spawn changé sur l'un s'applique aux autres
        spawn = new Spawn(this, database, lib.network(this, "eterhub", messages));
        network = new NetworkGui(this, lib, database, messages);
        visibility = new Visibility(this, database, messages);

        new Commands(this);
        new Events(this);
        network.start();
    }

    public Messages getMessages() {
        return messages;
    }

    public Spawn getSpawn() {
        return spawn;
    }

    public NetworkGui getNetwork() {
        return network;
    }

    public Visibility getVisibility() {
        return visibility;
    }
}
