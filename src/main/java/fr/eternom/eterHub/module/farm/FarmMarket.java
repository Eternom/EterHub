package fr.eternom.eterHub.module.farm;

import fr.eternom.eterHub.module.activity.EconomyLink;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Les acheteurs des Champs (actions eterhub:farmer et eterhub:vineyard sur des PNJ) : chacun rachète les récoltes de
 * ses cultures (buyer de farming.crops), jamais les objets ordinaires. Prix = prix de la culture × qualité
 * (farming.quality.<qualité>.multiplier) × farming.daily-bonus si elle vient de la parcelle du jour. Clic : vendre une
 * pile ; « Tout vendre ». Retiré puis payé par EterEconomy, rendu si le paiement échoue ; fermé sans EterEconomy.
 */
public class FarmMarket {

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};
    private static final int DAILY = 4;
    private static final int BACK = 48; // le centre porte l'action principale ; 45 = page précédente ailleurs
    private static final int SELL_ALL = 49;

    private final Farming farming;
    private final Messages messages;
    private final BackButton back;

    public FarmMarket(Farming farming, BackButton back) {
        this.farming = farming;
        this.messages = farming.messages();
        this.back = back;
    }

    /** Prix d'une pile de récoltes pour cet acheteur (0 s'il ne la rachète pas). */
    public double value(ItemStack stack, String buyer) {
        Optional<Farming.Produce> produce = farming.read(stack);
        if (produce.isEmpty()) {
            return 0;
        }
        Crop crop = farming.crop(produce.get().crop()).orElse(null);
        if (crop == null || !crop.buyer().equals(buyer)) {
            return 0;
        }
        double multiplier = farming.settings().number("farming.quality." + produce.get().quality().key() + ".multiplier",
                switch (produce.get().quality()) {
                    case NORMAL -> 1;
                    case FINE -> 2;
                    case GOLDEN -> 8;
                });
        double bonus = produce.get().bonus() ? farming.settings().number("farming.daily-bonus", 2) : 1;
        return Math.round(crop.price() * multiplier * bonus * stack.getAmount() * 100) / 100.0;
    }

    public void open(Player player, String buyer) {
        if (!farming.settings().bool("activities.farming", true)) {
            messages.send(player, "farming.closed");
            return;
        }
        if (!EconomyLink.available()) {
            messages.send(player, "farming.market.no-economy");
            Sounds.refuse(player);
            return;
        }
        player.openInventory(new Stall(player, buyer).getInventory());
    }

    private void sell(Player player, String buyer, List<Integer> inventorySlots) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        List<ItemStack> taken = new ArrayList<>();
        double total = 0;
        int count = 0;
        for (int slot : inventorySlots) {
            if (slot < 0 || slot >= contents.length) {
                continue;
            }
            double value = value(contents[slot], buyer);
            if (value <= 0) {
                continue;
            }
            total += value;
            count += contents[slot].getAmount();
            taken.add(contents[slot].clone());
            player.getInventory().setItem(slot, null);
        }
        if (taken.isEmpty()) {
            messages.send(player, "farming.market.nothing");
            Sounds.refuse(player);
            return;
        }
        double amount = Math.round(total * 100) / 100.0;
        int sold = count;
        UUID uuid = player.getUniqueId();
        Tasks.async(farming.plugin(), player, () -> EconomyLink.deposit(uuid, amount, "EterHub · " + buyer), paid -> {
            if (paid) {
                messages.send(player, "farming.market.sold", "count", String.valueOf(sold), "amount", EconomyLink.format(amount));
                player.playSound(player, Sound.ENTITY_VILLAGER_YES, 0.7f, 1.1f);
                farming.stats().add("activity:harvest-sold");
            } else {
                giveBack(player, taken);
                messages.send(player, "farming.market.failed");
            }
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Stall) {
                open(player, buyer);
            }
        }, () -> {
            giveBack(player, taken);
            messages.send(player, "farming.market.failed");
        });
    }

    private static void giveBack(Player player, List<ItemStack> items) {
        if (!player.isOnline()) {
            return;
        }
        player.getInventory().addItem(items.toArray(ItemStack[]::new)).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    private final class Stall implements Menu {

        private final String buyer;
        private final Inventory inventory;
        private final Map<Integer, Integer> inventorySlotAt = new HashMap<>();
        private final List<Integer> allSlots = new ArrayList<>();

        Stall(Player viewer, String buyer) {
            this.buyer = buyer;
            inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "farming.market.title-" + buyer));
            Frame.draw(inventory, buyer.equals("vineyard") ? Material.PURPLE_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE);
            ItemStack[] contents = viewer.getInventory().getStorageContents();
            double total = 0;
            int shown = 0;
            for (int slot = 0; slot < contents.length; slot++) {
                double value = value(contents[slot], buyer);
                if (value <= 0) {
                    continue;
                }
                total += value;
                allSlots.add(slot);
                if (shown < SLOTS.length) {
                    ItemStack display = contents[slot].clone();
                    display.editMeta(meta -> {
                        List<Component> lore = new ArrayList<>(meta.hasLore() ? meta.lore() : List.of());
                        lore.add(Component.empty());
                        lore.add(messages.get(viewer, "farming.market.price", "amount", EconomyLink.format(value)));
                        lore.add(messages.get(viewer, "farming.market.click"));
                        meta.lore(lore);
                    });
                    inventory.setItem(SLOTS[shown], display);
                    inventorySlotAt.put(SLOTS[shown], slot);
                    shown++;
                }
            }
            if (allSlots.isEmpty()) {
                inventory.setItem(22, Items.item(buyer.equals("vineyard") ? Material.GLASS_BOTTLE : Material.COMPOSTER, messages.get(viewer, "farming.market.empty-" + buyer),
                        List.of(messages.get(viewer, "farming.market.empty-lore"))));
            }
            String daily = farming.parcelOfTheDay().orElse("");
            inventory.setItem(DAILY, Items.item(Material.HAY_BLOCK, messages.get(viewer, "farming.market.daily",
                    "parcel", daily.isEmpty() ? "-" : farming.zones().plainName(viewer, daily)),
                    List.of(messages.get(viewer, "farming.market.daily-lore", "bonus",
                            String.valueOf(farming.settings().number("farming.daily-bonus", 2)).replace(".0", ""))), true));
            inventory.setItem(BACK, back.item(viewer));
            inventory.setItem(SELL_ALL, Items.item(allSlots.isEmpty() ? Material.GRAY_DYE : Material.EMERALD,
                    messages.get(viewer, "farming.market.sell-all", "amount", EconomyLink.format(Math.round(total * 100) / 100.0)),
                    List.of(messages.get(viewer, "farming.market.sell-all-lore", "count", String.valueOf(allSlots.size())))));
        }

        @Override
        public void onClick(Player player, int slot, ClickType click) {
            if (slot == BACK) {
                back.click(player);
            } else if (slot == SELL_ALL) {
                sell(player, buyer, allSlots);
            } else if (inventorySlotAt.containsKey(slot)) {
                sell(player, buyer, List.of(inventorySlotAt.get(slot)));
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
