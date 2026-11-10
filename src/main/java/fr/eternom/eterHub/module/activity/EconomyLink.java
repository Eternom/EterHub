package fr.eternom.eterHub.module.activity;

import fr.eternom.eterEconomy.api.EconomyApi;
import org.bukkit.Bukkit;

import java.util.Locale;
import java.util.UUID;

/**
 * EterEconomy vu d'EterHub (facultatif) : l'API n'est touchée que si le plugin tourne ici. Sans lui, le poissonnier
 * reste fermé (ni rachat, ni appâts).
 */
public final class EconomyLink {

    private EconomyLink() {
    }

    public static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("EterEconomy") && Api.present();
    }

    /** Versement. Bloquant (base). */
    public static boolean deposit(UUID player, double amount, String source) {
        return Api.deposit(player, amount, source);
    }

    /** Retrait (achat). Bloquant (base). false : pas assez, ou EterEconomy absent. */
    public static boolean withdraw(UUID player, double amount, String source) {
        return available() && Api.withdraw(player, amount, source);
    }

    /** « 1 234 Heloks », ou le nombre seul sans EterEconomy. */
    public static String format(double amount) {
        return available() ? Api.format(amount) : String.format(Locale.FRANCE, "%.2f", amount);
    }

    /** Seule classe qui charge EconomyApi : jamais utilisée sans EterEconomy. */
    private static final class Api {
        static boolean present() {
            return EconomyApi.get().isPresent();
        }

        static boolean deposit(UUID player, double amount, String source) {
            return EconomyApi.get().map(api -> api.deposit(player, amount, source)).orElse(false);
        }

        static boolean withdraw(UUID player, double amount, String source) {
            return EconomyApi.get().map(api -> api.withdraw(player, amount, source)).orElse(false);
        }

        static String format(double amount) {
            return EconomyApi.get().map(api -> api.format(amount)).orElse(String.valueOf(amount));
        }
    }
}
