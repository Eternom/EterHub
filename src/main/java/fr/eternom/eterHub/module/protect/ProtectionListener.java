package fr.eternom.eterHub.module.protect;

import fr.eternom.eterHub.module.spawn.Spawn;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Consumer;

/**
 * Le lobby est un endroit sûr : pas de dégâts ni de faim, rien à casser, poser, jeter ou ramasser (sauf avec
 * eterhub.build), pas de coffres, cadres ou supports d'armure à vider. Le vide ramène au spawn. Heure, météo et
 * monstres naturels réglés par les règles de jeu au démarrage.
 * EterSync peut tourner sur un lobby : l'inventaire et le mode de jeu ne sont JAMAIS modifiés ici.
 * Les PNJ (Mannequins d'EterMarket) restent cliquables : seuls cadres et supports d'armure sont bloqués.
 */
public class ProtectionListener implements Listener {

    public static final String BUILD = "eterhub.build";

    private final JavaPlugin plugin;
    private final Spawn spawn;
    private final boolean spawnOnJoin;
    private final int voidY;

    public ProtectionListener(JavaPlugin plugin, Spawn spawn) {
        this.plugin = plugin;
        this.spawn = spawn;
        this.spawnOnJoin = plugin.getConfig().getBoolean("spawn-on-join", true);
        this.voidY = plugin.getConfig().getInt("world.void-y", -64);
        applyWorldRules();
    }

    /** world.time (-1 = cycle normal), world.clear-weather, world.natural-mobs, pour tous les mondes de ce lobby. */
    private void applyWorldRules() {
        long time = plugin.getConfig().getLong("world.time", 6000);
        boolean clearWeather = plugin.getConfig().getBoolean("world.clear-weather", true);
        boolean naturalMobs = plugin.getConfig().getBoolean("world.natural-mobs", false);
        for (World world : Bukkit.getWorlds()) {
            // Seul le monde normal a une horloge : dans le Nether et l'End, setTime est refusé (26.x)
            if (time >= 0 && world.getEnvironment() == World.Environment.NORMAL) {
                world.setGameRule(GameRules.ADVANCE_TIME, false);
                world.setTime(time);
            }
            if (clearWeather) {
                world.setGameRule(GameRules.ADVANCE_WEATHER, false);
                world.setStorm(false);
                world.setThundering(false);
            }
            world.setGameRule(GameRules.SPAWN_MOBS, naturalMobs);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (spawnOnJoin) {
            spawn.teleport(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Une seule fois, au moment de passer sous la limite (la téléportation prend un instant)
        if (event.getTo().getY() < voidY && event.getFrom().getY() >= voidY) {
            spawn.teleport(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            event.setCancelled(true);
        }
    }

    /** Les joueurs ne frappent rien (PNJ, cadres, animaux...), sauf les builders. */
    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && !player.hasPermission(BUILD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getFoodLevel() < event.getEntity().getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        deny(event.getPlayer(), event::setCancelled);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        deny(event.getPlayer(), event::setCancelled);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        deny(event.getPlayer(), event::setCancelled);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        deny(event.getPlayer(), event::setCancelled);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        deny(event.getPlayer(), event::setCancelled);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            deny(player, event::setCancelled);
        }
    }

    /** Pas de terre labourée piétinée, pas de coffres ni autres conteneurs ouverts. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        boolean trample = event.getAction() == Action.PHYSICAL && block.getType() == Material.FARMLAND;
        boolean container = event.getAction() == Action.RIGHT_CLICK_BLOCK && block.getState(false) instanceof Container;
        if (trample || container) {
            deny(event.getPlayer(), event::setCancelled);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Hanging || event.getRightClicked() instanceof ArmorStand) {
            deny(event.getPlayer(), event::setCancelled);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        deny(event.getPlayer(), event::setCancelled);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (!(event.getRemover() instanceof Player player) || !player.hasPermission(BUILD)) {
            event.setCancelled(true);
        }
    }

    private static void deny(Player player, Consumer<Boolean> cancel) {
        if (!player.hasPermission(BUILD)) {
            cancel.accept(true);
        }
    }
}
