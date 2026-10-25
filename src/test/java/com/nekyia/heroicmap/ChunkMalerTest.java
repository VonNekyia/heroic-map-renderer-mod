package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.Test;

/**
 * Dünne Flächen mindestens ein Pixel, der Deckel einer Truhe in ihrer Textur und Pflanzen gekippt und eingepasst. Siehe
 * docs/minimap.md, „Flächen und Pixel“, „Blockentities“ und „Pflanzen von oben“.
 */
class ChunkMalerTest {

    private static float[] rechteck(float x0, float z0, float x1, float z1) {
        return new float[] {x0, z0, x0, z1, x1, z1, x1, z0};
    }

    /** Bei Pflanzen zählt eine Fläche erst als oben, wenn sie eher liegt als steht. Siehe docs/minimap.md, „Pflanzen aus Paketen“. */
    @Test
    void liegendGegenGekippt() {
        double w = Math.toRadians(22.5);
        // Eine Oberseite liegt; die Ecken gegen den Uhrzeigersinn von oben gesehen, wie im Spiel: nach Osten, dann nach Nordosten.
        float oben = ChunkMaler.obenAnteil(1, 0, 0, 1, 0, -1);
        assertEquals(1, oben, 1e-6);
        // Eine senkrechte Fläche, um 22,5° gekippt wie in manchen Paketen: Ihre Normale steht 67,5° von oben, n_y = 0,38.
        float gekippt = ChunkMaler.obenAnteil(1, 0, 0, 0, (float) Math.cos(w), (float) -Math.sin(w));
        assertEquals(0.3827, gekippt, 1e-3);
        assertTrue(ChunkMaler.liegt(oben));
        assertFalse(ChunkMaler.liegt(gekippt));
        // Genau 45° liegt noch, 46° nicht mehr.
        assertTrue(ChunkMaler.liegt((float) Math.cos(Math.toRadians(45))));
        assertFalse(ChunkMaler.liegt((float) Math.cos(Math.toRadians(46))));
        // Ohne Fläche nichts.
        assertEquals(0, ChunkMaler.obenAnteil(1, 0, 0, 2, 0, 0));
    }

    @Test
    void duenneFlaecheMindestensEinPixel() {
        // Die Oberseite einer Tür am Nordrand, 3/16 tief: bei 2 px trifft sie keine Mitte (1/4, 3/4), dann die Reihe ihrer Mitte.
        float[] tuer = rechteck(0, 0, 1, 3 / 16f);
        assertNull(ChunkMaler.duenn(tuer, 0, 2));
        assertArrayEquals(new float[] {0, 3 / 32f}, ChunkMaler.duenn(tuer, 1, 2));
        // Bei 4 px liegt die Mitte 1/8 darin, nichts zu tun; bei 1 px auch nicht, die Mitte 1/2 liegt nicht darin, also Reihe 0.
        assertNull(ChunkMaler.duenn(tuer, 1, 4));
        assertArrayEquals(new float[] {0, 3 / 32f}, ChunkMaler.duenn(tuer, 1, 1));
        // Eine Fackel in der Mitte, 2/16 breit: bei 2 px keine Mitte, die Reihe 1, in der ihre Mitte 1/2 liegt.
        float[] fackel = rechteck(7 / 16f, 7 / 16f, 9 / 16f, 9 / 16f);
        assertArrayEquals(new float[] {1, 0.5f}, ChunkMaler.duenn(fackel, 0, 2));
        // Eine volle Oberseite ist nie dünn.
        assertNull(ChunkMaler.duenn(rechteck(0, 0, 1, 1), 0, 16));
    }

