package fr.eternom.eterHub.module.admin;

import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.HubStats;
import fr.eternom.eterHub.module.core.SubCommand;
import fr.eternom.eterHub.module.network.NetworkGui;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * /eterhub : l'administration des lobbys (eterhub.admin ; build : eterhub.build). Chaque partie a son éditeur :
 * spawn, zone, pad, holo, parkour, head, event, annonce, build ; plus reglage, maintenance, journal, stats, reload, info.
 */
public class AdminCommand implements TabExecutor {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.of("Europe/Paris"));

    private final JavaPlugin plugin;
    private final Messages messages;
    private final HubSettings settings;
    private final HubStats stats;
    private final NetworkGui network;
    private final Runnable reload;
    private final Supplier<List<String>> info;
    private final Map<String, SubCommand> parts = new LinkedHashMap<>();

    public AdminCommand(JavaPlugin plugin, Messages messages, HubSettings settings, HubStats stats, NetworkGui network,
                        Runnable reload, Supplier<List<String>> info) {
        this.plugin = plugin;
        this.messages = messages;
        this.settings = settings;
        this.stats = stats;
        this.network = network;
        this.reload = reload;
        this.info = info;
    }

    public void add(String name, SubCommand part) {
        parts.put(name, part);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        String[] rest = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        if (sub.equals("reload")) {
            if (!sender.hasPermission("eterhub.admin")) {
                messages.send(sender, "command.no-permission");
                return true;
            }
            reload.run();
            messages.send(sender, "admin.reloaded");
            return true;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
            return true;
        }
        if (!sub.equals("build") && !player.hasPermission("eterhub.admin")) {
            messages.send(player, "command.no-permission");
            return true;
        }
        switch (sub) {
            case "setspawn" -> parts.get("spawn").run(player, new String[]{"set"});
            case "reglage", "setting" -> setting(player, rest);
            case "maintenance" -> maintenance(player, rest);
            case "journal", "log" -> journal(player, rest);
            case "stats" -> stats(player, rest);
            case "info" -> info.get().forEach(line -> player.sendMessage(messages.render(line,
                    net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.empty())));
            default -> {
                SubCommand part = parts.get(sub);
                if (part == null) {
                    messages.send(player, "admin.usage", "parts", String.join(", ", parts.keySet()));
                } else {
                    part.run(player, rest);
                }
            }
        }
        return true;
    }

    private void setting(Player player, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            messages.send(player, "admin.setting.list");
            settings.list().forEach((path, value) -> messages.send(player, "admin.setting.line", "path", path, "value", value));
            return;
        }
        if (args[0].equalsIgnoreCase("reset") && args.length > 1) {
            if (!settings.editable(args[1])) {
                messages.send(player, "admin.setting.unknown", "path", args[1]);
                return;
            }
            settings.reset(args[1], player.getName());
            messages.send(player, "admin.setting.reset", "path", args[1], "value", settings.current(args[1]));
            return;
        }
        String path = args[0];
        if (!settings.editable(path)) {
            messages.send(player, "admin.setting.unknown", "path", path);
            return;
        }
        if (args.length == 1) {
            messages.send(player, "admin.setting.line", "path", path, "value", settings.current(path));
            return;
        }
        String value = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        settings.set(path, value, player.getName()).ifPresentOrElse(
                saved -> messages.send(player, "admin.setting.saved", "path", path, "value", saved),
                () -> messages.send(player, "admin.setting.bad-value", "path", path, "value", value));
    }

    private void maintenance(Player player, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            Map<String, String> servers = network.maintenance();
            messages.send(player, servers.isEmpty() ? "admin.maintenance.none" : "admin.maintenance.list",
                    "servers", String.join(", ", servers.keySet()));
            return;
        }
        boolean on = args.length < 2 || List.of("on", "oui", "true").contains(args[1].toLowerCase(Locale.ROOT));
        network.setMaintenance(args[0], on, player.getName());
        messages.send(player, on ? "admin.maintenance.on" : "admin.maintenance.off", "server", args[0]);
    }

    private void journal(Player player, String[] args) {
        int limit = 15;
        try {
            limit = args.length > 0 ? Math.max(1, Math.min(50, Integer.parseInt(args[0]))) : 15;
        } catch (NumberFormatException ignored) {
            // 15 par défaut
        }
        int count = limit;
        Tasks.async(plugin, player, () -> settings.history(count), lines -> {
            messages.send(player, "admin.journal.title", "count", String.valueOf(lines.size()));
            for (String[] line : lines) {
                messages.send(player, "admin.journal.line", "date", DATE.format(Instant.ofEpochMilli(Long.parseLong(line[4]))),
                        "author", line[3], "path", line[0], "old", line[1], "new", line[2]);
            }
        }, () -> messages.send(player, "error.generic"));
    }

    private void stats(Player player, String[] args) {
        int days;
        try {
            days = args.length > 0 ? Math.max(1, Math.min(90, Integer.parseInt(args[0]))) : 7;
        } catch (NumberFormatException e) {
            days = 7;
        }
        String prefix = args.length > 1 ? args[1] : "";
        int window = days;
        Tasks.async(plugin, player, () -> {
            stats.flush();
            return stats.totals(window, prefix);
        }, totals -> {
            messages.send(player, "admin.stats.title", "days", String.valueOf(window));
            totals.forEach((name, total) -> messages.send(player, "admin.stats.line", "name", name, "total", String.valueOf(total)));
        }, () -> messages.send(player, "error.generic"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> options = new ArrayList<>(parts.keySet());
            options.addAll(List.of("reglage", "maintenance", "journal", "stats", "reload", "info", "setspawn"));
            return SubCommand.filter(options, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        if (sub.equals("reglage") && args.length == 2) {
            List<String> options = new ArrayList<>(settings.list().keySet());
            options.addAll(List.of("list", "reset"));
            return SubCommand.filter(options, args[1]);
        }
        if (sub.equals("reglage") && args.length == 3 && args[1].equalsIgnoreCase("reset")) {
            return SubCommand.filter(new ArrayList<>(settings.list().keySet()), args[2]);
        }
        if (sub.equals("maintenance") && args.length == 3) {
            return SubCommand.filter(List.of("on", "off"), args[2]);
        }
        SubCommand part = parts.get(sub);
        return part == null ? List.of() : part.complete(player, rest);
    }
}
