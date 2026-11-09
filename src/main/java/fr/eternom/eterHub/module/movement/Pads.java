package fr.eternom.eterHub.module.movement;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import com.google.gson.JsonObject;
import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.SubCommand;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Les plaques du village (lanceurs, ascenseurs, couloirs de vitesse, téléporteurs : Pad), plus :
 * - les plaques de lancement d'un type de bloc (launch-pads.plate), comme avant ;
 * - les trampolines (launch-pads.trampoline : un bloc, ex. SLIME_BLOCK) ;
 * - les couloirs de vitesse d'un type de bloc (launch-pads.speed-blocks).
 * Éditeur : /eterhub pad add <launch|elevator|speed|teleport> [force] [hauteur] [fixe] · mark · remove · list.
 * Une plaque se règle sur le bloc où l'on se tient (la plaque de pression elle-même, ou le bloc sous les pieds).
 */
public class Pads implements Listener, SubCommand {

    private static final String CHANGED = "pads";
    private static final long COOLDOWN_MILLIS = 1000;
    private static final int ELEVATOR_RANGE = 96;

    private final JavaPlugin plugin;
    private final PadStore store;
    private final HubSettings settings;
    private final Messages messages;
    private final NetworkBus bus;
    private final Consumer<Player> restoreSpeed;
    private volatile Map<String, Pad> byBlock = Map.of();
    private volatile Map<String, List<Pad>> elevators = Map.of();
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();
    private final Map<UUID, Pad.Target> marks = new HashMap<>();

    public Pads(JavaPlugin plugin, PadStore store, HubSettings settings, Messages messages, NetworkBus bus, Consumer<Player> restoreSpeed) {
        this.plugin = plugin;
        this.store = store;
        this.settings = settings;
        this.messages = messages;
        this.bus = bus;
        this.restoreSpeed = restoreSpeed;
        bus.on(CHANGED, data -> reload());
    }

    public void reload() {
        Tasks.async(plugin, () -> {
            List<Pad> pads = store.all();
            Map<String, Pad> blocks = new HashMap<>();
            Map<String, List<Pad>> columns = new HashMap<>();
            for (Pad pad : pads) {
                blocks.put(pad.key(), pad);
                if (pad.type() == Pad.Type.ELEVATOR) {
                    columns.computeIfAbsent(pad.column(), key -> new ArrayList<>()).add(pad);
                }
            }
            columns.values().forEach(list -> list.sort(Comparator.comparingInt(Pad::y)));
            byBlock = blocks;
            elevators = columns;
        }, "Plaques non relues");
    }

    private void changed() {
        reload();
        Tasks.async(plugin, () -> bus.publish(CHANGED, new JsonObject()), "Plaques non annoncées");
    }

    /** La plaque sous le joueur : sur le bloc où il est (plaque de pression), ou juste dessous. */
    private Optional<Pad> under(Location at) {
        World world = at.getWorld();
        Pad pad = byBlock.get(Pad.key(world.getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ()));
        if (pad == null) {
            pad = byBlock.get(Pad.key(world.getName(), at.getBlockX(), at.getBlockY() - 1, at.getBlockZ()));
        }
        return Optional.ofNullable(pad);
    }

