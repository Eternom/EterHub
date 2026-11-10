package fr.eternom.eterHub.module.heads;

import org.bukkit.configuration.ConfigurationSection;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Une chasse aux têtes : la principale (« main », toujours ouverte) ou une chasse à thème (Halloween, Noël...) ouverte
 * entre deux dates qui reviennent chaque année (« 10-20 » → « 11-02 », même à cheval sur le Nouvel An). Fermée, ses
 * têtes ne comptent plus, et disparaissent si hide-when-closed. Ses récompenses : à chaque tête, aux paliers, à la fin.
 * La logique des dates est pure (testée).
 */
public record Hunt(String id, MonthDay start, MonthDay end, boolean hideWhenClosed, List<String> rewardEach,
                   List<String> rewardRare, List<String> rewardAll, Map<Integer, List<String>> milestones) {

    public static final String MAIN = "main";

    public boolean isMain() {
        return MAIN.equals(id);
    }

    /** Ouverte ce jour-là : la principale toujours ; une chasse sans dates aussi. */
    public boolean openOn(LocalDate day) {
        if (isMain() || start == null || end == null) {
            return true;
        }
        MonthDay today = MonthDay.from(day);
        if (!start.isAfter(end)) {
            return !today.isBefore(start) && !today.isAfter(end);
        }
        // À cheval sur le Nouvel An (12-15 → 01-05)
        return !today.isBefore(start) || !today.isAfter(end);
    }

    /** La principale (section heads) puis les chasses à thème (heads.hunts.<id>). */
    public static Map<String, Hunt> load(ConfigurationSection heads, Logger logger) {
        Map<String, Hunt> hunts = new LinkedHashMap<>();
        hunts.put(MAIN, read(MAIN, heads, logger));
        ConfigurationSection section = heads == null ? null : heads.getConfigurationSection("hunts");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                if (!id.matches("[a-z0-9_-]{1,32}") || MAIN.equals(id)) {
                    logger.warning("heads.hunts." + id + " : identifiant invalide (minuscules, chiffres, - et _), ignorée");
                    continue;
                }
                hunts.put(id, read(id, section.getConfigurationSection(id), logger));
            }
        }
        return hunts;
    }

    private static Hunt read(String id, ConfigurationSection section, Logger logger) {
        if (section == null) {
            return new Hunt(id, null, null, false, List.of(), List.of(), List.of(), Map.of());
        }
        Map<Integer, List<String>> milestones = new TreeMap<>();
        ConfigurationSection steps = section.getConfigurationSection("milestones");
        if (steps != null) {
            for (String key : steps.getKeys(false)) {
                try {
                    milestones.put(Integer.parseInt(key.trim()), new ArrayList<>(steps.getStringList(key)));
                } catch (NumberFormatException e) {
                    logger.warning("heads milestones." + key + " : pas un nombre, ignoré");
                }
            }
        }
        return new Hunt(id, day(section.getString("start"), id, logger), day(section.getString("end"), id, logger),
                section.getBoolean("hide-when-closed", false), section.getStringList("reward-each"),
                section.getStringList("reward-rare"), section.getStringList("reward-all"), milestones);
    }

    private static MonthDay day(String text, String id, Logger logger) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return MonthDay.parse("--" + text.trim());
        } catch (DateTimeParseException e) {
            logger.warning("heads.hunts." + id + " : date invalide « " + text + " » (format MM-JJ, ex : 10-20)");
            return null;
        }
    }
}
