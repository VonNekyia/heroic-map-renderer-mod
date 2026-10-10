package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Die Sprites geheimer Banner über den Kanal. Siehe docs/ebenen.md, „Geheime Banner“. */
class GeheimbannerTest {

    private static final String EBENE = "beispiel:geheim";
    private final List<Geheimbanner.Schluessel> fragen = new ArrayList<>();
    private final List<Identifier> abgelegt = new ArrayList<>(), frei = new ArrayList<>();
    private long jetzt;
    private Geheimbanner g;

    @BeforeEach
    void auf() {
        g = new Geheimbanner((k, b) -> {
            Identifier id = Identifier.fromNamespaceAndPath("test", "geheim_" + abgelegt.size());
            abgelegt.add(id);
            return id;
        }, frei::add, () -> jetzt);
        Geheimbanner.fragen = k -> fragen.add(k);
    }

    @AfterEach
    void ab() {
        Geheimbanner.fragen = Kanal::frageBanner;
    }

    private static String png(int breite, int hoehe) throws IOException {
        BufferedImage b = new BufferedImage(breite, hoehe, BufferedImage.TYPE_INT_ARGB);
        b.setRGB(0, 0, 0xFF1FA88C);
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        ImageIO.write(b, "png", aus);
        return Base64.getEncoder().encodeToString(aus.toByteArray());
    }

    /** Eine Antwort des Plugins wie in seiner Doku; {@code png} null heisst ohne {@code satz} und {@code png}. */
    private static Geheimbanner.Antwort antwort(String version, String entwurf, boolean krone, String png) {
        String mit = png == null ? "" : ",\"satz\":{\"foot\":[10,46],\"angle\":0.0},\"png\":\"" + png + "\"";
        return Geheimbanner.Antwort.lies("{\"v\":1,\"typ\":\"banner\",\"jetzt\":1760000000,\"ebene\":\"" + EBENE + "\",\"version\":\"" + version
                + "\",\"entwurf\":\"" + entwurf + "\",\"krone\":" + krone + mit + "}");
    }

    @Test
    void einmalFragenDannDasSprite() throws IOException {
        assertNull(g.sprite(EBENE, "v1", "nordreich", false));
        assertNull(g.sprite(EBENE, "v1", "nordreich", false));
        // Eine Frage, solange sie offen ist; die Krone ist ein eigener Schlüssel.
        assertEquals(List.of(new Geheimbanner.Schluessel(EBENE, "v1", "nordreich", false)), fragen);
        // Eine Antwort auf etwas, das nicht gefragt ist, nimmt der Mod nicht.
        g.antwort(antwort("v1", "nordreich", true, png(20, 46)));
        assertEquals(List.of(), abgelegt);
        int stand = g.stand();
        g.antwort(antwort("v1", "nordreich", false, png(20, 46)));
        Symbole.Sprite s = g.sprite(EBENE, "v1", "nordreich", false);
        assertNotNull(s);
        assertEquals(10, s.fussX());
        assertEquals(46, s.fussY());
        assertEquals(20, s.textur().breite());
        assertEquals(stand + 1, g.stand());
        assertEquals(1, fragen.size());
    }

    @Test
    void hoechstens8OffenUnd20JeSekunde() throws IOException {
        for (int i = 0; i < 10; i++) {
            g.sprite(EBENE, "v1", "e" + i, false);
        }
        assertEquals(8, fragen.size());
        // Zwei Antworten machen zwei Plätze frei.
        g.antwort(antwort("v1", "e0", false, png(20, 46)));
        g.antwort(antwort("v1", "e1", false, null));
        for (int i = 0; i < 10; i++) {
            g.sprite(EBENE, "v1", "e" + i, false);
        }
        assertEquals(10, fragen.size());
        // Je Sekunde höchstens 20, auch wenn jede Antwort gleich kommt: 10 sind schon hinaus, also noch 10.
        for (int i = 2; i < 10; i++) {
            g.antwort(antwort("v1", "e" + i, false, null));
        }
        for (int i = 10; i < 30; i++) {
            g.sprite(EBENE, "v1", "e" + i, false);
            g.antwort(antwort("v1", "e" + i, false, null));
        }
        assertEquals(20, fragen.size());
        jetzt += 1000;
        g.antwort(antwort("v1", "e2", false, null));
        g.sprite(EBENE, "v1", "e30", false);
        assertEquals(21, fragen.size());
    }

    @Test
    void ohneAntwortOderOhnePngNichtWieder() {
        g.sprite(EBENE, "v1", "still", false);
        jetzt += Geheimbanner.FRIST_MS;
        // Nach der Frist gilt die Frage als ohne Antwort; der Mod fragt nicht wieder, auch nicht viel später.
        assertNull(g.sprite(EBENE, "v1", "still", false));
        jetzt += 600_000;
        assertNull(g.sprite(EBENE, "v1", "still", false));
        // Ohne png ebenso.
        g.sprite(EBENE, "v1", "leer", false);
        g.antwort(antwort("v1", "leer", false, null));
        assertNull(g.sprite(EBENE, "v1", "leer", false));
        assertEquals(List.of("still", "leer"), fragen.stream().map(Geheimbanner.Schluessel::entwurf).toList());
    }

    @Test
    void neueVersionGibtFreiUndFragtNeu() throws IOException {
        g.sprite(EBENE, "v1", "nordreich", false);
        g.antwort(antwort("v1", "nordreich", false, png(20, 46)));
        assertNotNull(g.sprite(EBENE, "v1", "nordreich", false));
        // Eine neue version gibt das alte Sprite frei und fragt neu; eine andere Ebene bleibt.
        assertNull(g.sprite(EBENE, "v2", "nordreich", false));
        assertEquals(abgelegt, frei);
        assertEquals(2, fragen.size());
        // Nach dem Trennen ist alles frei, und der Mod fragt neu.
        g.antwort(antwort("v2", "nordreich", false, png(20, 46)));
        g.leeren();
        assertEquals(abgelegt, frei);
        assertNull(g.sprite(EBENE, "v2", "nordreich", false));
        assertEquals(3, fragen.size());
    }

    @Test
    void antwortLesen() throws IOException {
        Geheimbanner.Antwort a = antwort("v1", "nordreich", true, png(20, 46));
        assertEquals(new Geheimbanner.Schluessel(EBENE, "v1", "nordreich", true), a.schluessel());
        assertEquals(new Symbole.Spritesatz(10, 46, 0), a.satz());
        assertEquals(46, a.bild().hoehe());
        // Ohne png, mit einem zu grossen Bild oder kaputtem Base64: eine Antwort ohne Sprite, so fragt der Mod nicht wieder.
        assertNull(antwort("v1", "nordreich", false, null).bild());
        assertNull(antwort("v1", "nordreich", false, png(33, 46)).bild());
        assertNull(antwort("v1", "nordreich", false, "kein%base64").bild());
        // Keine Antwort banner, oder ein Entwurf, der kein Teil einer Kennung ist: gar keine.
        assertNull(Geheimbanner.Antwort.lies("{\"v\":1,\"typ\":\"tafel\",\"ebene\":\"" + EBENE + "\"}"));
        assertNull(antwort("v1", "../geheim", false, null));
        assertNull(Geheimbanner.Antwort.lies("kein json"));
    }
}
