package fr.eternom.eterHub.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Un joueur réussit une activité du village. Thread principal. kind / detail :
 * <ul>
 *     <li>fishing / poisson (rareté dans value : 0 commun → 3 légendaire)</li>
 *     <li>dig / trouvaille de fouille</li>
 *     <li>head / tête trouvée (value : têtes trouvées en tout)</li>
 *     <li>discovery-complete / « » (tout le village découvert)</li>
 *     <li>tour / « » (visite guidée finie)</li>
 * </ul>
 */
public class HubActivityEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String kind;
    private final String detail;
    private final long value;
    private final boolean record;

    public HubActivityEvent(Player player, String kind, String detail, long value, boolean record) {
        this.player = player;
        this.kind = kind;
        this.detail = detail;
        this.value = value;
        this.record = record;
    }

    public Player getPlayer() {
        return player;
    }

    public String getKind() {
        return kind;
    }

    public String getDetail() {
        return detail;
    }

    public long getValue() {
        return value;
    }

    public boolean isRecord() {
        return record;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
