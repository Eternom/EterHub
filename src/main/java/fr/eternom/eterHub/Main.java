package fr.eternom.eterHub;

import fr.eternom.eterHub.api.HubApi;
import fr.eternom.eterHub.module.activity.Archaeology;
import fr.eternom.eterHub.module.activity.Fishing;
import fr.eternom.eterHub.module.activity.FishingStore;
import fr.eternom.eterHub.module.activity.FishItems;
import fr.eternom.eterHub.module.activity.Fishmonger;
import fr.eternom.eterHub.module.farm.FarmMarket;
import fr.eternom.eterHub.module.farm.FarmStore;
import fr.eternom.eterHub.module.farm.Farming;
import fr.eternom.eterHub.module.heads.HeadCommand;
import fr.eternom.eterHub.module.heads.HeadEditor;
import fr.eternom.eterHub.module.heads.HeadMenus;
import fr.eternom.eterHub.module.heads.HeadService;
import fr.eternom.eterHub.module.heads.HeadStore;
import fr.eternom.eterHub.module.activity.HubEvents;
import fr.eternom.eterHub.module.activity.Parkour;
import fr.eternom.eterHub.module.activity.ParkourStore;
import fr.eternom.eterHub.module.admin.AdminCommand;
import fr.eternom.eterHub.module.admin.HubActions;
import fr.eternom.eterHub.module.admin.HubApiService;
import fr.eternom.eterHub.module.core.HubPlayers;
import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.HubStats;
import fr.eternom.eterHub.module.display.Holograms;
import fr.eternom.eterHub.module.display.Tips;
import fr.eternom.eterHub.module.movement.Cosmetics;
import fr.eternom.eterHub.module.movement.MovementListener;
import fr.eternom.eterHub.module.movement.PadStore;
import fr.eternom.eterHub.module.movement.Pads;
import fr.eternom.eterHub.module.network.NetworkCommand;
import fr.eternom.eterHub.module.network.NetworkGui;
import fr.eternom.eterHub.module.network.PortalListener;
import fr.eternom.eterHub.module.protect.BuildMode;
import fr.eternom.eterHub.module.protect.ProtectionListener;
import fr.eternom.eterHub.module.social.FriendsView;
import fr.eternom.eterHub.module.social.Profiles;
import fr.eternom.eterHub.module.social.Seats;
import fr.eternom.eterHub.module.social.Visibility;
import fr.eternom.eterHub.module.spawn.ArrivalListener;
import fr.eternom.eterHub.module.spawn.Spawn;
import fr.eternom.eterHub.module.spawn.SpawnCommands;
import fr.eternom.eterHub.module.spawn.Tour;
import fr.eternom.eterHub.module.spawn.VoidListener;
import fr.eternom.eterHub.module.zone.Discoveries;
import fr.eternom.eterHub.module.zone.VillageMap;
import fr.eternom.eterHub.module.zone.ZoneCommand;
import fr.eternom.eterHub.module.zone.ZoneListener;
import fr.eternom.eterHub.module.zone.ZoneService;
import fr.eternom.eterHub.module.zone.ZoneStore;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.ranking.RankingSource;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.task.Tasks;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Le village des lobbys (à installer seulement sur eux) : lieux nommés et tracés en jeu, avec leurs règles et leur
 * ambiance, découverte et carte du village, arrivée et visite guidée, protection complète, double saut, plaques et
 * ascenseurs, activités (pêche au lac, fouilles et musée, parcours, chasse aux têtes, évènements et annonces),
 * hologrammes et tableaux d'honneur, sélecteur de serveurs et choix du lobby, visibilité, s'asseoir, profils.
 * Tout ce qui se règle en jeu est en base, commun à tous les lobbys : un nouveau lobby n'a rien à régler. Le côté proxy
 * (arrivée, /lobby) est EterVelocityLobby. Ne touche jamais à l'inventaire (EterSync peut tourner sur un lobby) ;
 * tout le monde y est en survie, sauf le staff (arrival.force-survival).
 */
public final class Main extends JavaPlugin {

    private static final String REQUIRED_ETERLIB = "1.25.0";
    private static final String TABLE_PREFIX = "eterhub_";

    private Messages messages;
    private HubStats stats;
    private NetworkGui network;
    private Holograms holograms;
    private Archaeology archaeology;
    private Farming farming;
    private Seats seats;
    private boolean started;

