package fr.eternom.eterHub.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Un joueur entre dans un lieu du village (ou en sort : zone vide). firstVisit : il le découvre. Thread principal.
 * Pour EterQuest / BetonQuest (« va au Lac de pêche »), la visite guidée, les succès...
 */
public class HubZoneEnterEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String from;
    private final String zone;
    private final boolean firstVisit;

    public HubZoneEnterEvent(Player player, String from, String zone, boolean firstVisit) {
        this.player = player;
        this.from = from;
        this.zone = zone;
        this.firstVisit = firstVisit;
    }

    public Player getPlayer() {
        return player;
    }

    /** La zone quittée (vide : aucune). */
    public String getFrom() {
        return from;
    }

    /** La zone où il entre (vide : il sort de toute zone). */
    public String getZone() {
        return zone;
    }

    public boolean isFirstVisit() {
        return firstVisit;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
