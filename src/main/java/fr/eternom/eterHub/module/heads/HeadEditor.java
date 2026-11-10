package fr.eternom.eterHub.module.heads;

import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.SubCommand;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Éditeur de la chasse aux têtes, /eterhub head (en visant une tête, à 6 blocs) :
 * <pre>
 *   add [chasse]               ajoute la tête visée (chasse principale par défaut)
 *   remove                     la retire de la chasse
 *   name <texte>               son nom (montré à la découverte et dans le menu) ; « name - » l'efface
 *   rare                       rare ou non (son, particules et récompense à part)
 *   hunt <chasse>              la change de chasse
 *   skin <pseudo|texture|url>  son skin : celui d'un joueur, une valeur « textures » (base64), une URL textures.minecraft.net
 *   edit                       son menu d'édition (aussi depuis list : clic sur une tête)
 *   give [texture|pseudo] [chasse]  une tête prête à poser (en mode construction) : ajoutée toute seule à la chasse
 *   list                       toutes les têtes, les moins trouvées d'abord (clic : éditer, clic droit : y aller)
 *   check [fix]                les têtes enregistrées dont le bloc a disparu (fix : les retirer)
 *   count                      combien par chasse
 *   reset <chasse|all> confirm  nouvelle saison : trouvailles et classements effacés, têtes gardées
 * </pre>
 */
public class HeadEditor implements SubCommand {

    private static final List<String> SUBS = List.of("add", "remove", "name", "rare", "hunt", "skin", "edit", "give", "list", "check", "count", "reset");

    private final HeadService service;
    private final HeadMenus menus;
    private final HubSettings settings;
    private final HeadEdits edits;
    private final Messages messages;

    public HeadEditor(HeadService service, HeadMenus menus, HubSettings settings, HeadEdits edits) {
        this.edits = edits;
        this.service = service;
        this.menus = menus;
        this.settings = settings;
        this.messages = service.messages();
    }

    @Override
    public void run(Player player, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "count";
        switch (sub) {
            case "count" -> service.hunts().keySet().forEach(hunt -> messages.send(player, "editor.head.count",
                    "hunt", service.huntName(player, hunt), "total", String.valueOf(service.total(hunt)),
                    "state", messages.plain(player, service.isOpen(hunt) ? "editor.head.open" : "editor.head.closed")));
            case "list" -> menus.openStaffList(player);
            case "check" -> check(player, args.length > 1 && args[1].equalsIgnoreCase("fix"));
            case "reset" -> reset(player, args);
            case "give" -> give(player, args);
            default -> targeted(player, sub, args);
        }
    }

    /** Les commandes qui visent une tête. */
    private void targeted(Player player, String sub, String[] args) {
        Block block = player.getTargetBlockExact(6);
        if (block == null || !HeadService.isHead(block.getType())) {
            messages.send(player, "editor.head.look");
            return;
        }
        if (sub.equals("add")) {
            String hunt = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : Hunt.MAIN;
            if (!service.hunts().containsKey(hunt)) {
                messages.send(player, "editor.head.unknown-hunt", "hunts", String.join(", ", service.hunts().keySet()));
                return;
            }
            service.add(player, block, hunt, () -> { });
            return;
        }
        HeadStore.Head head = service.at(block);
        if (head == null) {
            messages.send(player, "editor.head.unknown");
            return;
        }
        Runnable none = () -> { };
        switch (sub) {
            case "remove" -> edits.remove(player, head, none);
            case "name" -> {
                if (args.length < 2) {
                    messages.send(player, "editor.head.usage");
                    return;
                }
                edits.rename(player, head, String.join(" ", Arrays.copyOfRange(args, 1, args.length)), none);
            }
            case "rare" -> edits.toggleRare(player, head, none);
            case "hunt" -> edits.move(player, head, args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "", none);
            case "skin" -> edits.skin(player, head, args.length > 1 ? String.join("", Arrays.copyOfRange(args, 1, args.length)) : "", none);
            case "edit" -> menus.openEdit(player, head, 0);
            default -> messages.send(player, "editor.head.usage");
        }
    }

