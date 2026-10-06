package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/** Welche Änderungen die Live-Ebene zeichnet. Siehe docs/live.md, „Wann gezeichnet wird“. */
class LiveTest {

    @Test
    void unterDemErstenDeckendenNicht() {
        // Eine Spalte: oben Laub auf 70, deckender Stein ab 64 abwärts, ein Tunnel auf 40.
        IntPredicate deckend = y -> y <= 64 && !Set.of(40, 41).contains(y);
        assertTrue(Live.sichtbar(70, 70, deckend), "der oberste Block");
        assertTrue(Live.sichtbar(66, 70, deckend), "unter dem Laub, über dem Stein");
        assertTrue(Live.sichtbar(64, 70, deckend), "der erste deckende Block selbst");
        assertFalse(Live.sichtbar(63, 70, deckend), "unter dem ersten deckenden");
        assertFalse(Live.sichtbar(40, 70, deckend), "der Tunnel");
        // Abgebaut bis unter die neue Oberfläche: Die Höhenkarte liegt schon tiefer.
        assertTrue(Live.sichtbar(71, 70, deckend), "über dem obersten Block");
    }
}
