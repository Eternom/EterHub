package fr.eternom.eterHub.module.heads;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HuntTest {

    private static Hunt hunt(String id, String start, String end) {
        return new Hunt(id, start == null ? null : MonthDay.parse("--" + start), end == null ? null : MonthDay.parse("--" + end),
                true, List.of(), List.of(), List.of(), Map.of());
    }

    @Test
    void mainIsAlwaysOpen() {
        assertTrue(hunt(Hunt.MAIN, "10-20", "11-02").openOn(LocalDate.of(2026, 6, 1)));
    }

    @Test
    void withinTheYear() {
        Hunt halloween = hunt("halloween", "10-20", "11-02");
        assertTrue(halloween.openOn(LocalDate.of(2026, 10, 20)));
        assertTrue(halloween.openOn(LocalDate.of(2026, 10, 31)));
        assertTrue(halloween.openOn(LocalDate.of(2026, 11, 2)));
        assertFalse(halloween.openOn(LocalDate.of(2026, 11, 3)));
        assertFalse(halloween.openOn(LocalDate.of(2026, 10, 19)));
    }

    @Test
    void acrossNewYear() {
        Hunt christmas = hunt("noel", "12-15", "01-05");
        assertTrue(christmas.openOn(LocalDate.of(2026, 12, 24)));
        assertTrue(christmas.openOn(LocalDate.of(2027, 1, 1)));
        assertTrue(christmas.openOn(LocalDate.of(2027, 1, 5)));
        assertFalse(christmas.openOn(LocalDate.of(2027, 1, 6)));
        assertFalse(christmas.openOn(LocalDate.of(2026, 12, 14)));
        assertFalse(christmas.openOn(LocalDate.of(2026, 7, 14)));
    }

    @Test
    void withoutDatesIsOpen() {
        assertTrue(hunt("pirates", null, null).openOn(LocalDate.of(2026, 3, 3)));
    }
}
