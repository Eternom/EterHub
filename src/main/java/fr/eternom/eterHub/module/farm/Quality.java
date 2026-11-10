package fr.eternom.eterHub.module.farm;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Qualité d'une récolte : normale, belle, dorée. Tirage et choix du jour : logique pure (testée). */
public enum Quality {
    NORMAL, FINE, GOLDEN;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Quality parse(String text) {
        try {
            return valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            return NORMAL;
        }
    }

    /** pick dans [0, 1[ : dorée sous golden, belle sous golden + fine, normale sinon. */
    public static Quality roll(double pick, double fine, double golden) {
        if (pick < golden) {
            return GOLDEN;
        }
        return pick < golden + fine ? FINE : NORMAL;
    }

    /** Un choix qui change chaque jour, le même pour une date donnée (parcelle du jour). Vide si rien à choisir. */
    public static Optional<String> ofTheDay(LocalDate day, List<String> choices) {
        if (choices.isEmpty()) {
            return Optional.empty();
        }
        long mixed = (day.toEpochDay() + 7) * 2246822519L;
        return Optional.of(choices.get((int) Math.floorMod(mixed ^ (mixed >>> 13), choices.size())));
    }
}
