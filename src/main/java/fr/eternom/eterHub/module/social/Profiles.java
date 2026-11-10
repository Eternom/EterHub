package fr.eternom.eterHub.module.social;

import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.zone.ZoneService;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.ranking.RankingSource;
import fr.eternom.eterLib.helper.task.Tasks;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Profil d'un joueur d'un clic droit sur lui (main vide, social.profile-on-click) : son temps de jeu, ses points de
 * succès, son clan, sa progression au village ; « Ajouter en ami » si EterFriend tourne. Lu en tâche de fond.
 */
public class Profiles implements Listener {

    private final JavaPlugin plugin;
    private final HubSettings settings;
    private final Messages messages;
    private final FriendsView friends;
    private final Function<Player, List<Component>> progress;
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();

    public Profiles(JavaPlugin plugin, HubSettings settings, Messages messages, FriendsView friends,
                    Function<Player, List<Component>> progress) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.friends = friends;
        this.progress = progress;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player target) || event.getHand() != EquipmentSlot.HAND
                || !settings.bool("social.profile-on-click", true) || Bukkit.getPlayer(target.getUniqueId()) == null) {
            return;
        }
        Player viewer = event.getPlayer();
        if (!viewer.getInventory().getItemInMainHand().getType().isAir()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = last.put(viewer.getUniqueId(), now);
        if (previous != null && now - previous < 1000) {
            return;
        }
        open(viewer, target);
    }

    public void open(Player viewer, Player target) {
        UUID uuid = target.getUniqueId();
        List<Component> village = progress.apply(target);
        Tasks.async(plugin, viewer, () -> new Object[]{
                RankingSource.find("playtime_total").map(source -> source.of(uuid)[1]).orElse(-1L),
                RankingSource.find("success").map(source -> source.of(uuid)[1]).orElse(-1L),
                friends.clanName(uuid),
                friends.friends(viewer.getUniqueId()).contains(uuid)
        }, data -> viewer.openInventory(new ProfileMenu(viewer, target, (long) data[0], (long) data[1], (String) data[2],
                (boolean) data[3], village).getInventory()), () -> messages.send(viewer, "error.generic"));
    }

    private final class ProfileMenu implements Menu {

        private static final int ADD_FRIEND = 15;
        private final Inventory inventory;
        private final String targetName;
        private final boolean canAdd;

        ProfileMenu(Player viewer, Player target, long playtime, long success, String clan, boolean friend, List<Component> village) {
            this.targetName = target.getName();
            this.canAdd = friends.friendsAvailable() && !friend && viewer != target;
            inventory = Bukkit.createInventory(this, 27, messages.get(viewer, "profile.title", "player", target.getName()));
            Frame.draw(inventory, Material.PURPLE_STAINED_GLASS_PANE);
            List<Component> lore = new ArrayList<>();
            if (playtime >= 0) {
                lore.add(messages.get(viewer, "profile.playtime", "hours", String.valueOf(playtime / 60)));
            }
            if (success >= 0) {
                lore.add(messages.get(viewer, "profile.success", "points", String.valueOf(success)));
            }
            lore.add(messages.get(viewer, clan.isEmpty() ? "profile.no-clan" : "profile.clan", "clan", clan));
            if (friend) {
                lore.add(messages.get(viewer, "profile.friend"));
            }
            inventory.setItem(11, Items.head(Bukkit.createProfile(target.getUniqueId(), target.getName()),
                    messages.get(viewer, "profile.name", "player", target.getName()), lore));
            inventory.setItem(13, Items.item(Material.FILLED_MAP, messages.get(viewer, "profile.village"), village));
            if (canAdd) {
                inventory.setItem(ADD_FRIEND, Items.item(Material.EMERALD, messages.get(viewer, "profile.add-friend"),
                        List.of(messages.get(viewer, "profile.add-friend-lore", "player", target.getName()))));
            }
        }

        @Override
        public void onClick(Player player, int slot, ClickType click) {
            if (slot == ADD_FRIEND && canAdd) {
                Sounds.click(player);
                player.closeInventory();
                player.performCommand("friend add " + targetName);
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
