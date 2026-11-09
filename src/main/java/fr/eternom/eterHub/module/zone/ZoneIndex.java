package fr.eternom.eterHub.module.zone;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Les zones tracées, pour retrouver vite celle d'une position : la plus petite qui la contient (un lieu dans un
 * quartier, le Donjon secret dans le Donjon). Immuable : remplacée d'un bloc quand les zones changent. Logique pure.
 */
public final class ZoneIndex {

    public static final ZoneIndex EMPTY = new ZoneIndex(Map.of());

    private record Entry(String id, Region region) {
    }

    private final List<Entry> entries;

    public ZoneIndex(Map<String, Region> regions) {
        this.entries = regions.entrySet().stream().map(entry -> new Entry(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong((Entry entry) -> entry.region().volume()).thenComparing(Entry::id))
                .toList();
    }

    /** La zone de cette position (la plus petite qui la contient), vide hors de toute zone. */
    public Optional<String> at(String world, int x, int y, int z) {
        for (Entry entry : entries) {
            if (entry.region().contains(world, x, y, z)) {
                return Optional.of(entry.id());
            }
        }
        return Optional.empty();
    }

    public Optional<Region> region(String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).map(Entry::region).findFirst();
    }

    public int size() {
        return entries.size();
    }
}
