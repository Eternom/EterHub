package fr.eternom.eterHub.module.movement;

import java.util.Locale;
import java.util.Optional;

/**
 * Une plaque du village, posée sur un bloc précis (eterhub_pads, commune à tous les lobbys) :
 * - LAUNCH : projette (force, hauteur ; direction fixe si yaw est réglé, sinon vers où le joueur regarde) ;
 * - ELEVATOR : ascenseur, sauter monte à l'ascenseur du dessus (même colonne), s'accroupir descend ;
 * - SPEED : couloir de vitesse, quelques secondes plus rapide (force = durée en secondes) ;
 * - TELEPORT : envoie au point visé (entre les falaises).
 */
public record Pad(long id, Type type, String world, int x, int y, int z, double power, double height, Float yaw,
                  Target target) {

    public enum Type {
        LAUNCH, ELEVATOR, SPEED, TELEPORT;

        public static Optional<Type> parse(String text) {
            return switch (text.toLowerCase(Locale.ROOT)) {
                case "launch", "lancement", "lanceur" -> Optional.of(LAUNCH);
                case "elevator", "ascenseur" -> Optional.of(ELEVATOR);
                case "speed", "vitesse" -> Optional.of(SPEED);
                case "teleport", "tp", "teleportation" -> Optional.of(TELEPORT);
                default -> Optional.empty();
            };
        }
    }

    /** Point d'arrivée d'une plaque de téléportation. */
    public record Target(String world, double x, double y, double z, float yaw, float pitch) {
    }

    /** Clé du bloc : « monde:x:y:z ». */
    public String key() {
        return key(world, x, y, z);
    }

    public static String key(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    /** Colonne (ascenseurs) : « monde:x:z ». */
    public String column() {
        return world + ":" + x + ":" + z;
    }
}
