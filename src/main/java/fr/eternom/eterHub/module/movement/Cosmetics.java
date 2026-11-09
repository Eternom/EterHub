package fr.eternom.eterHub.module.movement;

import fr.eternom.eterHub.module.core.HubSettings;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Cosmétiques selon le grade (permissions, à donner aux groupes LuckPerms) :
 * - traînée du double saut : cosmetics.trails.<id> = particule, permission eterhub.trail.<id> (la dernière de la liste
 *   que le joueur a l'emporte : du plus simple au plus rare) ;
 * - particules autour du joueur : cosmetics.auras.<id>, permission eterhub.aura.<id>, une fois par seconde, vues par
 *   tous (sauf ceux qui ne voient pas ce joueur).
 */
public class Cosmetics {

    private final JavaPlugin plugin;
    private final HubSettings settings;
    private Predicate<Player> hidden = player -> false;

    public Cosmetics(JavaPlugin plugin, HubSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    /** Un joueur dont il ne faut pas montrer les particules (vanish, en parcours...). */
    public void setHidden(Predicate<Player> hidden) {
        this.hidden = hidden;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::auras, 60, 20);
    }

    /** La traînée de ce joueur (null : celle par défaut, des nuages). */
    public Particle trail(Player player) {
        return best(player, "cosmetics.trails", "eterhub.trail.");
    }

    private Particle best(Player player, String path, String permission) {
        Particle found = null;
        for (Map.Entry<String, Particle> entry : read(path).entrySet()) {
            if (player.hasPermission(permission + entry.getKey())) {
                found = entry.getValue();
            }
        }
        return found;
    }

    private void auras() {
        if (!settings.bool("cosmetics.enabled", true)) {
            return;
        }
        Map<String, Particle> auras = read("cosmetics.auras");
        if (auras.isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (hidden.test(player)) {
                continue;
            }
            Particle aura = null;
            for (Map.Entry<String, Particle> entry : auras.entrySet()) {
                if (player.hasPermission("eterhub.aura." + entry.getKey())) {
                    aura = entry.getValue();
                }
            }
            if (aura != null) {
                player.getWorld().spawnParticle(aura, player.getLocation().add(0, 1, 0), 3, 0.4, 0.6, 0.4, 0.01);
            }
        }
    }

    private Map<String, Particle> read(String path) {
        Map<String, Particle> particles = new LinkedHashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);
        if (section == null) {
            return particles;
        }
        for (String id : section.getKeys(false)) {
            try {
                Particle particle = Particle.valueOf(section.getString(id, "").toUpperCase(Locale.ROOT));
                if (particle.getDataType() == Void.class) {
                    particles.put(id, particle);
                }
            } catch (IllegalArgumentException ignored) {
                // particule inconnue : ignorée
            }
        }
        return particles;
    }
}