    private boolean cooldown(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastUse.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MILLIS) {
            return true;
        }
        lastUse.put(player.getUniqueId(), now);
        return false;
    }

    // ---------- Utilisation ----------

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock() || !settings.bool("launch-pads.enabled", true)) {
            return;
        }
        Player player = event.getPlayer();
        Location to = event.getTo();
        Optional<Pad> pad = under(to);
        if (pad.isPresent()) {
            switch (pad.get().type()) {
                case LAUNCH -> {
                    if (!cooldown(player)) {
                        launch(player, pad.get().power(), pad.get().height(), pad.get().yaw());
                    }
                }
                case SPEED -> boost(player, pad.get().power());
                case TELEPORT -> {
                    if (!cooldown(player)) {
                        teleport(player, pad.get());
                    }
                }
                default -> {
                }
            }
            return;
        }
        Material below = to.clone().subtract(0, 1, 0).getBlock().getType();
        String trampoline = settings.text("launch-pads.trampoline", "SLIME_BLOCK");
        if (!trampoline.isEmpty() && below.name().equalsIgnoreCase(trampoline) && !cooldown(player)) {
            player.setVelocity(player.getVelocity().setY(settings.number("launch-pads.trampoline-power", 1.4)));
            player.playSound(player, Sound.BLOCK_SLIME_BLOCK_FALL, 0.8f, 1.2f);
            return;
        }
        if (plugin.getConfig().getStringList("launch-pads.speed-blocks").stream().anyMatch(name -> name.equalsIgnoreCase(below.name()))) {
            boost(player, 2);
        }
    }

    /** Les plaques de pression d'un type donné (launch-pads.plate), comme avant 2.0. */
    @EventHandler
    public void onPlate(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.PHYSICAL || block == null || !settings.bool("launch-pads.enabled", true)
                || byBlock.containsKey(Pad.key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()))) {
            return;
        }
        if (block.getType().name().equalsIgnoreCase(settings.text("launch-pads.plate", "LIGHT_WEIGHTED_PRESSURE_PLATE"))
                && !cooldown(event.getPlayer())) {
            launch(event.getPlayer(), settings.number("launch-pads.power", 2.5), settings.number("launch-pads.height", 1.0), null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onJump(PlayerJumpEvent event) {
        Player player = event.getPlayer();
        under(event.getFrom()).filter(pad -> pad.type() == Pad.Type.ELEVATOR).ifPresent(pad -> {
            if (!cooldown(player)) {
                elevate(player, pad, true);
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }
        Player player = event.getPlayer();
        under(player.getLocation()).filter(pad -> pad.type() == Pad.Type.ELEVATOR).ifPresent(pad -> {
            if (!cooldown(player)) {
                elevate(player, pad, false);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastUse.remove(event.getPlayer().getUniqueId());
        marks.remove(event.getPlayer().getUniqueId());
    }

    private void launch(Player player, double power, double height, Float yaw) {
        Vector direction;
        if (yaw != null) {
            double radians = Math.toRadians(yaw);
            direction = new Vector(-Math.sin(radians), 0, Math.cos(radians));
        } else {
            direction = player.getLocation().getDirection().setY(0);
        }
        if (direction.lengthSquared() < 1.0E-4) {
            direction = new Vector(0, 0, 0);
        } else {
            direction.normalize();
        }
        player.setVelocity(direction.multiply(power).setY(height));
        player.playSound(player, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.7f, 1.2f);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 15, 0.3, 0.1, 0.3, 0.05);
    }

    private void elevate(Player player, Pad from, boolean up) {
        List<Pad> column = elevators.getOrDefault(from.column(), List.of());
        Pad next = null;
        for (Pad pad : column) {
            if (up && pad.y() > from.y() && pad.y() - from.y() <= ELEVATOR_RANGE) {
                next = pad;
                break;
            }
            if (!up && pad.y() < from.y() && from.y() - pad.y() <= ELEVATOR_RANGE) {
                next = pad;
            }
        }
        if (next == null) {
            return;
        }
        World world = Bukkit.getWorld(next.world());
        if (world == null) {
            return;
        }
        Location at = player.getLocation();
        Location target = new Location(world, next.x() + 0.5, next.y() + 1, next.z() + 0.5, at.getYaw(), at.getPitch());
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        player.playSound(player, Sound.BLOCK_BEACON_ACTIVATE, 0.5f, up ? 1.8f : 1.2f);
        world.spawnParticle(Particle.END_ROD, target.clone().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.02);
    }

    private void boost(Player player, double seconds) {
        player.setWalkSpeed(Math.min(1f, Math.max(player.getWalkSpeed(), 0.36f)));
        long ticks = Math.max(20, (long) (seconds * 20));
        UUID uuid = player.getUniqueId();
        long stamp = System.nanoTime();
        boosts.put(uuid, stamp);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && Long.valueOf(stamp).equals(boosts.get(uuid))) {
                boosts.remove(uuid);
                restoreSpeed.accept(player);
            }
        }, ticks);
    }

    private final Map<UUID, Long> boosts = new ConcurrentHashMap<>();

    private void teleport(Player player, Pad pad) {
        Pad.Target target = pad.target();
        World world = target == null ? null : Bukkit.getWorld(target.world());
        if (world == null) {
            return;
        }
        player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.3);
        player.teleportAsync(new Location(world, target.x(), target.y(), target.z(), target.yaw(), target.pitch()),
                PlayerTeleportEvent.TeleportCause.PLUGIN);
        player.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.3f);
    }

    // ---------- Éditeur ----------

    /** Le bloc de la plaque : la plaque de pression où l'on est, sinon le bloc sous les pieds. */
    private static Block padBlock(Player player) {
        Block feet = player.getLocation().getBlock();
        if (Tag.PRESSURE_PLATES.isTagged(feet.getType()) || feet.getType() == Material.HEAVY_WEIGHTED_PRESSURE_PLATE
                || feet.getType() == Material.LIGHT_WEIGHTED_PRESSURE_PLATE) {
            return feet;
        }
        return feet.getRelative(0, -1, 0);
    }

    @Override
    public void run(Player player, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "add" -> add(player, args);
            case "mark" -> {
                Location at = player.getLocation();
                marks.put(player.getUniqueId(), new Pad.Target(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch()));
                messages.send(player, "editor.pad.marked");
            }
            case "remove" -> {
                Block block = padBlock(player);
                String author = player.getName();
                Tasks.async(plugin, player, () -> {
                    boolean removed = store.remove(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
                    if (removed) {
                        settings.log("pad", block.getX() + "," + block.getY() + "," + block.getZ(), null, author);
                    }
                    return removed;
                }, removed -> {
                    messages.send(player, removed ? "editor.pad.removed" : "editor.pad.none");
                    changed();
                }, () -> messages.send(player, "error.generic"));
            }
            case "list" -> {
                Location at = player.getLocation();
                List<Pad> near = byBlock.values().stream().filter(pad -> pad.world().equals(at.getWorld().getName()))
                        .filter(pad -> Math.abs(pad.x() - at.getBlockX()) <= 48 && Math.abs(pad.z() - at.getBlockZ()) <= 48)
                        .sorted(Comparator.comparingDouble(pad -> Math.pow(pad.x() - at.getX(), 2) + Math.pow(pad.z() - at.getZ(), 2)))
                        .limit(15).toList();
                messages.send(player, "editor.pad.list", "count", String.valueOf(near.size()), "total", String.valueOf(byBlock.size()));
                near.forEach(pad -> messages.send(player, "editor.pad.line", "type", pad.type().name().toLowerCase(Locale.ROOT),
                        "x", String.valueOf(pad.x()), "y", String.valueOf(pad.y()), "z", String.valueOf(pad.z()),
                        "power", String.valueOf(pad.power())));
            }
            default -> messages.send(player, "editor.pad.usage");
        }
    }

    private void add(Player player, String[] args) {
        Optional<Pad.Type> type = args.length > 1 ? Pad.Type.parse(args[1]) : Optional.empty();
        if (type.isEmpty()) {
            messages.send(player, "editor.pad.usage");
            return;
        }
        double power;
        double height;
        try {
            power = args.length > 2 ? Double.parseDouble(args[2].replace(',', '.')) : defaultPower(type.get());
            height = args.length > 3 ? Double.parseDouble(args[3].replace(',', '.')) : 1.0;
        } catch (NumberFormatException e) {
            messages.send(player, "editor.pad.usage");
            return;
        }
        boolean fixed = args.length > 4 && List.of("fixe", "fixed").contains(args[4].toLowerCase(Locale.ROOT));
        Pad.Target target = null;
        if (type.get() == Pad.Type.TELEPORT) {
            target = marks.get(player.getUniqueId());
            if (target == null) {
                messages.send(player, "editor.pad.need-mark");
                return;
            }
        }
        Block block = padBlock(player);
        Pad pad = new Pad(0, type.get(), block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), power, height,
                fixed ? player.getLocation().getYaw() : null, target);
        String author = player.getName();
        Tasks.async(plugin, player, () -> {
            store.save(pad);
            settings.log("pad", null, pad.type() + " " + pad.x() + "," + pad.y() + "," + pad.z(), author);
            return true;
        }, done -> {
            messages.send(player, "editor.pad.added", "type", pad.type().name().toLowerCase(Locale.ROOT),
                    "x", String.valueOf(pad.x()), "y", String.valueOf(pad.y()), "z", String.valueOf(pad.z()));
            changed();
        }, () -> messages.send(player, "error.generic"));
    }

    private static double defaultPower(Pad.Type type) {
        return switch (type) {
            case LAUNCH -> 2.5;
            case SPEED -> 3;
            default -> 1;
        };
    }

    @Override
    public List<String> complete(Player player, String[] args) {
        if (args.length == 1) {
            return SubCommand.filter(List.of("add", "mark", "remove", "list"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
            return SubCommand.filter(List.of("launch", "elevator", "speed", "teleport"), args[1]);
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("add")) {
            return SubCommand.filter(List.of("fixe"), args[4]);
        }
        return List.of();
    }
}
