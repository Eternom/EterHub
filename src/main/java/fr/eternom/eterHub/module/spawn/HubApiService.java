package fr.eternom.eterHub.module.spawn;

import fr.eternom.eterHub.api.HubApi;
import fr.eternom.eterHub.module.network.NetworkGui;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** L'API d'EterHub (HubApi) : le plugin lui-même, vu de l'extérieur. */
public class HubApiService implements HubApi {

    private final Spawn spawn;
    private final NetworkGui network;

    public HubApiService(Spawn spawn, NetworkGui network) {
        this.spawn = spawn;
        this.network = network;
    }

    @Override
    public Location spawn() {
        return spawn.location();
    }

    @Override
    public void teleportToSpawn(Player player) {
        spawn.teleport(player);
    }

    @Override
    public void openServers(Player player) {
        network.openServers(player);
    }

    @Override
    public void openLobbies(Player player) {
        network.openLobbies(player);
    }
}
