package fr.eternom.eterHub.module.zone;

import java.util.Locale;

/** Ce qu'une zone permet ou non (config.yml > zones.<zone>.rules, sinon zone-defaults.rules). */
public enum ZoneRule {
    /** Le double saut. */
    DOUBLE_JUMP,
    /** La pêche (canne, ou la ligne sans canne du lac). */
    FISHING,
    /** Perles, boules de neige, œufs, flèches, tridents. */
    PROJECTILES,
    /** Les fouilles archéologiques. */
    DIG,
    /** Poser bateaux et chariots. */
    BOATS,
    /** S'asseoir sur les bancs, escaliers et dalles. */
    SIT,
    /** Récolter les cultures mûres et cueillir les vignes (parcelles des Champs). */
    HARVEST;

    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