    @Test
    void deckelNachFacingUndHaelfte() {
        float[] deckel = rechteck(1 / 16f, 1 / 16f, 15 / 16f, 15 / 16f);
        // Nach Süden: x 1..15 auf u 28..42, z 1 auf v 14 und z 15 auf v 0.
        assertArrayEquals(new float[] {28, 14, 28, 0, 42, 0, 42, 14}, ChunkMaler.deckel(Direction.SOUTH, ChestType.SINGLE, deckel), 1e-4f);
        // Nach Norden um 180 gedreht: jede Ecke liegt gegenüber.
        assertArrayEquals(new float[] {42, 0, 42, 14, 28, 14, 28, 0}, ChunkMaler.deckel(Direction.NORTH, ChestType.SINGLE, deckel), 1e-4f);
        // Nach Osten (toYRot 270): Die Front, im Modell z 15 und v 0, zeigt nach +x, das Modell-x 15, u 42, nach Norden;
        // die Ecke Nordost (x 15, z 1) liegt also bei u 42, v 0.
        float[] osten = ChunkMaler.deckel(Direction.EAST, ChestType.SINGLE, deckel);
        assertArrayEquals(new float[] {42, 0}, new float[] {osten[6], osten[7]}, 1e-4f);
        // Die Hälften einer Doppeltruhe nach Süden: links x 0..15 auf u 29..44, rechts x 1..16 auf u 29..44.
        assertArrayEquals(new float[] {29, 14, 29, 0, 44, 0, 44, 14},
                ChunkMaler.deckel(Direction.SOUTH, ChestType.LEFT, rechteck(0, 1 / 16f, 15 / 16f, 15 / 16f)), 1e-4f);
        assertArrayEquals(new float[] {29, 14, 29, 0, 44, 0, 44, 14},
                ChunkMaler.deckel(Direction.SOUTH, ChestType.RIGHT, rechteck(1 / 16f, 1 / 16f, 1, 15 / 16f)), 1e-4f);
    }

