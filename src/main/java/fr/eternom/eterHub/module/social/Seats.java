package fr.eternom.eterHub.module.social;

import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.zone.ZoneRule;
import fr.eternom.eterHub.module.zone.ZoneService;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.entity.EntityDismountEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S'asseoir sur les bancs, escaliers et dalles du village (clic droit main vide, sans s'accroupir, à 3 blocs au plus) :
 * le joueur monte sur un support invisible, qui disparaît quand il se relève (Maj). Coupé là où la zone l'interdit
 * (règle sit) ou avec social.sit: false. Une place à la fois.
 */
public class Seats implements Listener {

    private final JavaPlugin plugin;
    private final ZoneService zones;
    private final HubSettings settings;
    private final Map<UUID, ArmorStand> seated = new ConcurrentHashMap<>();
    private final Map<Location, UUID> taken = new ConcurrentHashMap<>();

    public Seats(JavaPlugin plugin, ZoneService zones, HubSettings settings) {
        this.plugin = plugin;
        this.zones = zones;
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        Player player = event.getPlayer();
        if (block == null || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getItem() != null || player.isSneaking() || player.isInsideVehicle()
                || !settings.bool("social.sit", true) || !zones.allows(block.getLocation(), ZoneRule.SIT)) {
            return;
        }
        double height;
        if (Tag.STAIRS.isTagged(block.getType()) && block.getBlockData() instanceof Stairs stairs && stairs.getHalf() == Bisected.Half.BOTTOM) {
            height = 0.5;
        } else if (Tag.SLABS.isTagged(block.getType()) && block.getBlockData() instanceof Slab slab && slab.getType() == Slab.Type.BOTTOM) {
            height = 0.5;
        } else {
            return;
        }
        if (!block.getRelative(0, 1, 0).isPassable() || player.getLocation().distanceSquared(block.getLocation().add(0.5, 0.5, 0.5)) > 9) {
            return;
        }
        Location key = block.getLocation();
        if (taken.containsKey(key)) {
            return;
        }
        event.setCancelled(true);
        Location seat = block.getLocation().add(0.5, height - 0.2, 0.5);
        if (block.getBlockData() instanceof Stairs stairs) {
            seat.setDirection(stairs.getFacing().getOppositeFace().getDirection());
        } else {
            seat.setYaw(player.getLocation().getYaw());
        }
        ArmorStand stand = block.getWorld().spawn(seat, ArmorStand.class, created -> {
            created.setInvisible(true);
            created.setMarker(true);
            created.setGravity(false);
            created.setSmall(true);
            created.setInvulnerable(true);
            created.setPersistent(false);
            created.setSilent(true);
        });
        stand.addPassenger(player);
        seated.put(player.getUniqueId(), stand);
        taken.put(key, player.getUniqueId());
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player) {
            stand(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stand(event.getPlayer());
    }

    private void stand(Player player) {
        ArmorStand stand = seated.remove(player.getUniqueId());
        if (stand == null) {
            return;
        }
        taken.values().remove(player.getUniqueId());
        Location up = stand.getLocation().add(0, 0.7, 0);
        up.setDirection(player.getLocation().getDirection());
        stand.remove();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.teleportAsync(up);
            }
        });
    }

    /** Arrêt : tout le monde se relève. */
    public void standAll() {
        seated.values().forEach(ArmorStand::remove);
        seated.clear();
        taken.clear();
    }
}
