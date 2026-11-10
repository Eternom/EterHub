package fr.eternom.eterHub.module.protect;

import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.zone.ZoneRule;
import fr.eternom.eterHub.module.zone.ZoneService;
import io.papermc.paper.event.player.PlayerOpenSignEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Le village est un endroit sûr et figé :
 * - pas de dégâts ni de faim ; aucune entité blessée (PNJ, animaux, cadres), sauf par le staff en mode construction ;
 * - rien à casser, poser, jeter, ramasser, remplir, ouvrir (coffres), éditer (panneaux), tourner (cadres) ;
 * - objets décoratifs protégés (pots, pupitres, bibliothèques sculptées, gâteaux, composteurs, feux de camp, jukebox...) ;
 *   lits et ancres sans effet ;
 * - projectiles (perles, boules de neige, œufs, flèches, tridents, potions jetées) seulement dans les zones qui les
 *   permettent ; potions et fruit de chorus interdits ; élytres et fusées interdits partout ; trident Impulsion bloqué ;
 *   la canne à pêche n'accroche pas les joueurs ; bateaux et chariots seulement là où la zone le permet ;
 * - le monde ne change pas : pas de feu, de feuilles qui tombent, de fonte ou de formation de glace et de neige,
 *   d'écoulement, de chute de sable, de pousse des cultures, d'explosion, de portail allumé.
 * EterSync peut tourner sur un lobby : l'inventaire n'est JAMAIS modifié ici (le mode de jeu : ArrivalListener).
 */
public class ProtectionListener implements Listener {

    /** Décor qu'un clic droit changerait. */
    private static final Set<Material> DECOR = EnumSet.of(Material.FLOWER_POT, Material.LECTERN, Material.CHISELED_BOOKSHELF,
            Material.DECORATED_POT, Material.CAKE, Material.COMPOSTER, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.JUKEBOX, Material.RESPAWN_ANCHOR, Material.NOTE_BLOCK, Material.REPEATER, Material.COMPARATOR,
            Material.DAYLIGHT_DETECTOR, Material.CAULDRON, Material.WATER_CAULDRON, Material.LAVA_CAULDRON,
            Material.POWDER_SNOW_CAULDRON, Material.BEEHIVE, Material.BEE_NEST, Material.SWEET_BERRY_BUSH,
            Material.CAVE_VINES, Material.CAVE_VINES_PLANT, Material.REDSTONE_WIRE, Material.DRAGON_EGG,
            Material.VAULT, Material.CRAFTER, Material.END_PORTAL_FRAME, Material.SUSPICIOUS_SAND, Material.SUSPICIOUS_GRAVEL);
    /** Objets qu'on ne peut pas utiliser (jetés ou bus). */
    private static final Set<Material> FORBIDDEN_ITEMS = EnumSet.of(Material.FIREWORK_ROCKET, Material.CHORUS_FRUIT,
            Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION, Material.ENDER_EYE, Material.FLINT_AND_STEEL,
            Material.FIRE_CHARGE, Material.BONE_MEAL, Material.ARMOR_STAND, Material.END_CRYSTAL, Material.WIND_CHARGE);

    private final JavaPlugin plugin;
    private final ZoneService zones;
    private final BuildMode build;
    private final HubSettings settings;

    public ProtectionListener(JavaPlugin plugin, ZoneService zones, BuildMode build, HubSettings settings) {
        this.plugin = plugin;
        this.zones = zones;
        this.build = build;
        this.settings = settings;
        applyWorldRules();
    }

    /** world.time (-1 = cycle normal), world.clear-weather, world.natural-mobs, pour tous les mondes de ce lobby. */
    public void applyWorldRules() {
        long time = (long) settings.number("world.time", 6000);
        boolean clearWeather = settings.bool("world.clear-weather", true);
        boolean naturalMobs = settings.bool("world.natural-mobs", false);
        for (World world : Bukkit.getWorlds()) {
            world.setGameRule(GameRules.SPAWN_MOBS, naturalMobs);
            // Heure et météo : seulement le monde normal (le Nether et l'End n'ont pas d'horloge en 26.x)
            if (world.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }
            if (time >= 0) {
                world.setGameRule(GameRules.ADVANCE_TIME, false);
                world.setTime(time);
            } else {
                world.setGameRule(GameRules.ADVANCE_TIME, true);
            }
            if (clearWeather) {
                world.setGameRule(GameRules.ADVANCE_WEATHER, false);
                world.setStorm(false);
                world.setThundering(false);
            }
        }
    }

    private boolean builds(Player player) {
        return build.isBuilding(player);
    }