    /** Nouvelle saison : /eterhub head reset <chasse|all> confirm (trouvailles et classements effacés, têtes gardées). */
    private void reset(Player player, String[] args) {
        String target = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        boolean all = target.equals("all");
        if (!all && !service.hunts().containsKey(target)) {
            messages.send(player, "editor.head.reset-usage", "hunts", String.join(", ", service.hunts().keySet()));
            return;
        }
        String label = all ? messages.plain(player, "editor.head.all-hunts") : service.huntName(player, target);
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            messages.send(player, "editor.head.reset-warning", "hunt", label, "command", "/eterhub head reset " + target + " confirm");
            return;
        }
        service.reset(player, all ? null : target);
    }

    /** Une tête prête à poser : texture de heads.textures, sinon le skin d'un joueur (sinon le sien). */
    private void give(Player player, String[] args) {
        String source = args.length > 1 ? args[1] : player.getName();
        String hunt = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : Hunt.MAIN;
        if (!service.hunts().containsKey(hunt)) {
            messages.send(player, "editor.head.unknown-hunt", "hunts", String.join(", ", service.hunts().keySet()));
            return;
        }
        ConfigurationSection textures = service.plugin().getConfig().getConfigurationSection("heads.textures");
        String texture = textures != null ? textures.getString(source) : null;
        player.getInventory().addItem(service.headItem(texture, texture == null ? source : null, hunt))
                .values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        messages.send(player, "editor.head.given", "hunt", service.huntName(player, hunt));
    }

    /** Les têtes dont le bloc n'est plus une tête (seulement là où le monde est chargé). */
    private void check(Player player, boolean fix) {
        List<HeadStore.Head> broken = new ArrayList<>();
        for (HeadStore.Head head : service.heads()) {
            World world = Bukkit.getWorld(head.world());
            if (world == null) {
                continue;
            }
            Hunt hunt = service.hunts().get(head.hunt());
            boolean hidden = hunt != null && hunt.hideWhenClosed() && !service.isOpen(head.hunt());
            if (!hidden && !HeadService.isHead(world.getBlockAt(head.x(), head.y(), head.z()).getType())) {
                broken.add(head);
            }
        }
        if (broken.isEmpty()) {
            messages.send(player, "editor.head.check-ok", "total", String.valueOf(service.heads().size()));
            return;
        }
        messages.send(player, "editor.head.check-broken", "count", String.valueOf(broken.size()));
        broken.forEach(head -> messages.send(player, "editor.head.check-line", "id", String.valueOf(head.id()),
                "x", String.valueOf(head.x()), "y", String.valueOf(head.y()), "z", String.valueOf(head.z()),
                "hunt", service.huntName(player, head.hunt())));
        if (!fix) {
            messages.send(player, "editor.head.check-fix");
            return;
        }
        String author = player.getName();
        Tasks.async(service.plugin(), player, () -> {
            broken.forEach(head -> {
                service.store().remove(head.id());
                settings.log("head", head.hunt() + " " + head.x() + "," + head.y() + "," + head.z() + " (disparue)", null, author);
            });
            return broken.size();
        }, removed -> {
            messages.send(player, "editor.head.check-fixed", "count", String.valueOf(removed));
            service.changed();
        }, () -> messages.send(player, "error.generic"));
    }

    @Override
    public List<String> complete(Player player, String[] args) {
        if (args.length == 1) {
            return SubCommand.filter(SUBS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> hunts = new ArrayList<>(service.hunts().keySet());
        if (args.length == 2 && (sub.equals("add") || sub.equals("hunt"))) {
            return SubCommand.filter(hunts, args[1]);
        }
        if (args.length == 2 && sub.equals("reset")) {
            List<String> options = new ArrayList<>(hunts);
            options.add("all");
            return SubCommand.filter(options, args[1]);
        }
        if (args.length == 3 && sub.equals("reset")) {
            return SubCommand.filter(List.of("confirm"), args[2]);
        }
        if (args.length == 2 && sub.equals("check")) {
            return SubCommand.filter(List.of("fix"), args[1]);
        }
        if (args.length == 2 && sub.equals("skin")) {
            ConfigurationSection textures = service.plugin().getConfig().getConfigurationSection("heads.textures");
            List<String> options = textures == null ? new ArrayList<>() : new ArrayList<>(textures.getKeys(false));
            Bukkit.getOnlinePlayers().forEach(online -> options.add(online.getName()));
            return SubCommand.filter(options, args[1]);
        }
        if (args.length == 2 && sub.equals("give")) {
            ConfigurationSection textures = service.plugin().getConfig().getConfigurationSection("heads.textures");
            List<String> options = textures == null ? new ArrayList<>() : new ArrayList<>(textures.getKeys(false));
            options.add(player.getName());
            return SubCommand.filter(options, args[1]);
        }
        if (args.length == 3 && sub.equals("give")) {
            return SubCommand.filter(hunts, args[2]);
        }
        return List.of();
    }
}
