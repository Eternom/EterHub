package fr.eternom.eterHub.module.network;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sélecteur de serveurs (/servers) et choix du lobby (/lobbies). Les serveurs du sélecteur sont dans config.yml
 * (selector.servers : icône et case) ; les lobbys sont ceux où EterHub est installé (table eterhub_lobbies, chacun
 * s'y inscrit au démarrage). Noms affichés, état en ligne et joueurs par serveur viennent d'EterLib, relus toutes
 * les 5 s en tâche de fond : un menu s'ouvre sans attendre la base. Un clic envoie sur le serveur (EterLib connect).
 */
public class NetworkGui {

    private static final String LOBBIES = "lobbies";
    private static final long REFRESH_TICKS = 5 * 20;

    /** Un serveur du sélecteur : son nom dans velocity.toml, son icône et sa case dans le menu. */
    record Entry(String server, Material icon, int slot) {
    }

    private final JavaPlugin plugin;
    private final EterLib lib;
    private final Database database;
    private final Messages messages;
    private final List<Entry> entries;
    private final Material lobbyIcon;
    private final BackButton selectorBack;
    private final BackButton lobbiesBack;
    private volatile Map<String, Integer> counts = Map.of();
    private volatile List<String> lobbies = List.of();

    public NetworkGui(JavaPlugin plugin, EterLib lib, Database database, Messages messages) {
        this.plugin = plugin;
        this.lib = lib;
        this.database = database;
        this.messages = messages;
        this.entries = entries(plugin.getConfig().getConfigurationSection("selector.servers"));
        Material icon = Material.matchMaterial(plugin.getConfig().getString("lobbies.icon", "BEACON"));
        this.lobbyIcon = icon == null ? Material.BEACON : icon;
        this.selectorBack = lib.backButton(plugin.getConfig().getString("menus.selector.back-command", ""));
        this.lobbiesBack = lib.backButton(plugin.getConfig().getString("menus.lobbies.back-command", ""));
        database.createTable(LOBBIES, Column.of("name", Column.Type.STRING).length(64).primaryKey());
    }

    /** Bloquant au premier appel (inscription de ce lobby), puis relecture toutes les 5 s en tâche de fond. */
    public void start() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            database.set(LOBBIES, Map.of("name", lib.getServerName()), "name");
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refresh, 0, REFRESH_TICKS);
        });
    }

    public void openServers(Player player) {
        player.openInventory(new ServerMenu(this, player).getInventory());
    }

    public void openLobbies(Player player) {
        player.openInventory(new LobbyMenu(this, player).getInventory());
    }

    /** Envoie le joueur sur server, s'il tourne et que ce n'est pas celui-ci. */
    void connect(Player player, String server) {
        String name = displayName(server);
        if (isHere(server)) {
            messages.send(player, "network.here", "server", name);
        } else if (!isOnline(server)) {
            messages.send(player, "network.offline", "server", name);
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
        } else {
            player.closeInventory();
            messages.send(player, "network.connecting", "server", name);
            lib.getTeleports().connect(player, server);
        }
    }

    boolean isHere(String server) {
        return server.equals(lib.getServerName());
    }

    boolean isOnline(String server) {
        return isHere(server) || lib.getServers().isOnline(server);
    }

    int players(String server) {
        return isHere(server) ? Bukkit.getOnlinePlayers().size() : counts.getOrDefault(server, 0);
    }

    String displayName(String server) {
        return lib.getServerDisplayName(server);
    }

    /** Lignes communes aux deux menus : joueurs, puis « tu es ici », « en ligne » ou « hors ligne » et l'action. */
    List<Component> state(Player viewer, String server) {
        String status = isHere(server) ? "network.state.here" : isOnline(server) ? "network.state.online" : "network.state.offline";
        return List.of(messages.get(viewer, "network.state.players", "count", String.valueOf(players(server))),
                messages.get(viewer, status));
    }

    List<Entry> entries() {
        return entries;
    }

    List<String> lobbies() {
        return lobbies;
    }

    Material lobbyIcon() {
        return lobbyIcon;
    }

    BackButton selectorBack() {
        return selectorBack;
    }

    BackButton lobbiesBack() {
        return lobbiesBack;
    }

    Messages messages() {
        return messages;
    }

    private void refresh() {
        try {
            counts = lib.getPlayers().countByServer();
            lobbies = database.get(LOBBIES, Map.of()).stream().map(row -> row.getString("name")).sorted().toList();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Serveurs du réseau non relus : " + e.getMessage());
        }
    }

    private List<Entry> entries(ConfigurationSection section) {
        List<Entry> list = new ArrayList<>();
        if (section == null) {
            return list;
        }
        for (String server : section.getKeys(false)) {
            Material icon = Material.matchMaterial(section.getString(server + ".icon", "GRASS_BLOCK"));
            int slot = section.getInt(server + ".slot", -1);
            if (icon == null || !icon.isItem() || slot < 0 || slot >= ServerMenu.SIZE) {
                plugin.getLogger().warning("selector.servers." + server + " : icône ou case invalide, ignoré");
                continue;
            }
            list.add(new Entry(server, icon, slot));
        }
        return list;
    }
}
