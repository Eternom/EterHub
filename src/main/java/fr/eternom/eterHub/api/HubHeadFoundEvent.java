package fr.eternom.eterHub.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Un joueur trouve une tête cachée (une fois par tête). Thread principal. Pour BetonQuest / EterQuest (« trouve les 3
 * têtes du cimetière »), des succès... found / total : sa progression dans cette chasse.
 */
public class HubHeadFoundEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final long head;
    private final String name;
    private final String zone;
    private final String hunt;
    private final boolean rare;
    private final int found;
    private final int total;

    public HubHeadFoundEvent(Player player, long head, String name, String zone, String hunt, boolean rare, int found, int total) {
        this.player = player;
        this.head = head;
        this.name = name;
        this.zone = zone;
        this.hunt = hunt;
        this.rare = rare;
        this.found = found;
        this.total = total;
    }

    public Player getPlayer() {
        return player;
    }

    /** Identifiant de la tête (eterhub_heads). */
    public long getHead() {
        return head;
    }

    /** Son nom (vide sans nom). */
    public String getName() {
        return name;
    }

    /** Le lieu du village où elle est (vide hors lieu). */
    public String getZone() {
        return zone;
    }

    /** Sa chasse (« main », ou une chasse à thème). */
    public String getHunt() {
        return hunt;
    }

    public boolean isRare() {
        return rare;
    }

    public int getFound() {
        return found;
    }

    public int getTotal() {
        return total;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
