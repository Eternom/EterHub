package fr.eternom.eterHub.module.display;

/** Un temps en millisecondes, pour les tableaux d'honneur « time » : « 1:05.23 », « 42.10 s ». Logique pure (testée). */
public final class TimeFormat {

    private TimeFormat() {
    }

    public static String format(long millis) {
        long safe = Math.max(0, millis);
        long minutes = safe / 60_000;
        long seconds = safe / 1000 % 60;
        long hundredths = safe % 1000 / 10;
        return minutes > 0 ? String.format("%d:%02d.%02d", minutes, seconds, hundredths)
                : String.format("%d.%02d s", seconds, hundredths);
    }
}
