package fr.eternom.eterHub.module.zone;

/**
 * Un pavé de blocs entre deux coins (inclus), dans un monde. Logique pure (testée).
 */
public record Region(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    /** Les deux coins dans n'importe quel ordre. */
    public static Region of(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Region(world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(String world, int x, int y, int z) {
        return this.world.equals(world) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** Nombre de blocs : la plus petite zone l'emporte quand deux zones se chevauchent (un lieu dans un quartier). */
    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    /** Le centre, au niveau du sol de la zone. */
    public double[] center() {
        return new double[]{(minX + maxX + 1) / 2.0, minY, (minZ + maxZ + 1) / 2.0};
    }

    public String describe() {
        return world + " " + minX + "," + minY + "," + minZ + " → " + maxX + "," + maxY + "," + maxZ;
    }
}
