package fr.eternom.eterHub.module.spawn;

import fr.eternom.eterHub.module.core.HubPlayers;
import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.HubStats;
import fr.eternom.eterHub.module.zone.ZoneService;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * L'arrivée au lobby :
 * - avant la connexion (événement asynchrone de Paper) : ce que les lobbys savent du joueur est lu, et il apparaît à sa
 *   dernière position (arrival.return-to-last-position) ou sur un point d'arrivée de la place. Priorité basse : une
 *   téléportation réseau en cours (EterLib : rejoindre un ami, un home...) passe après et l'emporte ;
 * - à la connexion : titre et son de bienvenue (différents la première fois), message avec liens, vitesse au village,
 *   visite guidée pour un nouveau venu, staff prévenu d'un spawn dans un monde absent ;
 * - le dernier serveur joué hors lobby est noté (bouton « Reprendre »).
 */
public class ArrivalListener implements Listener {

    private static final float BASE_WALK_SPEED = 0.2f;
    /** Le staff garde son mode de jeu au lobby. */
    public static final String STAFF = "eterhub.staff";

    private final JavaPlugin plugin;
    private final HubPlayers players;
    private final Spawn spawn;
    private final ZoneService zones;
    private final Tour tour;
    private final HubSettings settings;
    private final HubStats stats;
    private final Messages messages;
    private final List<Consumer<UUID>> loaders;
    private final List<String> lobbyPrefixes;
    /** Joueurs dont la position d'arrivée a été choisie ici (sinon : réseau, ou rechargement). */
    private final Set<UUID> placed = ConcurrentHashMap.newKeySet();

