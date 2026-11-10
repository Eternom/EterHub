package fr.eternom.eterHub.module.activity;

import com.google.gson.JsonObject;
import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.core.SubCommand;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Évènements et annonces du village, sur TOUS les lobbys :
 * - /eterhub event start <minutes> <nom> : une bossbar avec le compte à rebours, pour tout le monde, qui invite à la
 *   Zone d'évènement ; /eterhub event stop. Gardé dans Redis : un lobby créé pendant l'évènement l'affiche aussi ;
 * - /eterhub announce <texte> : la Cathédrale annonce (titre, son de cloche, message), sur tous les lobbys.
 */
public class HubEvents implements Listener, SubCommand {

    private static final String KEY = "eterhub:event";
    private static final String CHANGED = "event";
    private static final String ANNOUNCE = "announce";

    private final JavaPlugin plugin;
    private final HubSettings settings;
    private final Messages messages;
    private final NetworkBus bus;
    private final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);
    private volatile String name;
    private volatile long started;
    private volatile long endsAt;

    public HubEvents(JavaPlugin plugin, HubSettings settings, Messages messages, NetworkBus bus) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.bus = bus;
        bus.on(CHANGED, data -> load());
        bus.on(ANNOUNCE, data -> announceHere(data.get("text").getAsString()));
    }

    public void start() {
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
    }

    public Optional<String> current() {
        return Optional.ofNullable(name);
    }

    private void load() {
        Tasks.async(plugin, () -> {
            Optional<String> raw = EterLib.get().getRedis().get(KEY);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (raw.isEmpty()) {
                    end(false);
                    return;
                }
                String[] parts = raw.get().split("\\|", 3);
                try {
                    started = Long.parseLong(parts[0]);
                    endsAt = Long.parseLong(parts[1]);
                    name = parts[2];
                } catch (RuntimeException e) {
                    end(false);
                    return;
                }
                Bukkit.getOnlinePlayers().forEach(player -> player.showBossBar(bar));
            });
        }, "Évènement non relu");
    }

    private void tick() {
        if (name == null) {
            return;
        }
        long left = endsAt - System.currentTimeMillis();
        if (left <= 0) {
            end(true);
            return;
        }
        long total = Math.max(1, endsAt - started);
        bar.progress(Math.max(0f, Math.min(1f, (float) left / total)));
        long seconds = left / 1000;
        String time = String.format("%d:%02d", seconds / 60, seconds % 60);
        String format = messages.raw(Bukkit.getConsoleSender(), "event.bar");
        bar.name(messages.render(format == null ? "<name> · <time>" : format,
                net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.empty(), "name", name, "time", time));
    }

    private void end(boolean announce) {
        if (name != null && announce) {
            String ended = name;
            Bukkit.getOnlinePlayers().forEach(player -> messages.send(player, "event.ended", "name", ended));
        }
        name = null;
        Bukkit.getOnlinePlayers().forEach(player -> player.hideBossBar(bar));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (name != null) {
            event.getPlayer().showBossBar(bar);
        }
    }

    /** Annonce de la Cathédrale, ici. */
    private void announceHere(String text) {
        Component body = messages.render(text, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.empty());
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(Title.title(messages.get(player, "announce.title"), body,
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1))));
            player.playSound(player, Sound.BLOCK_BELL_USE, 0.8f, 0.8f);
            messages.send(player, "announce.chat", "text", text);
        }
    }

    // ---------- /eterhub event | annonce ----------

    @Override
    public void run(Player player, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "start" -> {
                if (args.length < 3) {
                    messages.send(player, "event.usage");
                    return;
                }
                long minutes;
                try {
                    minutes = Math.max(1, Long.parseLong(args[1]));
                } catch (NumberFormatException e) {
                    messages.send(player, "event.usage");
                    return;
                }
                String eventName = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).replace("|", "/");
                long now = System.currentTimeMillis();
                long end = now + minutes * 60_000;
                String author = player.getName();
                Tasks.async(plugin, () -> {
                    EterLib.get().getRedis().set(KEY, now + "|" + end + "|" + eventName, Duration.ofMinutes(minutes + 1));
                    settings.log("event", null, eventName + " (" + minutes + " min)", author);
                    bus.publish(CHANGED, new JsonObject());
                    Bukkit.getScheduler().runTask(plugin, this::load);
                }, "Évènement non lancé");
                messages.send(player, "event.started", "name", eventName, "minutes", String.valueOf(minutes));
            }
            case "stop" -> {
                String author = player.getName();
                Tasks.async(plugin, () -> {
                    EterLib.get().getRedis().delete(KEY);
                    settings.log("event", "en cours", null, author);
                    bus.publish(CHANGED, new JsonObject());
                    Bukkit.getScheduler().runTask(plugin, () -> end(true));
                }, "Évènement non arrêté");
            }
            default -> messages.send(player, "event.usage");
        }
    }

    @Override
    public List<String> complete(Player player, String[] args) {
        return args.length == 1 ? SubCommand.filter(List.of("start", "stop"), args[0]) : List.of();
    }

    /** /eterhub announce <texte> : sur tous les lobbys. */
    public SubCommand announcer() {
        return new SubCommand() {
            @Override
            public void run(Player player, String[] args) {
                if (args.length == 0) {
                    messages.send(player, "announce.usage");
                    return;
                }
                String text = String.join(" ", args);
                announceHere(text);
                JsonObject data = new JsonObject();
                data.addProperty("text", text);
                String author = player.getName();
                Tasks.async(plugin, () -> {
                    bus.publish(ANNOUNCE, data);
                    settings.log("announce", null, text, author);
                }, "Annonce non envoyée");
            }

            @Override
            public List<String> complete(Player player, String[] args) {
                return List.of();
            }
        };
    }
}
