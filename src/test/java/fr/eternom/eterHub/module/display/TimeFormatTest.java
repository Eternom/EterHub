package fr.eternom.eterHub.module.display;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeFormatTest {

    @Test
    void formats() {
        assertEquals("0.00 s", TimeFormat.format(0));
        assertEquals("42.10 s", TimeFormat.format(42_105));
        assertEquals("1:05.23", TimeFormat.format(65_230));
        assertEquals("12:00.00", TimeFormat.format(720_000));
        assertEquals("0.00 s", TimeFormat.format(-5));
    }
}
