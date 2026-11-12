package fr.eternom.eterHub.module.admin;

import java.util.List;
import fr.eternom.eterHub.api.HubApi;
import fr.eternom.eterHub.module.heads.HeadService;
import fr.eternom.eterHub.module.network.NetworkGui;
import fr.eternom.eterHub.module.spawn.Spawn;
import fr.eternom.eterHub.module.spawn.Tour;
import fr.eternom.eterHub.module.zone.VillageMap;
import fr.eternom.eterHub.module.zone.ZoneService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.Set;

/** L'API d'EterHub (HubApi) : le plugin vu de l'extérieur. */
public class HubApiService implements HubApi {

    private final Spawn spawn;
    private final NetworkGui network;
    private final ZoneService zones;
    private final VillageMap map;
    private final HeadService heads;
    private final List<java.util.function.Predicate<Player>> busy;
    private final List<java.util.function.Predicate<Player>> voidHandlers;
    private final Tour tour;

    public HubApiService(Spawn spawn, NetworkGui network, ZoneService zones, VillageMap map, HeadService heads, Tour tour,
                         List<java.util.function.Predicate<Player>> busy, List<java.util.function.Predicate<Player>> voidHandlers) {
        this.spawn = spawn;
        this.network = network;
        this.zones = zones;
        this.map = map;
        this.heads = heads;
        this.busy = busy;
        this.voidHandlers = voidHandlers;
        this.tour = tour;
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

    @Override
    public String zoneOf(Player player) {
        return zones.idOf(player);
    }

    @Override
    public Set<String> zones() {
        return new LinkedHashSet<>(zones.zones().keySet());
    }

    @Override
    public Set<String> discovered(Player player) {
        return zones.discoveries().of(player.getUniqueId());
    }

    @Override
    public void teleportToZone(Player player, String zone, boolean force) {
        map.travel(player, zone, force);
    }

    @Override
    public void openMap(Player player) {
        map.open(player);
    }

    @Override
    public int[] heads(Player player) {
        return new int[]{heads.count(player.getUniqueId(), "main"), heads.total("main")};
    }

    @Override
    public boolean isBusy(Player player) {
        return busy.stream().anyMatch(test -> test.test(player));
    }

    @Override
    public void addBusy(java.util.function.Predicate<Player> test) {
        busy.add(test);
    }

    @Override
    public void addVoidHandler(java.util.function.Predicate<Player> handler) {
        voidHandlers.add(handler);
    }

    @Override
    public void startTour(Player player, boolean restart) {
        tour.begin(player, restart);
    }
}
