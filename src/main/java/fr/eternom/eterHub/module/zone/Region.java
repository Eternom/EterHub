package fr.eternom.eterHub.module.zone;

import java.util.Arrays;

/**
 * Les limites d'un lieu, dans un monde. Logique pure (testée). Deux formes :
 * - un pavé entre deux coins (inclus) ;
 * - une surface libre : un polygone de points (x, z), même concave (arc de cercle, bras d'un lac), avec une hauteur
 *   min / max facultative (un lieu sous un autre) : sans hauteur, toute la hauteur du monde.
 * Le pavé englobant (minX..maxZ) sert de premier tri rapide.
 */
public record Region(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int[] xs, int[] zs) {

    /** Hauteur prise en compte pour comparer deux lieux quand une surface n'a pas de hauteur. */
    public static final int FULL_HEIGHT = 384;
    public static final int NO_MIN = -2048;
    public static final int NO_MAX = 4096;

    /** Un pavé : les deux coins dans n'importe quel ordre. */
    public static Region of(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Region(world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2), null, null);
    }

    /** Une surface libre (au moins 3 points) ; minY / maxY : NO_MIN / NO_MAX sans hauteur. */
    public static Region polygon(String world, int[] xs, int[] zs, int minY, int maxY) {
        if (xs.length < 3 || xs.length != zs.length) {
            throw new IllegalArgumentException("Il faut au moins 3 points");
        }
        return new Region(world, Arrays.stream(xs).min().orElseThrow(), Math.min(minY, maxY), Arrays.stream(zs).min().orElseThrow(),
                Arrays.stream(xs).max().orElseThrow(), Math.max(minY, maxY), Arrays.stream(zs).max().orElseThrow(),
                xs.clone(), zs.clone());
    }

    public boolean isPolygon() {
        return xs != null;
    }

    public boolean hasHeight() {
        return !isPolygon() || minY > NO_MIN || maxY < NO_MAX;
    }

    public boolean contains(String world, int x, int y, int z) {
        if (!this.world.equals(world) || x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
            return false;
        }
        return !isPolygon() || insidePolygon(x + 0.5, z + 0.5);
    }

    /** Dans le polygone (centres des blocs), bords compris : un bloc à moins d'un demi-bloc d'un côté est dedans. */
    private boolean insidePolygon(double px, double pz) {
        boolean inside = false;
        int n = xs.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = xs[i] + 0.5;
            double zi = zs[i] + 0.5;
            double xj = xs[j] + 0.5;
            double zj = zs[j] + 0.5;
            if (segmentDistance(px, pz, xi, zi, xj, zj) <= 0.5) {
                return true;
            }
            if ((zi > pz) != (zj > pz) && px < (xj - xi) * (pz - zi) / (zj - zi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static double segmentDistance(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double length = dx * dx + dz * dz;
        double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / length));
        double cx = ax + t * dx - px;
        double cz = az + t * dz - pz;
        return Math.sqrt(cx * cx + cz * cz);
    }

    /** Nombre de blocs (environ, pour une surface) : la plus petite zone l'emporte quand deux lieux se chevauchent. */
    public long volume() {
        long height = Math.min(FULL_HEIGHT, (long) maxY - minY + 1);
        if (!isPolygon()) {
            return (long) (maxX - minX + 1) * height * (maxZ - minZ + 1);
        }
        double area = 0;
        double perimeter = 0;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            area += (double) xs[j] * zs[i] - (double) xs[i] * zs[j];
            perimeter += Math.hypot(xs[i] - xs[j], zs[i] - zs[j]);
        }
        return Math.max(1, Math.round(Math.abs(area) / 2 + perimeter / 2 + 1)) * height;
    }

    /** Le centre, au niveau du sol de la zone. */
    public double[] center() {
        return new double[]{(minX + maxX + 1) / 2.0, minY, (minZ + maxZ + 1) / 2.0};
    }

    public String describe() {
        if (isPolygon()) {
            return world + " " + xs.length + " points" + (hasHeight() ? ", y " + minY + " → " + maxY : "");
        }
        return world + " " + minX + "," + minY + "," + minZ + " → " + maxX + "," + maxY + "," + maxZ;
    }

    /** Points pour la base : « x,z;x,z;... » (vide pour un pavé). */
    public String points() {
        if (!isPolygon()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < xs.length; i++) {
            text.append(i > 0 ? ";" : "").append(xs[i]).append(',').append(zs[i]);
        }
        return text.toString();
    }

    /** Relit une forme gardée en base. */
    public static Region read(String world, int x1, int y1, int z1, int x2, int y2, int z2, String points) {
        if (points == null || points.isBlank()) {
            return of(world, x1, y1, z1, x2, y2, z2);
        }
        String[] parts = points.split(";");
        int[] xs = new int[parts.length];
        int[] zs = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String[] xz = parts[i].split(",");
            xs[i] = Integer.parseInt(xz[0].trim());
            zs[i] = Integer.parseInt(xz[1].trim());
        }
        return polygon(world, xs, zs, Math.min(y1, y2), Math.max(y1, y2));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Region region && world.equals(region.world) && minX == region.minX && minY == region.minY
                && minZ == region.minZ && maxX == region.maxX && maxY == region.maxY && maxZ == region.maxZ
                && Arrays.equals(xs, region.xs) && Arrays.equals(zs, region.zs);
    }

    @Override
    public int hashCode() {
        return 31 * java.util.Objects.hash(world, minX, minY, minZ, maxX, maxY, maxZ) + Arrays.hashCode(xs) * 7 + Arrays.hashCode(zs);
    }

    @Override
    public String toString() {
        return "Region[" + describe() + "]";
    }
}