    @Override
    public void onEnable() {
        saveDefaultConfig();
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
        // Plugin de lobby : il fige le monde et donne le double saut. Installé par erreur sur une survie, il l'abîmerait.
        String server = lib.getServerName().toLowerCase(Locale.ROOT);
        List<String> lobbies = getConfig().getStringList("lobby-servers").stream().map(name -> name.toLowerCase(Locale.ROOT)).toList();
        if (lobbies.stream().noneMatch(server::startsWith)) {
            getLogger().severe("Ce serveur (" + lib.getServerName() + ") n'est pas un lobby (lobby-servers : " + lobbies
                    + ") : EterHub ne touche à rien et s'arrête. Retire-le de ce serveur.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        messages = lib.messages(this, "en_us", "fr_fr");
        Database database = lib.database(TABLE_PREFIX);
        NetworkBus bus = lib.network(this, "eterhub", messages);

        // Socle
        HubSettings settings = new HubSettings(this, database, bus);
        stats = new HubStats(this, database);
        HubPlayers players = new HubPlayers(this, database);
        FriendsView friends = FriendsView.create();

        // Lieux
        ZoneStore zoneStore = new ZoneStore(database);
        Discoveries discoveries = new Discoveries(this, database);
        ZoneService zones = new ZoneService(this, zoneStore, discoveries, settings, stats, messages, bus);
        VillageMap map = new VillageMap(zones, messages, settings, lib.backButton(getConfig().getString("menus.map.back-command", "")));

        // Activités
        FishingStore fishingStore = new FishingStore(database);
        FishItems fishItems = new FishItems(this);
        fr.eternom.eterHub.module.activity.Baits baits = new fr.eternom.eterHub.module.activity.Baits(this, messages);
        Fishing fishing = new Fishing(this, zones, fishingStore, fishItems, baits, settings, stats, messages, bus,
                lib.backButton(getConfig().getString("menus.activities.back-command", "")));
        Fishmonger fishmonger = new Fishmonger(this, fishItems, settings, stats, messages,
                lib.backButton(getConfig().getString("menus.activities.back-command", "")), fishing::table, baits);
        // Les coins de pêche sans nom (en plus des lieux à la règle fishing)
        fr.eternom.eterHub.module.activity.FishingSpots fishingSpots = new fr.eternom.eterHub.module.activity.FishingSpots(this, database, bus);
        zones.setFishingSpots(fishingSpots::contains);
        Tasks.async(this, fishingSpots::reload, "Coins de pêche non lus");
        fishing.setFishOfTheDay(fishmonger::fishOfTheDay);
        archaeology = new Archaeology(this, zones, database, settings, stats, messages,
                lib.backButton(getConfig().getString("menus.activities.back-command", "")));
        Parkour parkour = new Parkour(this, new ParkourStore(database), settings, stats, messages, bus);
        HeadService heads = new HeadService(this, new HeadStore(database), zones, settings, stats, messages, bus);
        HeadMenus headMenus = new HeadMenus(heads, lib.backButton(getConfig().getString("menus.activities.back-command", "")));
        farming = new Farming(this, zones, new FarmStore(database), settings, stats, messages,
                lib.backButton(getConfig().getString("menus.activities.back-command", "")));
        FarmMarket farmMarket = new FarmMarket(farming, lib.backButton(getConfig().getString("menus.activities.back-command", "")));
        HubEvents events = new HubEvents(this, settings, messages, bus);

        // Arrivée
        Spawn spawn = new Spawn(this, database, bus);
        SpawnCommands spawnCommands = new SpawnCommands(spawn, settings, messages);
        Tour tour = new Tour(this, players, zones, settings, stats, messages);
        ArrivalListener arrival = new ArrivalListener(this, players, spawn, zones, tour, settings, stats, messages,
                List.of(discoveries::load, heads::load, archaeology::load, farming::load));
        VoidListener voidListener = new VoidListener(this, spawn, settings);
        voidListener.setInterceptor(player -> {
            if (parkour.running(player)) {
                parkour.back(player);
                return true;
            }
            return false;
        });

        // Protection et mouvement
        BuildMode build = new BuildMode(messages);
        ProtectionListener protection = new ProtectionListener(this, zones, build, settings);
        Cosmetics cosmetics = new Cosmetics(this, settings);
        cosmetics.setHidden(player -> lib.getVanish().isVanished(player.getUniqueId()) || parkour.running(player));
        MovementListener movement = new MovementListener(this, zones, settings, cosmetics);
        movement.setBusy(parkour::running);
        Pads pads = new Pads(this, new PadStore(database), settings, messages, bus, arrival::applySpeed);

        // Affichages
        Function<Player, List<Component>> progress = player -> progress(player, zones, heads, archaeology);
        holograms = new Holograms(this, database, settings, messages, bus, progress);
        Tips tips = new Tips(this, zones, settings, messages);
        tips.setBusy(parkour::running);

        // Navigation et social
        network = new NetworkGui(this, lib, database, messages, settings, players, friends);
        Visibility visibility = new Visibility(this, database, messages, friends);
        visibility.setIsolated(zones::isolated);
        zones.setSceneChanged(visibility::refresh);
        seats = new Seats(this, zones, settings);
        Profiles profiles = new Profiles(this, settings, messages, friends, progress);

        // Commandes
        NetworkCommand networkCommand = new NetworkCommand(network, messages);
        register("spawn", spawnCommands);
        register("servers", networkCommand);
        register("lobbies", networkCommand);
        register("visibility", visibility);
        register("map", new SimpleCommand(map::open));
        register("fishing", fishing);
        register("museum", archaeology);
        register("parkour", parkour);
        register("tour", tour);
        AdminCommand admin = new AdminCommand(this, messages, settings, stats, network, () -> {
            reloadEverywhere(bus);
        }, () -> info(lib, zones, heads, parkour));
        admin.add("spawn", spawnCommands);
        ZoneCommand zoneCommand = new ZoneCommand(this, zones, zoneStore, map, settings, messages);
        admin.add("zone", zoneCommand);
        admin.add("fishing", new fr.eternom.eterHub.module.activity.FishingSpotCommand(fishingSpots, zoneCommand, settings, messages));
        getServer().getPluginManager().registerEvents(zoneCommand, this);
        admin.add("pad", pads);
        admin.add("holo", holograms);
        admin.add("parkour", parkour);
        admin.add("head", new HeadEditor(heads, headMenus, settings));
        register("heads", new HeadCommand(heads, headMenus));
        register("harvest", farming);
        admin.add("event", events);
        admin.add("announce", events.announcer());
        admin.add("build", build);
        register("eterhub", admin);
        bus.on("reload", data -> reloadLocal(settings, zones, fishing, protection));
        this.reloadLocal = () -> reloadLocal(settings, zones, fishing, protection);

        HubActions.register(this, messages, network, spawnCommands, zones, map, visibility, fishing, fishmonger, archaeology, tour);
        // Les acheteurs des Champs (PNJ) et le carnet des récoltes
        lib.getActions().register(this, "eterhub:farmer", "Le fermier : rachète les récoltes des champs", List.of(),
                (player, args) -> farmMarket.open(player, "farmer"));
        lib.getActions().register(this, "eterhub:vineyard", "Le vigneron : rachète les grappes des vignes", List.of(),
                (player, args) -> farmMarket.open(player, "vineyard"));
        lib.getActions().register(this, "eterhub:harvest-journal", "Le carnet des récoltes", List.of(), (player, args) -> farming.openJournal(player));
        // La grosse récompense de la chasse aux têtes, auprès d'un PNJ (une fois par saison), et ses conditions
        fr.eternom.eterLib.module.action.Actions.Argument huntArgument = new fr.eternom.eterLib.module.action.Actions.Argument("chasse", false,
                typed -> new java.util.ArrayList<>(heads.hunts().keySet()));
        lib.getActions().register(this, "eterhub:heads-claim", "Récompense de la chasse aux têtes (toutes trouvées, une fois par saison)",
                List.of(huntArgument), (player, args) -> heads.claim(player, args.isEmpty() ? "main" : args.getFirst().toLowerCase(Locale.ROOT)));
        lib.getActions().registerCondition(this, "eterhub:heads-complete", "Le joueur a trouvé toutes les têtes de la chasse",
                List.of(huntArgument), (player, args) -> heads.complete(player.getUniqueId(), args.isEmpty() ? "main" : args.getFirst().toLowerCase(Locale.ROOT)));
        lib.getActions().registerCondition(this, "eterhub:heads-claimed", "Le joueur a déjà récupéré la récompense de la chasse",
                List.of(huntArgument), (player, args) -> heads.hasClaimed(player.getUniqueId(), args.isEmpty() ? "main" : args.getFirst().toLowerCase(Locale.ROOT)));
        // Le chasseur de têtes (PNJ) : le menu, et le lieu où il manque le plus de têtes au joueur
        lib.getActions().register(this, "eterhub:heads", "La chasse aux têtes (menu et indice)", List.of(), (player, args) -> {
            headMenus.open(player);
            String zone = heads.mostMissingZone(player.getUniqueId());
            if (!zone.isEmpty()) {
                messages.send(player, "heads.npc-hint", "zone", heads.zoneName(player, zone));
            }
        });
        getServer().getServicesManager().register(HubApi.class,
                new HubApiService(spawn, network, zones, map, heads, parkour, tour), this, ServicePriority.Normal);
        getServer().getServicesManager().register(RankingSource.class, fishingStore.monthly(), this, ServicePriority.Normal);

        // Écouteurs
        for (Listener listener : List.of(arrival, voidListener, new ZoneListener(zones, messages), protection, build, movement, pads,
                fishing, archaeology, parkour, heads, events, tour, holograms, visibility, seats, profiles, farming,
                new fr.eternom.eterHub.module.activity.QuestSignals())) {
            getServer().getPluginManager().registerEvents(listener, this);
        }
        if (getConfig().getBoolean("portals.open-selector", true)) {
            getServer().getPluginManager().registerEvents(new PortalListener(this, network), this);
        }

        // Départ
        stats.start();
        zones.start();
        tour.start();
        pads.reload();
        cosmetics.start();
        parkour.start();
        heads.start();
        farming.start();
        events.start();
        holograms.start();
        tips.start();
        network.start();
        visibility.start();
        started = true;
    }

    private Runnable reloadLocal = () -> { };

    /** /eterhub reload : ici, puis sur les autres lobbys. */
    private void reloadEverywhere(NetworkBus bus) {
        reloadLocal.run();
        Tasks.async(this, () -> bus.publish("reload", new com.google.gson.JsonObject()), "Rechargement non annoncé");
    }

    private void reloadLocal(HubSettings settings, ZoneService zones, Fishing fishing, ProtectionListener protection) {
        reloadConfig();
        zones.loadDefinitions();
        zones.reload();
        fishing.reload();
        archaeology.reload();
        farming.reload();
        network.reloadConfig();
        protection.applyWorldRules();
        Tasks.async(this, settings::load, "Réglages non relus");
    }

    /** Progression au village d'un joueur connecté ici (hologramme « progress », profil). */
    private List<Component> progress(Player player, ZoneService zones, HeadService heads, Archaeology archaeology) {
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get(player, "progress.discoveries", "count", String.valueOf(zones.discoveredCount(player.getUniqueId())),
                "total", String.valueOf(zones.discoverableCount())));
        lines.addAll(heads.progress(player));
        int[] museum = archaeology.progress(player.getUniqueId());
        if (museum[1] > 0) {
            lines.add(messages.get(player, "progress.museum", "count", String.valueOf(museum[0]), "total", String.valueOf(museum[1])));
        }
        return lines;
    }

    private List<String> info(EterLib lib, ZoneService zones, HeadService heads, Parkour parkour) {
        return List.of(
                "<primary>EterHub " + getPluginMeta().getVersion() + " <dark_gray>· <info>" + lib.getServerName(),
                "<info>Joueurs ici : <accent>" + Bukkit.getOnlinePlayers().size(),
                "<info>Lieux tracés : <accent>" + zones.index().size() + "/" + zones.zones().size(),
                "<info>Têtes : <accent>" + heads.heads().size() + " <dark_gray>· <info>Parcours : <accent>" + parkour.courses().size(),
                "<info>Lobbys : <accent>" + String.join(", ", network.lobbiesView()),
                "<info>Maintenance : <accent>" + (network.maintenance().isEmpty() ? "-" : String.join(", ", network.maintenance().keySet())));
    }

    private void register(String name, TabExecutor executor) {
        PluginCommand command = Objects.requireNonNull(getCommand(name), "Commande absente du plugin.yml : " + name);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    @Override
    public void onDisable() {
        if (!started) {
            return;
        }
        holograms.stop();
        archaeology.restoreAll();
        farming.restoreAll();
        seats.standAll();
        network.stop();
        stats.flush();
        Bukkit.getOnlinePlayers().forEach(player -> player.setWalkSpeed(0.2f));
    }

    public Messages getMessages() {
        return messages;
    }

    /** Une commande joueur sans argument qui ouvre un menu (/map). */
    private record SimpleCommand(java.util.function.Consumer<Player> action) implements TabExecutor {
        @Override
        public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
            if (sender instanceof Player player) {
                action.accept(player);
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
            return List.of();
        }
    }
}