    public ArrivalListener(JavaPlugin plugin, HubPlayers players, Spawn spawn, ZoneService zones, Tour tour, HubSettings settings,
                           HubStats stats, Messages messages, List<Consumer<UUID>> loaders) {
        this.plugin = plugin;
        this.players = players;
        this.spawn = spawn;
        this.zones = zones;
        this.tour = tour;
        this.settings = settings;
        this.stats = stats;
        this.messages = messages;
        this.loaders = loaders;
        this.lobbyPrefixes = plugin.getConfig().getStringList("lobby-servers").stream().map(name -> name.toLowerCase(Locale.ROOT)).toList();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        UUID uuid = event.getConnection().getProfile().getId();
        if (uuid == null) {
            return;
        }
        HubPlayers.Data data;
        try {
            data = players.load(uuid);
            for (Consumer<UUID> loader : loaders) {
                loader.accept(uuid);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Données du lobby non lues pour " + uuid + " : " + e.getMessage());
            return;
        }
        Location target = settings.bool("arrival.return-to-last-position", false) ? data.last().orElse(null) : null;
        event.setSpawnLocation(target != null ? target : spawn.arrival());
        placed.add(uuid);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        boolean loaded = players.isLoaded(uuid);
        if (!loaded) {
            // Rechargement à chaud : lecture maintenant, en tâche de fond
            Tasks.async(plugin, () -> {
                players.load(uuid);
                loaders.forEach(loader -> loader.accept(uuid));
            }, "Données du lobby non lues");
        }
        placed.remove(uuid);
        HubPlayers.Data data = players.of(player);
        boolean newcomer = loaded && data.firstVisit() && newToNetwork(uuid);
        stats.add("join");
        if (newcomer) {
            stats.add("first-join");
        }
        // Zone d'arrivée : réservée (ancienne position dans le Donjon secret...) = retour au spawn
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && !zones.update(player, player.getLocation(), null)) {
                spawn.teleport(player);
            }
        });
        applySpeed(player);
        forceSurvival(player);
        // EterSync peut remettre le mode de jeu un peu après l'arrivée : on repasse derrière
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                forceSurvival(player);
            }
        }, 40);
        welcome(player, newcomer);
        if (newcomer && settings.bool("activities.tour", true)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    tour.begin(player, true);
                }
            }, 60);
        } else if (loaded && data.tourStep() > 0 && settings.bool("activities.tour", true)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    tour.begin(player, false);
                }
            }, 60);
        }
        if (player.hasPermission("eterhub.admin") && !spawn.missingWorlds().isEmpty()) {
            messages.send(player, "spawn.missing-world", "points", String.join(", ", spawn.missingWorlds()));
        }
        Tasks.async(plugin, () -> EterLib.get().getPlayers().previousServer(uuid)
                .filter(server -> lobbyPrefixes.stream().noneMatch(server.toLowerCase(Locale.ROOT)::startsWith))
                .ifPresent(server -> players.setResume(uuid, server)), "Dernier serveur non noté");
    }

    /** Arrivé sur le réseau il y a moins de 10 minutes (l'annuaire d'EterLib) : un vrai nouveau venu. */
    private static boolean newToNetwork(UUID uuid) {
        return EterLib.get().getPlayers().get(uuid)
                .map(found -> found.firstSeen() <= 0 || System.currentTimeMillis() - found.firstSeen() < Duration.ofMinutes(10).toMillis())
                .orElse(true);
    }

    private void welcome(Player player, boolean newcomer) {
        String prefix = newcomer ? "welcome.first" : "welcome.back";
        player.showTitle(Title.title(messages.get(player, prefix + ".title", "player", player.getName()),
                messages.get(player, prefix + ".subtitle", "player", player.getName()),
                Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2500), Duration.ofMillis(800))));
        String sound = settings.text(newcomer ? "arrival.first-sound" : "arrival.sound", "");
        if (!sound.isEmpty()) {
            try {
                player.playSound(Sound.sound(Key.key(sound), Sound.Source.MASTER, 0.7f, 1f));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("arrival : son invalide " + sound);
            }
        }
        String raw = settings.bool("arrival.message", true) ? messages.raw(player, prefix + ".message") : null;
        if (raw != null) {
            // Les liens vont dans <click:open_url:'...'> : remplacés avant la mise en forme (MiniMessage ne remplit pas
            // les arguments d'une balise)
            raw = raw.replace("<wiki>", plugin.getConfig().getString("links.wiki", ""))
                    .replace("<discord>", plugin.getConfig().getString("links.discord", ""))
                    .replace("<shop>", plugin.getConfig().getString("links.shop", ""));
            player.sendMessage(messages.prefix().append(messages.render(raw,
                    net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.empty(), "player", player.getName())));
        }
    }

    /** Vitesse au village (arrival.speed-level, 0 = normale) : vitesse de marche, remise à la normale au départ. */
    public void applySpeed(Player player) {
        int level = Math.max(0, Math.min(5, settings.integer("arrival.speed-level", 1)));
        player.setWalkSpeed(Math.min(1f, BASE_WALK_SPEED * (1 + 0.2f * level)));
    }

    /**
     * Au lobby, tout le monde est en survie (arrival.force-survival) : on peut récolter, pêcher, casser les cultures. Le
     * staff (eterhub.staff) garde son mode (créatif, spectateur, mode staff). Ne touche à rien d'autre (inventaire).
     */
    public void forceSurvival(Player player) {
        if (settings.bool("arrival.force-survival", true) && !player.hasPermission(STAFF)
                && player.getGameMode() != GameMode.SURVIVAL) {
            player.setGameMode(GameMode.SURVIVAL);
        }
    }

    /** Un autre mode de jeu (synchronisation, commande) : remis en survie au tick suivant, sauf pour le staff. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() == GameMode.SURVIVAL || event.getPlayer().hasPermission(STAFF)) {
            return;
        }
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                forceSurvival(player);
            }
        });
    }

    /** En tout premier : la vitesse revient à la normale avant que d'autres plugins (EterSync) ne gardent le joueur. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuitFirst(PlayerQuitEvent event) {
        event.getPlayer().setWalkSpeed(BASE_WALK_SPEED);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        players.quit(event.getPlayer());
        placed.remove(event.getPlayer().getUniqueId());
    }
}
