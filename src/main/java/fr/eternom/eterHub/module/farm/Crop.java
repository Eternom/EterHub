package fr.eternom.eterHub.module.farm;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Une culture des Champs (config.yml > farming.crops.<id>) :
 * - récoltée en cassant le bloc mûr (blé, carottes...) : elle se replante et repousse étape par étape ;
 * - ou cueillie d'un clic droit (picked : le bloc qu'elle devient le temps de repousser, ex. les feuilles d'azalée
 *   fleurie qui perdent leurs fleurs) ;
 * item : l'objet donné ; price : prix de base au fermier ou au vigneron (buyer) ; daily-cap : récoltes par jour et par
 * joueur, sur tout le réseau ; regrow-seconds : temps de repousse.
 */
public record Crop(String id, Material block, Material picked, Material item, double price, int dailyCap, String buyer,
                   int regrowSeconds) {

    public boolean pickable() {
        return picked != null;
    }

    public static Map<String, Crop> load(ConfigurationSection farming, Logger logger) {
        Map<String, Crop> crops = new LinkedHashMap<>();
        ConfigurationSection section = farming == null ? null : farming.getConfigurationSection("crops");
        if (section == null) {
            return crops;
        }
        int defaultRegrow = Math.max(10, farming.getInt("regrow-seconds", 180));
        for (String id : section.getKeys(false)) {
            ConfigurationSection crop = section.getConfigurationSection(id);
            if (crop == null) {
                continue;
            }
            Material block = Material.matchMaterial(crop.getString("block", ""));
            Material item = Material.matchMaterial(crop.getString("item", ""));
            Material picked = crop.contains("picked") ? Material.matchMaterial(crop.getString("picked", "")) : null;
            if (block == null || item == null || !item.isItem() || crop.contains("picked") && picked == null) {
                logger.warning("farming.crops." + id + " : bloc ou objet inconnu, ignorée");
                continue;
            }
            crops.put(id, new Crop(id, block, picked, item, Math.max(0, crop.getDouble("price", 0)), Math.max(0, crop.getInt("daily-cap", 32)),
                    crop.getString("buyer", "farmer"), Math.max(10, crop.getInt("regrow-seconds", defaultRegrow))));
        }
        return crops;
    }
}
