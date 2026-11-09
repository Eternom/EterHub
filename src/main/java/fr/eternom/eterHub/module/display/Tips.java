package fr.eternom.eterHub.module.display;

import fr.eternom.eterHub.module.core.HubSettings;
import fr.eternom.eterHub.module.zone.ZoneService;
import fr.eternom.eterLib.helper.message.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Astuces dans la barre d'action, de temps en temps (tips.interval-seconds), selon le lieu : lang/ tips.<zone>
 * (une ligne par astuce), sinon tips.default. Pas pendant une activité qui utilise déjà la barre (parcours, pêche).
 */
public class Tips {

    private final JavaPlugin plugin;
    private final ZoneService zones;
    private final HubSettings settings;
    private final Messages messages;
    private Predicate<Player> busy = player -> false;
    private long last;

    public Tips(JavaPlugin plugin, ZoneService zones, HubSettings settings, Messages messages) {
        this.plugin = plugin;
        this.zones = zones;
        this.settings = settings;
        this.messages = messages;
    }

    public void setBusy(Predicate<Player> busy) {
        this.busy = busy;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200, 100);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (!settings.bool("tips.enabled", true) || now - last < settings.number("tips.interval-seconds", 45) * 1000) {
            return;
        }
        last = now;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (busy.test(player)) {
                continue;
            }
            String zone = zones.idOf(player);
            String raw = zone.isEmpty() ? null : messages.raw(player, "tips." + zone);
            if (raw == null) {
                raw = messages.raw(player, "tips.default");
            }
            if (raw == null || raw.isBlank()) {
                continue;
            }
            List<String> lines = List.of(raw.split("\n"));
            String tip = lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
            player.sendActionBar(messages.render(tip, TagResolver.empty()));
        }
    }
}
