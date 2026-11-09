package fr.eternom.eterHub.module.network;

import fr.eternom.eterHub.module.core.HubPlayers;
import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.social.FriendsView;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.task.Tasks;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sélecteur de serveurs (/servers) et choix du lobby (/lobbies).
 * Serveurs du sélecteur : config.yml (selector.servers : icône, case, capacité, « bientôt », commande ou action à la
 * place d'une connexion, ex. l'accès direct au monde ressource). État : barre de remplissage, plein, maintenance
 * (/eterhub maintenance, le staff passe), bientôt, hors ligne ; amis en ligne sur chaque serveur ; bouton
 * « Reprendre » vers le dernier serveur joué. Lobbys : ceux où EterHub tourne (signe de vie toutes les 5 s, retiré à
 * l'arrêt) ; le moins rempli est conseillé, les amis présents sont indiqués. Les menus ouverts se rafraîchissent.
 */
public class NetworkGui {

    public static final String MAINTENANCE_BYPASS = "eterhub.maintenance.bypass";
    private static final String LOBBIES = "lobbies";
    private static final String MAINTENANCE = "eterhub:maintenance";
    private static final long REFRESH_TICKS = 5 * 20;
    private static final Duration LOBBY_ALIVE = Duration.ofSeconds(30);
    private static final Duration LOBBY_FORGOTTEN = Duration.ofMinutes(10);

    /** Un serveur du sélecteur. command / action : à la place d'une connexion. */
    record Entry(String server, Material icon, int slot, int capacity, boolean soon, String command, String action) {
    }

    private final JavaPlugin plugin;
    private final EterLib lib;
    private final Database database;
    private final Messages messages;
    private final HubSettings settings;
    private final HubPlayers players;
    private final FriendsView friends;
    private volatile List<Entry> entries;
    private final BackButton selectorBack;
    private final BackButton lobbiesBack;
    private volatile Map<String, Integer> counts = Map.of();
    private volatile List<String> lobbies = List.of();
    private volatile Map<String, String> maintenance = Map.of();

    public NetworkGui(JavaPlugin plugin, EterLib lib, Database database, Messages messages, HubSettings settings,
                      HubPlayers players, FriendsView friends) {
        this.plugin = plugin;
        this.lib = lib;
        this.database = database;
        this.messages = messages;
        this.settings = settings;
        this.players = players;
        this.friends = friends;
        this.entries = entries(plugin.getConfig().getConfigurationSection("selector.servers"));
        this.selectorBack = lib.backButton(plugin.getConfig().getString("menus.selector.back-command", ""));
        this.lobbiesBack = lib.backButton(plugin.getConfig().getString("menus.lobbies.back-command", ""));
        database.createTable(LOBBIES,
                Column.of("name", Column.Type.STRING).length(64).primaryKey(),
                Column.of("last_seen", Column.Type.LONG));
    }

    public void reloadConfig() {
        entries = entries(plugin.getConfig().getConfigurationSection("selector.servers"));
    }

    /** Toutes les 5 s en tâche de fond ; les menus ouverts se rafraîchissent juste après. */
    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            refresh();
            Bukkit.getScheduler().runTask(plugin, this::refreshOpenMenus);
        }, 0, REFRESH_TICKS);
    }

    /** Arrêt : ce lobby disparaît tout de suite de la liste (plus de joueurs envoyés vers un lobby qui s'arrête). */
    public void stop() {
        try {
            database.delete(LOBBIES, Map.of("name", lib.getServerName()));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Lobby non retiré de la liste : " + e.getMessage());
        }
    }

    public void openServers(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> friendsByServer(uuid),
                byServer -> player.openInventory(new ServerMenu(this, player, byServer).getInventory()), () -> messages.send(player, "error.generic"));
    }

    public void openLobbies(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> friendsByServer(uuid),
                byServer -> player.openInventory(new LobbyMenu(this, player, byServer).getInventory()), () -> messages.send(player, "error.generic"));
    }

    /** Serveur -> pseudos des amis en ligne dessus. Bloquant. */
    private Map<String, List<String>> friendsByServer(UUID player) {
        Map<String, List<String>> byServer = new HashMap<>();
        friends.online(player).forEach((friend, server) -> byServer.computeIfAbsent(server, key -> new ArrayList<>())
                .add(lib.getPlayers().get(friend).map(found -> found.name()).orElse("?")));
        return byServer;
    }

    private void refreshOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            var holder = player.getOpenInventory().getTopInventory().getHolder(false);
            if (holder instanceof ServerMenu menu) {
                menu.render();
            } else if (holder instanceof LobbyMenu menu) {
                menu.render();
            }
        }
    }

    /** Clic sur un serveur : maintenance, plein, bientôt, action ou commande, sinon connexion. */
    void choose(Player player, Entry entry) {
        if (entry.soon()) {
            messages.send(player, "network.soon", "server", displayName(entry.server()));
            Sounds.refuse(player);
            return;
        }
        if (entry.action() != null && !entry.action().isBlank()) {
            player.closeInventory();
            lib.getActions().run(player, List.of(entry.action()));
            return;
        }
        if (entry.command() != null && !entry.command().isBlank()) {
            player.closeInventory();
            player.performCommand(entry.command());
            return;
        }
        connect(player, entry.server(), entry.capacity());
    }

    /** Envoie le joueur sur server (en ligne, pas ici, pas en maintenance, pas plein). */
    public void connect(Player player, String server, int capacity) {
        String name = displayName(server);
        if (isHere(server)) {
            messages.send(player, "network.here", "server", name);
        } else if (!isOnline(server)) {
            messages.send(player, "network.offline", "server", name);
            Sounds.refuse(player);
        } else if (inMaintenance(server) && !player.hasPermission(MAINTENANCE_BYPASS)) {
            messages.send(player, "network.maintenance", "server", name);
            Sounds.refuse(player);
        } else if (capacity > 0 && players(server) >= capacity && !player.hasPermission("eterhub.full.bypass")) {
            messages.send(player, "network.full", "server", name);
            Sounds.refuse(player);
        } else {
            player.closeInventory();
            messages.send(player, "network.connecting", "server", name);
            lib.getTeleports().connect(player, server);
        }
    }

    /** Le dernier serveur joué hors lobby, s'il y a lieu (bouton « Reprendre »). */
    Optional<String> resume(Player player) {
        return players.of(player).resume().filter(server -> !isHere(server));
    }

    boolean isHere(String server) {
        return server.equals(lib.getServerName());
    }

    boolean isOnline(String server) {
        return isHere(server) || lib.getServers().isOnline(server);
    }

    boolean inMaintenance(String server) {
        return maintenance.containsKey(server);
    }

    int players(String server) {
        return isHere(server) ? Bukkit.getOnlinePlayers().size() : counts.getOrDefault(server, 0);
    }

    String displayName(String server) {
        return lib.getServerDisplayName(server);
    }

    /** Lignes communes : joueurs (et barre de remplissage), amis, puis l'état et l'action. */
    List<Component> state(Player viewer, String server, int capacity, boolean soon, List<String> friendNames) {
        List<Component> lines = new ArrayList<>();
        int count = players(server);
        if (capacity > 0) {
            lines.add(messages.get(viewer, "network.state.fill",
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.parsed("bar", bar(count, capacity)),
                    "count", String.valueOf(count), "capacity", String.valueOf(capacity)));
        } else {
            lines.add(messages.get(viewer, "network.state.players", "count", String.valueOf(count)));
        }
        if (friendNames != null && !friendNames.isEmpty()) {
            lines.add(messages.get(viewer, "network.state.friends", "friends", String.join(", ",
                    friendNames.subList(0, Math.min(4, friendNames.size()))) + (friendNames.size() > 4 ? "…" : "")));
        }
        String status;
        if (soon) {
            status = "network.state.soon";
        } else if (isHere(server)) {
            status = "network.state.here";
        } else if (!isOnline(server)) {
            status = "network.state.offline";
        } else if (inMaintenance(server)) {
            status = "network.state.maintenance";
        } else if (capacity > 0 && count >= capacity) {
            status = "network.state.full";
        } else {
            status = "network.state.online";
        }
        lines.add(messages.get(viewer, status));
        return lines;
    }

    private static String bar(int count, int capacity) {
        int filled = (int) Math.round(10.0 * Math.min(count, capacity) / capacity);
        String color = count >= capacity ? "<red>" : filled >= 7 ? "<gold>" : "<green>";
        return color + "|".repeat(filled) + "<dark_gray>" + "|".repeat(10 - filled);
    }

    List<Entry> entries() {
        return entries;
    }

    public List<String> lobbiesView() {
        return lobbies;
    }

    List<String> lobbies() {
        return lobbies;
    }

    Material lobbyIcon() {
        Material icon = Material.matchMaterial(plugin.getConfig().getString("lobbies.icon", "BEACON"));
        return icon == null ? Material.BEACON : icon;
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

    FriendsView friends() {
        return friends;
    }

    HubSettings settings() {
        return settings;
    }

    // ---------- Maintenance (commune, Redis) ----------

    public void setMaintenance(String server, boolean on, String author) {
        Tasks.async(plugin, () -> {
            if (on) {
                lib.getRedis().setHashField(MAINTENANCE, server, author);
            } else {
                lib.getRedis().deleteHashField(MAINTENANCE, server);
            }
            settings.log("maintenance:" + server, on ? null : "on", on ? "on" : null, author);
            refresh();
        }, "Maintenance non changée");
    }

    public Map<String, String> maintenance() {
        return maintenance;
    }

    private void refresh() {
        try {
            long now = System.currentTimeMillis();
            database.set(LOBBIES, Map.of("name", lib.getServerName(), "last_seen", now), "name");
            database.execute("DELETE FROM " + database.table(LOBBIES) + " WHERE last_seen IS NULL OR last_seen < ?",
                    now - LOBBY_FORGOTTEN.toMillis());
            counts = lib.getPlayers().countByServer();
            lobbies = database.query("SELECT name FROM " + database.table(LOBBIES) + " WHERE last_seen >= ?",
                    now - LOBBY_ALIVE.toMillis()).stream().map(row -> row.getString("name")).sorted().toList();
            maintenance = Map.copyOf(lib.getRedis().getHash(MAINTENANCE));
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
            if (icon == null || !icon.isItem() || slot < 0 || slot >= ServerMenu.SIZE || slot == ServerMenu.BACK || slot == ServerMenu.RESUME) {
                plugin.getLogger().warning("selector.servers." + server + " : icône ou case invalide, ignoré");
                continue;
            }
            list.add(new Entry(server, icon, slot, Math.max(0, section.getInt(server + ".capacity", 0)),
                    section.getBoolean(server + ".soon", false), section.getString(server + ".command", ""),
                    section.getString(server + ".action", "")));
        }
        return List.copyOf(list);
    }
}
