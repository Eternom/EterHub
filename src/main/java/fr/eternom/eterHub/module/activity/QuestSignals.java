package fr.eternom.eterHub.module.activity;

import fr.eternom.eterHub.api.HubActivityEvent;
import fr.eternom.eterHub.api.HubHeadFoundEvent;
import fr.eternom.eterHub.api.HubZoneEnterEvent;
import fr.eternom.eterLib.module.quest.QuestBridge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Locale;
import java.util.Map;

/**
 * Le village parle aux quêtes (EterQuest, par QuestBridge d'EterLib ; sans lui, rien) :
 * <pre>
 *   eterhub:zone {zone, first}              entrée dans un lieu (first : première visite)
 *   eterhub:head {hunt, zone, rare}         tête trouvée
 *   eterhub:find {find, rarity}             trouvaille des fouilles
 *   eterhub:tour · eterhub:discovery-complete
 *   eterhub:fish {fish, rarity, grams}      (Fishing)      eterhub:harvest {crop, quality, zone}   (Farming)
 * </pre>
 */
public class QuestSignals implements Listener {

    private static final String[] RARITIES = {"common", "uncommon", "rare", "legendary"};

    @EventHandler(priority = EventPriority.MONITOR)
    public void onZone(HubZoneEnterEvent event) {
        if (!event.getZone().isEmpty()) {
            QuestBridge.signal(event.getPlayer(), "eterhub:zone", Map.of("zone", event.getZone(),
                    "first", String.valueOf(event.isFirstVisit())), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHead(HubHeadFoundEvent event) {
        QuestBridge.signal(event.getPlayer(), "eterhub:head", Map.of("hunt", event.getHunt(), "zone",
                event.getZone() == null ? "" : event.getZone(), "rare", String.valueOf(event.isRare())), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onActivity(HubActivityEvent event) {
        switch (event.getKind()) {
            case "dig" -> QuestBridge.signal(event.getPlayer(), "eterhub:find", Map.of("find", event.getDetail(),
                    "rarity", rarity(event.getValue())), 1);
            case "tour", "discovery-complete" -> QuestBridge.signal(event.getPlayer(), "eterhub:" + event.getKind(), Map.of(), 1);
            default -> {
                // fishing et harvest : signalés par Fishing et Farming (poids, qualité, lieu)
            }
        }
    }

    static String rarity(long ordinal) {
        return ordinal >= 0 && ordinal < RARITIES.length ? RARITIES[(int) ordinal] : String.valueOf(ordinal).toLowerCase(Locale.ROOT);
    }
}