    /** Die Ausdehnung in z über die vier Ecken ab {@code b}: min, max. */
    private static float[] zSpanne(float[] e, int... ab) {
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int b : ab) {
            for (int k = 0; k < 4; k++) {
                min = Math.min(min, e[b + 3 * k + 2]);
                max = Math.max(max, e[b + 3 * k + 2]);
            }
        }
        return new float[] {min, max};
    }

    @Test
    void kippenNachNorden() {
        // Die Mitte oben wandert nach Norden, nach −z, und sinkt auf cos 45°; der Fuss bleibt; x bleibt.
        float[] e = {0.3f, 1, 0.5f, 0.3f, 0, 0.5f, 0.3f, 0, 0.5f, 0.3f, 0, 0.5f};
        ChunkMaler.kippen(e, 0);
        assertArrayEquals(new float[] {0.3f, 0.7071f, -0.2071f, 0.3f, 0, 0.5f}, java.util.Arrays.copyOf(e, 6), 1e-4f);
    }

    @Test
    void cropEingepasst() {
        // Die Ebenen eines crop bei z 4/16 und 12/16, je 1 Block hoch: gekippt −0,384 bis 0,677, Spanne 1,06, Faktor 0,94.
        float[] crop = {0, 0, 0.25f, 1, 0, 0.25f, 1, 1, 0.25f, 0, 1, 0.25f, 0, 0, 0.75f, 1, 0, 0.75f, 1, 1, 0.75f, 0, 1, 0.75f};
        ChunkMaler.kippen(crop, 0);
        ChunkMaler.kippen(crop, 12);
        float[] z = zSpanne(crop, 0, 12);
        assertEquals(-0.384f, z[0], 1e-3f);
        assertEquals(0.677f, z[1], 1e-3f);
        float faktor = ChunkMaler.einpassFaktor(z[0], z[1]), schieben = ChunkMaler.einpassVerschiebung(z[0], z[1]);
        assertEquals(0.943f, faktor, 1e-3f);
        // Gestaucht genau in den Block.
        assertEquals(0, z[0] * faktor + schieben, 1e-6f);
        assertEquals(1, z[1] * faktor + schieben, 1e-6f);
    }

    @Test
    void kleinePflanzeBleibtVersetzt() {
        // Eine Fläche halb so hoch, 0,1 nach Süden versetzt (z 0,85): gekippt 0,394 bis 0,747, passt also und bleibt, wo sie ist.
        float[] klein = {0, 0, 0.85f, 1, 0, 0.85f, 1, 0.5f, 0.85f, 0, 0.5f, 0.85f};
        ChunkMaler.kippen(klein, 0);
        float[] z = zSpanne(klein, 0);
        assertEquals(0.394f, z[0], 1e-3f);
        assertEquals(0.747f, z[1], 1e-3f);
        assertEquals(1, ChunkMaler.einpassFaktor(z[0], z[1]));
        assertEquals(0, ChunkMaler.einpassVerschiebung(z[0], z[1]));
        // Ragt sie über eine Kante, so wenig wie nötig zurück; ein volles Kreuz (Spanne 1,34) wird gestaucht und an 0 gelegt.
        assertEquals(0.1f, ChunkMaler.einpassVerschiebung(-0.1f, 0.8f), 1e-6f);
        assertEquals(-0.2f, ChunkMaler.einpassVerschiebung(0.3f, 1.2f), 1e-6f);
        assertEquals(0.744f, ChunkMaler.einpassFaktor(-0.525f, 0.818f), 1e-3f);
        assertEquals(0, -0.525f * ChunkMaler.einpassFaktor(-0.525f, 0.818f) + ChunkMaler.einpassVerschiebung(-0.525f, 0.818f), 1e-6f);
    }

    @Test
    void gekippteFlaecheNachObenSchattiert() {
        CardinalLighting licht = CardinalLighting.DEFAULT;
        // Die Vorlagen des Spiels schattieren schon nach oben: nichts zu ändern.
        assertEquals(1f, ChunkMaler.schattenNachOben(licht, Direction.UP, Direction.NORTH), 1e-6f);
        // Eine Fläche nach Norden, ohne Überschreiben: deren Schatten heraus, der nach oben hinein.
        assertEquals(licht.up() / licht.north(), ChunkMaler.schattenNachOben(licht, null, Direction.NORTH), 1e-6f);
        assertEquals(licht.up() / licht.east(), ChunkMaler.schattenNachOben(licht, Direction.EAST, Direction.NORTH), 1e-6f);
    }

    @Test
    void draufsichtWieFlaechenEinzeln() {
        // Pixel 0: vorn halb durchsichtig, dahinter deckend; Pixel 1 ohne Fläche. Der Faktor 1,5 kappt bei 1.
        float[] vorn = {0.2f, 0.4f, 0.6f}, hinten = {0.8f, 0.1f, 0.3f};
        float[][] faktoren = {{0.9f, 0.8f, 0.7f}, {1.5f, 1, 0.5f}};
        float[] zellen = new float[2 * 2 * 4], deckung = new float[2];
        deckung[0] = ChunkMaler.stapel(zellen, 0, 0, 0.5f, vorn[0], vorn[1], vorn[2]);
        assertEquals(0.5f, deckung[0], 1e-6f);
        deckung[0] = ChunkMaler.stapel(zellen, 4, deckung[0], 1, hinten[0], hinten[1], hinten[2]);
        assertEquals(1, deckung[0], 1e-6f);

        // Die Spalte deckt Pixel 0 schon zu einem Viertel. Erwartet wie in schicht: Fläche für Fläche darunter.
        float[] summe = {0.1f, 0.1f, 0.1f, 0.25f, 0, 0, 0, 0};
        ChunkMaler.lege(summe, zellen, deckung, faktoren);
        float[] erwartet = {0.1f, 0.1f, 0.1f, 1};
        for (int c = 0; c < 3; c++) {
            erwartet[c] += 0.75f * 0.5f * Math.min(1, ChunkMaler.srgb(vorn[c]) * faktoren[0][c]);
            erwartet[c] += 0.375f * Math.min(1, ChunkMaler.srgb(hinten[c]) * faktoren[1][c]);
        }
        assertArrayEquals(erwartet, java.util.Arrays.copyOf(summe, 4), 1e-6f);
        assertArrayEquals(new float[4], java.util.Arrays.copyOfRange(summe, 4, 8));

        // Was schon deckt, bleibt.
        float[] gedeckt = {0.3f, 0.3f, 0.3f, 1, 0, 0, 0, 0};
        ChunkMaler.lege(gedeckt, zellen, deckung, faktoren);
        assertArrayEquals(new float[] {0.3f, 0.3f, 0.3f, 1, 0, 0, 0, 0}, gedeckt);
    }

    /** Eine Textur seite × seite, deckend nur, wo {@code deckt} es sagt. */
    private static ChunkMaler.Texel textur(int seite, java.util.function.BiPredicate<Integer, Integer> deckt) {
        int[] argb = new int[seite * seite];
        for (int y = 0; y < seite; y++) {
            for (int x = 0; x < seite; x++) {
                argb[y * seite + x] = deckt.test(x, y) ? 0xFFE0C020 : 0;
            }
        }
        return new ChunkMaler.Texel(argb, seite, seite);
    }

    /** Eine senkrechte Fläche bei z 0,25 von y unten bis oben, x 0 bis 1; Ecken oben links, unten links, unten rechts, oben rechts. */
    private static float[] stehend(float unten, float oben) {
        return new float[] {0, oben, 0.25f, 0, unten, 0.25f, 1, unten, 0.25f, 1, oben, 0.25f};
    }

    /** UV der Fläche: u von 0 bis {@code ub}, v von 0 bis 1 des Sprites, das bei (0,5, 0,25) mit 0,25 × 0,25 im Atlas liegt. */
    private static float[] uv(float ub) {
        return new float[] {0.5f, 0.25f, 0.5f, 0.5f, 0.5f + 0.25f * ub, 0.5f, 0.5f + 0.25f * ub, 0.25f};
    }

    @Test
    void hoherWeizenOhneDurchsichtigeObereHaelfte() {
        // 32 hoch von −1/16 bis 31/16, UV [0, 0, 8, 16] auf 32 × 32, deckend nur im Viertel links unten.
        float[] e = stehend(-1 / 16f, 31 / 16f), uv = uv(0.5f);
        ChunkMaler.Texel t = textur(32, (x, y) -> x < 16 && y >= 16);
        assertArrayEquals(new float[] {-1 / 16f, 15 / 16f}, ChunkMaler.deckendesY(e, 0, uv, 0, t, 0.5f, 0.25f, 0.25f, 0.25f), 1e-5f);
        ChunkMaler.zuschneiden(e, 0, uv, 0, t, 0.5f, 0.25f, 0.25f, 0.25f);
        // Oben bei 1 abgeschnitten, v dort 15/32 des Sprites; unten bleibt.
        assertArrayEquals(stehend(-1 / 16f, 1), e, 1e-5f);
        assertArrayEquals(new float[] {0.5f, 0.25f + 0.25f * 15 / 32f, 0.5f, 0.5f, 0.625f, 0.5f, 0.625f, 0.25f + 0.25f * 15 / 32f}, uv, 1e-5f);
    }

    @Test
    void cropUndKreuzBleiben() {
        // crop von −1/16 bis 15/16, die unterste Reihe deckt: bleibt, wie es ist.
        float[] e = stehend(-1 / 16f, 15 / 16f), uv = uv(1);
        ChunkMaler.zuschneiden(e, 0, uv, 0, textur(16, (x, y) -> y == 15 && x == 7), 0.5f, 0.25f, 0.25f, 0.25f);
        assertArrayEquals(stehend(-1 / 16f, 15 / 16f), e);
        assertArrayEquals(uv(1), uv);
        // Ein Kreuz von 0 bis 1 mit durchsichtigem Rand wie Mohn: im Block, also bleibt es.
        e = stehend(0, 1);
        uv = uv(1);
        ChunkMaler.zuschneiden(e, 0, uv, 0, textur(16, (x, y) -> x >= 5 && x <= 10 && y >= 6), 0.5f, 0.25f, 0.25f, 0.25f);
        assertArrayEquals(stehend(0, 1), e);
        assertArrayEquals(uv(1), uv);
    }

    @Test
    void ohneWaagrechteKantenUndGanzDurchsichtig() {
        // Gedreht, kein Paar Kanten waagrecht: bleibt, auch über dem Block.
        float[] schief = {0, 1.5f, 0.25f, 0.3f, -0.2f, 0.25f, 1.3f, 0, 0.25f, 1, 1.7f, 0.25f}, uv = uv(1);
        float[] vorher = schief.clone();
        ChunkMaler.zuschneiden(schief, 0, uv, 0, textur(16, (x, y) -> y >= 12), 0.5f, 0.25f, 0.25f, 0.25f);
        assertArrayEquals(vorher, schief);
        // Ganz über dem Block und ganz durchsichtig: ohne Höhe bei 1,2.
        float[] e = stehend(1.2f, 1.9f);
        ChunkMaler.zuschneiden(e, 0, uv(1), 0, textur(16, (x, y) -> false), 0.5f, 0.25f, 0.25f, 0.25f);
        assertArrayEquals(stehend(1.2f, 1.2f), e, 1e-5f);
    }
}
