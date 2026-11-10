package fr.eternom.eterHub.module.zone;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Un lieu du village, tel que décrit dans config.yml (zones.<id>) : icône, règles, ambiance, accès réservé et
 * découverte. Ses limites et son point d'arrivée sont tracés en jeu et gardés en base (ZoneStore). Nom et sous-titre :
 * lang/ zones.<id>.
 * Ambiance : des couches de particules (particles) et des sons (ambient), chacun avec sa fréquence (every, en secondes)
 * et sa probabilité (chance) : certains toujours là, d'autres rares ; vus et entendus par le joueur seulement.
 * Hors de tout lieu : celles de zone-defaults.
 */
public record Zone(String id, Material icon, Map<ZoneRule, Boolean> rules, String enterSound, List<Effect> particles,
                   List<Ambient> sounds, long time, String weather, String permission, String condition, boolean discoverable,
                   boolean title, boolean halloween, String parent, Scene scene) {

    /**
     * Une couche de particules : count particules dans un rayon autour du joueur, entre minY et maxY au-dessus de ses
     * pieds, toutes les every secondes, avec la probabilité chance (1 = toujours) ; speed : leur vitesse.
     */
    public record Effect(Particle type, int count, double radius, double minY, double maxY, int every, double chance, double speed) {
    }

    /** Un son d'ambiance : joué toutes les every secondes avec la probabilité chance, un peu autour du joueur. */
    public record Ambient(String sound, int every, double chance, float volume, float pitch, float pitchSpread) {
    }

    /**
     * La mise en scène d'un lieu : isolation (on n'y voit personne et personne ne nous voit, sauf le staff), no-teleport
     * (aucune téléportation pour en sortir), effects (effets par vagues, ex. l'obscurité, retirés en sortant), on-enter /
     * on-leave (actions d'EterLib en entrant et en sortant : messages, sons, titres, plus tard EterQuest).
     */
    public record Scene(boolean isolation, boolean noTeleport, List<Status> effects, List<String> onEnter, List<String> onLeave,
                        Passage passage) {
        public static final Scene NONE = new Scene(false, false, List.of(), List.of(), List.of(), null);
    }

    /**
     * Un passage : la mise en scène du lieu colle au joueur après en être sorti (un couloir qu'on ne peut pas tracer),
     * jusqu'à un lieu de until (l'arrivée) ; elle s'arrête aussi s'il va dans un lieu ni de keep ni de until, après
     * timeout secondes, ou à la déconnexion.
     */
    public record Passage(List<String> until, List<String> keep, int timeout) {
    }

    /** Un effet par vagues : toutes les every secondes, avec la probabilité chance, pendant seconds secondes. */
    public record Status(org.bukkit.potion.PotionEffectType type, int every, double chance, int seconds, int amplifier) {
    }

    /** Hors de tout lieu : les règles et l'ambiance par défaut. */
    public static Zone wilderness(ConfigurationSection config, Logger logger) {
        ConfigurationSection defaults = config.getConfigurationSection("zone-defaults");
        return new Zone("", Material.GRASS_BLOCK, defaults(config), "", particles(defaults, "zone-defaults", logger),
                sounds(defaults, "zone-defaults", logger), -1, "", "", "", false, false, false, "", Scene.NONE);
    }

    public boolean allows(ZoneRule rule) {
        return rules.getOrDefault(rule, false);
    }

    public boolean restricted() {
        return !permission.isEmpty() || !condition.isEmpty();
    }

    /** Toutes les zones de config.yml, dans leur ordre (celui de la carte du village). */
    public static Map<String, Zone> load(ConfigurationSection config, Logger logger) {
        Map<ZoneRule, Boolean> defaults = defaults(config);
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
            zones.put(id, new Zone(id, icon == null || !icon.isItem() ? Material.MAP : icon,
                    rules(zone.getConfigurationSection("rules"), defaults), zone.getString("sound", ""),
                    particles(zone, "zones." + id, logger), sounds(zone, "zones." + id, logger),
                    zone.getLong("time", -1), zone.getString("weather", "").toLowerCase(Locale.ROOT),
                    zone.getString("access.permission", ""), zone.getString("access.condition", ""),
                    zone.getBoolean("discoverable", true), zone.getBoolean("title", true), zone.getBoolean("halloween", false),
                    zone.getString("parent", ""), scene(zone, "zones." + id, logger)));
        }
        return inherit(zones, section, defaults, logger);
    }

    /**
     * Un lieu dans un autre (parent : une parcelle dans les Champs) reprend son ambiance (particules, sons, heure, météo)
     * s'il n'a pas la sienne, et ses règles comme base (les siennes par-dessus).
     */
    private static Map<String, Zone> inherit(Map<String, Zone> zones, ConfigurationSection section, Map<ZoneRule, Boolean> defaults,
                                             Logger logger) {
        Map<String, Zone> resolved = new LinkedHashMap<>();
        for (Zone zone : zones.values()) {
            Zone parent = zone.parent().isEmpty() ? null : zones.get(zone.parent());
            if (parent == null) {
                if (!zone.parent().isEmpty()) {
                    logger.warning("zones." + zone.id() + ".parent : lieu inconnu " + zone.parent());
                }
                resolved.put(zone.id(), zone);
                continue;
            }
            Map<ZoneRule, Boolean> rules = rules(section.getConfigurationSection(zone.id() + ".rules"), parent.rules());
            resolved.put(zone.id(), new Zone(zone.id(), zone.icon(), rules, zone.enterSound(),
                    zone.particles().isEmpty() ? parent.particles() : zone.particles(),
                    zone.sounds().isEmpty() ? parent.sounds() : zone.sounds(),
                    zone.time() >= 0 ? zone.time() : parent.time(), zone.weather().isEmpty() ? parent.weather() : zone.weather(),
                    zone.permission(), zone.condition(), zone.discoverable(), zone.title(), zone.halloween() || parent.halloween(),
                    zone.parent(), zone.scene()));
        }
        return resolved;
    }

    /** Le lieu « racine » : lui-même, ou son parent. */
    public String root() {
        return parent.isEmpty() ? id : parent;
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

    /** particles : une liste de couches, ou (ancien format) une seule couche { type, count, radius }. */
    private static List<Effect> particles(ConfigurationSection zone, String path, Logger logger) {
        List<Effect> effects = new ArrayList<>();
        if (zone == null) {
            return effects;
        }
        List<Map<?, ?>> entries = zone.getMapList("particles");
        if (entries.isEmpty() && zone.isConfigurationSection("particles")) {
            entries = List.of(zone.getConfigurationSection("particles").getValues(false));
        }
        for (Map<?, ?> entry : entries) {
            String name = String.valueOf(entry.get("type"));
            Particle type;
            try {
                type = Particle.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                logger.warning(path + ".particles : particule inconnue " + name);
                continue;
            }
            if (type.getDataType() != Void.class) {
                logger.warning(path + ".particles : " + name + " demande des données, ignorée");
                continue;
            }
            effects.add(new Effect(type, Math.max(1, number(entry, "count", 4).intValue()), Math.max(1, number(entry, "radius", 8).doubleValue()),
                    number(entry, "min-y", 0).doubleValue(), number(entry, "max-y", 4).doubleValue(),
                    Math.max(1, number(entry, "every", 1).intValue()), Math.clamp(number(entry, "chance", 1).doubleValue(), 0, 1),
                    Math.max(0, number(entry, "speed", 0).doubleValue())));
        }
        return List.copyOf(effects);
    }

    /** isolation, no-teleport, effects, on-enter, on-leave. */
    private static Scene scene(ConfigurationSection zone, String path, Logger logger) {
        List<Status> effects = new ArrayList<>();
        for (Map<?, ?> entry : zone.getMapList("effects")) {
            String name = String.valueOf(entry.get("type")).toLowerCase(Locale.ROOT);
            org.bukkit.potion.PotionEffectType type = org.bukkit.Registry.MOB_EFFECT.get(org.bukkit.NamespacedKey.minecraft(name));
            if (type == null) {
                logger.warning(path + ".effects : effet inconnu " + name);
                continue;
            }
            effects.add(new Status(type, Math.max(1, number(entry, "every", 10).intValue()), Math.clamp(number(entry, "chance", 1).doubleValue(), 0, 1),
                    Math.max(1, number(entry, "seconds", 3).intValue()), Math.max(0, number(entry, "amplifier", 0).intValue())));
        }
        Passage passage = zone.isConfigurationSection("passage") ? new Passage(List.copyOf(zone.getStringList("passage.until")),
                List.copyOf(zone.getStringList("passage.keep")), Math.max(10, zone.getInt("passage.timeout", 300))) : null;
        boolean any = zone.getBoolean("isolation", false) || zone.getBoolean("no-teleport", false) || !effects.isEmpty()
                || !zone.getStringList("on-enter").isEmpty() || !zone.getStringList("on-leave").isEmpty() || passage != null;
        return any ? new Scene(zone.getBoolean("isolation", false), zone.getBoolean("no-teleport", false), List.copyOf(effects),
                List.copyOf(zone.getStringList("on-enter")), List.copyOf(zone.getStringList("on-leave")), passage) : Scene.NONE;
    }

    /** ambient : une liste de sons, ou (ancien format) { sound, every }. */
    private static List<Ambient> sounds(ConfigurationSection zone, String path, Logger logger) {
        List<Ambient> sounds = new ArrayList<>();
        if (zone == null) {
            return sounds;
        }
        List<Map<?, ?>> entries = zone.getMapList("ambient");
        if (entries.isEmpty() && zone.isConfigurationSection("ambient")) {
            entries = List.of(zone.getConfigurationSection("ambient").getValues(false));
        }
        for (Map<?, ?> entry : entries) {
            Object sound = entry.get("sound");
            if (sound == null || String.valueOf(sound).isBlank()) {
                continue;
            }
            sounds.add(new Ambient(String.valueOf(sound), Math.max(1, number(entry, "every", 30).intValue()),
                    Math.clamp(number(entry, "chance", 1).doubleValue(), 0, 1), number(entry, "volume", 0.6).floatValue(),
                    number(entry, "pitch", 1).floatValue(), Math.max(0, number(entry, "pitch-spread", 0.1).floatValue())));
        }
        return List.copyOf(sounds);
    }

    private static Number number(Map<?, ?> entry, String key, Number fallback) {
        Object value = entry.get(key);
        if (value instanceof Number number) {
            return number;
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value).replace(',', '.'));
            } catch (NumberFormatException ignored) {
                // valeur abîmée : celle par défaut
            }
        }
        return fallback;
    }
}
