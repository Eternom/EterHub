package fr.eternom.eterHub.module.zone;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Un lieu du village, tel que décrit dans config.yml (zones.<id>) : icône, règles, ambiance (son d'entrée, son qui
 * revient, particules, heure et météo vues par le joueur), accès réservé et découverte. Ses limites (deux coins) et son
 * point de téléportation sont tracés en jeu et gardés en base (ZoneStore). Nom et sous-titre : lang/ zones.<id>.
 */
public record Zone(String id, Material icon, Map<ZoneRule, Boolean> rules, String enterSound, String ambientSound,
                   int ambientSeconds, Particle particle, int particleCount, double particleRadius, long time,
                   String weather, String permission, String condition, boolean discoverable, boolean title,
                   boolean halloween) {

    /** Hors de toute zone : les règles par défaut, sans ambiance. */
    public static Zone wilderness(Map<ZoneRule, Boolean> defaults) {
        return new Zone("", Material.GRASS_BLOCK, defaults, "", "", 0, null, 0, 0, -1, "", "", "", false, false, false);
    }

    public boolean allows(ZoneRule rule) {
        return rules.getOrDefault(rule, false);
    }

    public boolean restricted() {
        return !permission.isEmpty() || !condition.isEmpty();
    }

    /** Toutes les zones de config.yml, dans leur ordre (celui de la carte du village). */
    public static Map<String, Zone> load(ConfigurationSection config, Logger logger) {
        Map<ZoneRule, Boolean> defaults = rules(config.getConfigurationSection("zone-defaults.rules"), new EnumMap<>(Map.of(
                ZoneRule.DOUBLE_JUMP, true, ZoneRule.FISHING, false, ZoneRule.PROJECTILES, false, ZoneRule.DIG, false,
                ZoneRule.BOATS, false, ZoneRule.SIT, true)));
        Map<String, Zone> zones = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("zones");
        if (section == null) {
            return zones;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection zone = section.getConfigurationSection(id);
            if (zone == null || !id.matches("[a-z0-9_-]+")) {
                logger.warning("zones." + id + " : identifiant invalide (minuscules, chiffres, - et _), ignorée");
                continue;
            }
            Material icon = Material.matchMaterial(zone.getString("icon", "MAP"));
            Particle particle = null;
            String particleName = zone.getString("particles.type", "");
            if (!particleName.isEmpty()) {
                try {
                    particle = Particle.valueOf(particleName.toUpperCase(Locale.ROOT));
                    if (particle.getDataType() != Void.class) {
                        logger.warning("zones." + id + ".particles.type : " + particleName + " demande des données, ignorée");
                        particle = null;
                    }
                } catch (IllegalArgumentException e) {
                    logger.warning("zones." + id + ".particles.type : particule inconnue " + particleName);
                }
            }
            zones.put(id, new Zone(id, icon == null || !icon.isItem() ? Material.MAP : icon,
                    rules(zone.getConfigurationSection("rules"), defaults),
                    zone.getString("sound", ""), zone.getString("ambient.sound", ""), Math.max(0, zone.getInt("ambient.every", 0)),
                    particle, Math.max(0, zone.getInt("particles.count", 4)), Math.max(1, zone.getDouble("particles.radius", 8)),
                    zone.getLong("time", -1), zone.getString("weather", "").toLowerCase(Locale.ROOT),
                    zone.getString("access.permission", ""), zone.getString("access.condition", ""),
                    zone.getBoolean("discoverable", true), zone.getBoolean("title", true), zone.getBoolean("halloween", false)));
        }
        return zones;
    }

    public static Map<ZoneRule, Boolean> defaults(ConfigurationSection config) {
        return rules(config.getConfigurationSection("zone-defaults.rules"), new EnumMap<>(Map.of(
                ZoneRule.DOUBLE_JUMP, true, ZoneRule.FISHING, false, ZoneRule.PROJECTILES, false, ZoneRule.DIG, false,
                ZoneRule.BOATS, false, ZoneRule.SIT, true)));
    }

    private static Map<ZoneRule, Boolean> rules(ConfigurationSection section, Map<ZoneRule, Boolean> base) {
        Map<ZoneRule, Boolean> rules = new EnumMap<>(ZoneRule.class);
        rules.putAll(base);
        if (section != null) {
            for (ZoneRule rule : ZoneRule.values()) {
                if (section.contains(rule.key())) {
                    rules.put(rule, section.getBoolean(rule.key()));
                }
            }
        }
        return rules;
    }
}
