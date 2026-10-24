package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.slf4j.Logger;

/**
 * Führt den Ausweis der Welt, in der der Spieler ist: beim Betreten einer Dimension Besuch und Weltschlüssel, im Tick die
 * Spielzeit, geschrieben beim ersten Wert und beim Verlassen. Nur in einen Ordner, den es schon gibt; ein Besuch allein legt
 * keinen an. Nur auf dem Render-Thread. Siehe docs/download.md, „Ausweis“.
 */
final class Ausweise {

    static final Ausweise INSTANZ = new Ausweise();
    private static final Logger LOGGER = LogUtils.getLogger();

    private Path ordner;
    private String schluessel;
    private Ausweis ausweis;
    /** Die letzte Spielzeit vom Server, oder -1. */
    private long alter = -1;
    private boolean geschrieben;

    private Ausweise() {
    }

    /** Nach jedem Wechsel der Welt, auch der Dimension, nachdem Ablage, Name und Karte entschieden sind. */
    void betreten(Minecraft mc) {
        verlassen();
        Path o = Downloads.weltOrdner();
        if (o == null || mc.level == null) {
            return;
        }
        ordner = o;
        schluessel = mc.level.dimension().identifier().toString();
        Ausweis a = Ausweis.lies(o);
        ausweis = (a != null ? a : Ausweis.neu(o.getFileName().toString().replaceFirst("^welt-", "")))
                .mit(netz(), server()).besucht(schluessel, Instant.now(), ZoneId.systemDefault());
        alter = -1;
        geschrieben = false;
    }

    /** Der Ordner der Welt zog mitten im Besuch um, etwa zur Karte vom Server oder in eine andere Ablage. */
    void ordnerGewechselt() {
        Path o = Downloads.weltOrdner();
        if (ausweis != null && o != null) {
            ordner = o;
            ausweis = ausweis.mit(netz(), server());
        }
    }

    /** Im Tick: die Spielzeit, sobald der Server sie geschickt hat; beim ersten Wert gleich geschrieben, mit einer Zeile im Log. */
    void tick(Minecraft mc) {
        if (ausweis == null || mc.level == null || mc.level.getGameTime() <= 0) {
            return;
        }
        alter = mc.level.getGameTime();
        if (!geschrieben && schreibe()) {
            // Ob die Spielzeit bei jedem Wechsel kommt und weiterläuft, ist zu prüfen; die Zeile zeigt es im Log.
            LOGGER.info("Heroic Map: Ausweis {}, {}, Spielzeit {}", ordner.getFileName(), schluessel, alter);
        }
    }

    /** Beim Wechsel der Welt und beim Trennen: geschrieben, dann vergessen. */
    void verlassen() {
        schreibe();
        ausweis = null;
        ordner = null;
    }

    /** Schreibt den Ausweis mit der letzten Spielzeit, wenn es den Ordner gibt; wahr, wenn geschrieben. */
    private boolean schreibe() {
        if (ausweis == null || ordner == null || !Files.isDirectory(ordner)) {
            return false;
        }
        if (alter > 0) {
            ausweis = ausweis.alter(schluessel, alter);
        }
        try {
            ausweis.schreibe(ordner);
            geschrieben = true;
            return true;
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: Ausweis in {} nicht geschrieben", ordner, e);
            return false;
        }
    }

    /** Für den Gametest: der Ausweis der Welt, in der der Spieler ist, wie er im Speicher steht. */
    Ausweis jetzt() {
        return ausweis;
    }

    private static String netz() {
        ServerData s = Downloads.server();
        return s == null ? "" : Servername.schluessel(s.ip);
    }

    private static String server() {
        String wer = Downloads.INSTANZ.wer();
        return Zeigt.OHNE.equals(wer) ? "" : wer;
    }
}
