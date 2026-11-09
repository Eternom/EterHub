package fr.eternom.eterHub.module.activity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParkourTimeTest {

    @Test
    void formats() {
        assertEquals("0.00 s", ParkourTime.format(0));
        assertEquals("42.10 s", ParkourTime.format(42_105));
        assertEquals("1:05.23", ParkourTime.format(65_230));
        assertEquals("12:00.00", ParkourTime.format(720_000));
        assertEquals("0.00 s", ParkourTime.format(-5));
    }

    @Test
    void records() {
        assertTrue(ParkourTime.isRecord(0, 50_000));
        assertTrue(ParkourTime.isRecord(60_000, 59_999));
        assertFalse(ParkourTime.isRecord(60_000, 60_000));
        assertFalse(ParkourTime.isRecord(60_000, 61_000));
    }
}
