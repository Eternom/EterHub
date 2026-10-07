package fr.eternom.eterHub.module.movement;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Double saut et plaques de lancement (config.yml > double-jump, launch-pads).
 *
 * Double saut : en survie ou aventure, le vol est « autorisé » pour capter la double pression sur espace ; au lieu de
 * voler, le joueur est propulsé, et le saut revient en touchant le sol. Ceux qui ont eterhub.fly (staff, /fly) ne
 * sont jamais touchés : ils volent normalement. Le mode de jeu n'est jamais changé (EterSync peut le synchroniser).
 * Plaque de lancement : marcher sur la plaque configurée projette le joueur vers l'avant (une fois par seconde).
 */
public class MovementListener implements Listener {

    public static final String FLY = "eterhub.fly";
    private static final long PAD_COOLDOWN_MILLIS = 1000;

    private final JavaPlugin plugin;
    private final boolean doubleJump;
    private final double jumpPower;
    private final double jumpHeight;
    private final boolean launchPads;
    private final Material padPlate;
    private final double padPower;
    private final double padHeight;
    private final Map<UUID, Long> lastPad = new HashMap<>();

    public MovementListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.doubleJump = plugin.getConfig().getBoolean("double-jump.enabled", true);
        this.jumpPower = plugin.getConfig().getDouble("double-jump.power", 1.2);
        this.jumpHeight = plugin.getConfig().getDouble("double-jump.height", 0.9);
        this.launchPads = plugin.getConfig().getBoolean("launch-pads.enabled", true);
        Material plate = Material.matchMaterial(plugin.getConfig().getString("launch-pads.plate", "LIGHT_WEIGHTED_PRESSURE_PLATE"));
        if (plate == null) {
            plugin.getLogger().warning("launch-pads.plate : bloc inconnu, LIGHT_WEIGHTED_PRESSURE_PLATE utilisé");
            plate = Material.LIGHT_WEIGHTED_PRESSURE_PLATE;
        }
        this.padPlate = plate;
        this.padPower = plugin.getConfig().getDouble("launch-pads.power", 2.5);
        this.padHeight = plugin.getConfig().getDouble("launch-pads.height", 1.0);
    }

    // ---------- Double saut ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        allowJump(event.getPlayer());
    }

    /** Le mode de jeu peut arriver après la connexion (EterSync) : on regarde au tick suivant. */
    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> allowJump(player));
    }

    @EventHandler(ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!event.isFlying() || !jumps(player)) {
            return;
        }
        event.setCancelled(true);
        player.setAllowFlight(false); // revient en touchant le sol
        player.setVelocity(player.getLocation().getDirection().multiply(jumpPower).setY(jumpHeight));
        player.playSound(player, Sound.ENTITY_BAT_TAKEOFF, 0.5f, 1.4f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!player.getAllowFlight() && jumps(player) && event.getTo().clone().subtract(0, 0.1, 0).getBlock().getType().isSolid()) {
            player.setAllowFlight(true);
        }
    }

    // ---------- Plaques de lancement ----------

    @EventHandler
    public void onPlate(PlayerInteractEvent event) {
        if (launchPads && event.getAction() == Action.PHYSICAL && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == padPlate) {
            launch(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastPad.remove(event.getPlayer().getUniqueId());
    }

    private void launch(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastPad.get(player.getUniqueId());
        if (last != null && now - last < PAD_COOLDOWN_MILLIS) {
            return;
        }
        lastPad.put(player.getUniqueId(), now);
        Vector direction = player.getLocation().getDirection().setY(0).normalize();
        player.setVelocity(direction.multiply(padPower).setY(padHeight));
        player.playSound(player, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.7f, 1.2f);
    }

    private void allowJump(Player player) {
        if (jumps(player)) {
            player.setAllowFlight(true);
        }
    }

    /** Double saut pour ce joueur : activé, survie ou aventure, et pas du staff qui vole. */
    private boolean jumps(Player player) {
        GameMode mode = player.getGameMode();
        return doubleJump && (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) && !player.hasPermission(FLY);
    }
}
