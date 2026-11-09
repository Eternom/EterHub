package fr.eternom.eterHub.module.activity;

/** Mise en forme d'un temps de parcours : « 1:05.23 », « 42.10 s ». Logique pure (testée). */
public final class ParkourTime {

    private ParkourTime() {
    }

    public static String format(long millis) {
        long safe = Math.max(0, millis);
        long minutes = safe / 60_000;
        long seconds = safe / 1000 % 60;
        long hundredths = safe % 1000 / 10;
        return minutes > 0 ? String.format("%d:%02d.%02d", minutes, seconds, hundredths)
                : String.format("%d.%02d s", seconds, hundredths);
    }

    /** Un nouveau record : pas de temps avant, ou plus rapide. */
    public static boolean isRecord(long previousBest, long time) {
        return previousBest <= 0 || time < previousBest;
    }
}