    private void deny(Player player, Consumer<Boolean> cancel) {
        if (!builds(player)) {
            cancel.accept(true);
        }
    }

    // ---------- Joueurs et entités ----------

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player player && builds(player)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getFoodLevel() < event.getEntity().getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.isGliding() && event.getEntity() instanceof Player player && !builds(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectile(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player) || builds(player)) {
            return;
        }
        if (projectile instanceof FishHook) {
            if (!zones.allows(player, ZoneRule.FISHING)) {
                event.setCancelled(true);
            }
            return;
        }
        if (projectile instanceof ThrownPotion || !zones.allows(player, ZoneRule.PROJECTILES)) {
            event.setCancelled(true);
        }
    }

    /** La canne à pêche n'accroche ni les joueurs ni les PNJ. */
    @EventHandler(ignoreCancelled = true)
    public void onHook(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY && !builds(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Material type = event.getItem().getType();
        if ((type == Material.CHORUS_FRUIT || type == Material.POTION || type == Material.MILK_BUCKET || type == Material.HONEY_BOTTLE)
                && !builds(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onRiptide(PlayerRiptideEvent event) {
        // Non annulable : le joueur est arrêté net au tick suivant
        Player player = event.getPlayer();
        if (!builds(player)) {
            Bukkit.getScheduler().runTask(plugin, () -> player.setVelocity(new org.bukkit.util.Vector(0, -0.5, 0)));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null || builds(player)) {
            return;
        }
        Entity entity = event.getEntity();
        boolean vehicle = entity instanceof Boat || entity instanceof Minecart;
        if (!vehicle || !zones.allows(event.getBlock().getLocation(), ZoneRule.BOATS)) {
            event.setCancelled(true);
        }
    }

    // ---------- Blocs ----------

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

    @EventHandler(ignoreCancelled = true)
    public void onSignOpen(PlayerOpenSignEvent event) {
        deny(event.getPlayer(), event::setCancelled);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        deny(event.getPlayer(), event::setCancelled);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBed(PlayerBedEnterEvent event) {
        event.setCancelled(true);
    }

    /**
     * Clics sur les blocs : terre piétinée, conteneurs, décor, lits, ancres, panneaux cirés ; objets interdits en main
     * (fusées, potions, briquet, poudre d'os...), trident (pas d'Impulsion), seaux, œufs d'apparition.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (builds(player)) {
            return;
        }
        ItemStack item = event.getItem();
        if (item != null && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            Material type = item.getType();
            if (FORBIDDEN_ITEMS.contains(type) || type == Material.TRIDENT || type.name().endsWith("_SPAWN_EGG")
                    || type == Material.ELYTRA) {
                event.setCancelled(true);
                return;
            }
            // Projectiles lancés à la main : refusés hors des zones qui les permettent (le lancer est aussi bloqué plus loin)
            if ((type == Material.ENDER_PEARL || type == Material.SNOWBALL || type == Material.EGG || type == Material.BOW
                    || type == Material.CROSSBOW) && !zones.allows(player, ZoneRule.PROJECTILES)) {
                event.setCancelled(true);
                return;
            }
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Material type = block.getType();
        boolean trample = event.getAction() == Action.PHYSICAL && (type == Material.FARMLAND || type == Material.TURTLE_EGG);
        boolean click = event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.LEFT_CLICK_BLOCK;
        boolean protectedBlock = click && (block.getState(false) instanceof Container || DECOR.contains(type)
                || type.name().startsWith("POTTED_") || Tag.BEDS.isTagged(type) || Tag.CANDLE_CAKES.isTagged(type)
                || Tag.ALL_SIGNS.isTagged(type) && event.getAction() == Action.RIGHT_CLICK_BLOCK && item != null);
        if (trample || protectedBlock) {
            event.setCancelled(true);
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
    public void onHangingBreak(HangingBreakEvent event) {
        if (event instanceof HangingBreakByEntityEvent byEntity && byEntity.getRemover() instanceof Player player && builds(player)) {
            return;
        }
        event.setCancelled(true);
    }

    // ---------- Monde figé ----------

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getPlayer() == null || !builds(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeaves(LeavesDecayEvent event) {
        event.setCancelled(true);
    }

    /** Fonte de la glace et de la neige, corail qui meurt, feu qui s'éteint... */
    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        event.setCancelled(true);
    }

    /** Glace, neige, obsidienne, béton qui se forment. */
    @EventHandler(ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        event.setCancelled(true);
    }

    /** Sable et gravier qui tombent, mobs qui changent un bloc (ravageurs, moutons...). */
    @EventHandler(ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Player player) || !builds(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PortalCreateEvent event) {
        event.setCancelled(true);
    }
}
