package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
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
    /** Die Welt, wie der Ausweis sie vor diesem Besuch kannte, oder null; daran misst sich ein Reset. */
    private Ausweis.Welt vorher;
    /** Ist der Reset für diesen Besuch entschieden? Ticks seit dem ersten Wert der Spielzeit. */
    private boolean geprueft;
    private int ticks;
    /** So lange wartet der Entscheid ohne UID, in Ticks: Die Antwort auf show mit der UID kommt nach dem Betreten. */
    private static final int OHNE_UID_NACH = 200;

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
        vorher = a == null ? null : a.welten().get(schluessel);
        ausweis = (a != null ? a : Ausweis.neu(o.getFileName().toString().replaceFirst("^welt-", "")))
                .mit(netz(), server()).besucht(schluessel, Instant.now(), ZoneId.systemDefault());
        alter = -1;
        geschrieben = false;
        geprueft = false;
        ticks = 0;
        // Ein neuer Hash bei gleichem Server und gleicher Welt: Die alten Ordner sind sicher veraltet.
        int geloescht = 0;
        for (Path f : Reset.veraltet(o, ausweis, schluessel)) {
            geloescht += Math.max(0, loesche(mc, f, schluessel, false));
        }
        if (geloescht > 0) {
            melde(mc, "heroicmap.reset.sicher", "Heroic Map: neuer Seed für {}, alte Karten gelöscht: {}", List.of(schluessel, geloescht));
        }
        pruefeUid(mc);
    }

    /** Die Antwort auf show brachte UIDs; sie kommt nach dem Betreten. */
    void uidsGekommen(Minecraft mc) {
        if (ausweis != null) {
            pruefeUid(mc);
        }
    }

    /** Mit UID vom Plugin: eine andere als im Ausweis heisst sicher zurückgesetzt, dann sind die Bäume der Welt weg. */
    private void pruefeUid(Minecraft mc) {
        String uid = Downloads.uids().get(schluessel);
        if (uid == null || geprueft) {
            return;
        }
        geprueft = true;
        if (Reset.pruefe(vorher, uid, -1) != Reset.Art.SICHER) {
            ausweis = ausweis.uid(schluessel, uid);
            return;
        }
        int geloescht = loesche(mc, ordner, schluessel, true);
        // Läuft ein Download, bleibt die alte UID im Ausweis, und der nächste Besuch versucht es wieder.
        if (geloescht >= 0) {
            ausweis = ausweis.neueGeneration(schluessel, alter, uid);
        }
        if (geloescht > 0) {
            melde(mc, "heroicmap.reset.sicher", "Heroic Map: {} in {} mit neuer UID, alte Karten gelöscht: {}", List.of(schluessel, ordner, geloescht));
        }
    }

    /**
     * Löscht die Bäume der Welt {@code schluessel} im Ordner {@code welt}, Downloads und eigene Karten; die Wegpunkte bleiben.
     * Nichts, wenn ein Download in einen läuft; dann versucht es der nächste Besuch. Die eigene Karte der Welt, in der der
     * Spieler ist ({@code hier}), beginnt danach neu im selben Massstab. Gibt zurück, wie viele Bäume gelöscht werden, -1,
     * wenn ein Download läuft.
     */
    private int loesche(Minecraft mc, Path welt, String schluessel, boolean hier) {
        List<Path> baeume = Reset.baeume(welt, schluessel);
        if (!halte(baeume)) {
            return -1;
        }
        Path eigene = welt.resolve(Selbst.baum(schluessel, Downloads.INSTANZ.wer()));
        Satz satz = hier ? Satz.lies(eigene) : null;
        for (Path b : baeume) {
            Kartenliste.loescheBaum(b, ok -> {
                Downloads.INSTANZ.gibFrei(b);
                if (!ok) {
                    LOGGER.warn("Heroic Map: {} nach dem Reset nicht ganz gelöscht", b);
                }
                if (satz != null && b.equals(eigene)) {
                    Selbst.INSTANZ.waehle(mc, satz.massstab());
                }
            });
        }
        return baeume.size();
    }

    /** Ohne UID: Fällt die Spielzeit weit, ruhen die Bäume der Welt unter einem neuen Namen, und eine neue Karte beginnt. */
    private void pruefeOhneUid(Minecraft mc) {
        geprueft = true;
        if (Reset.pruefe(vorher, null, alter) != Reset.Art.UNSICHER) {
            return;
        }
        List<Path> baeume = Reset.baeume(ordner, schluessel);
        if (baeume.isEmpty() || !halte(baeume)) {
            return;
        }
        Path welt = ordner, eigene = welt.resolve(Selbst.baum(schluessel, Downloads.INSTANZ.wer()));
        Satz satz = Satz.lies(eigene);
        long von = vorher.alterZuletzt(), bis = alter;
        Set<String> belegt = new HashSet<>();
        Downloads.INSTANZ.baeume().forEach(b -> belegt.add(b.id()));
        ausweis = ausweis.neueGeneration(schluessel, alter, null);
        Minimap.INSTANZ.leeren();
        Selbst.INSTANZ.leeren();
        Selbst.INSTANZ.nachArbeit(() -> {
            List<String> geruht = new ArrayList<>();
            try (Stream<Path> namen = Files.list(welt)) {
                namen.forEach(p -> belegt.add(p.getFileName().toString()));
                for (Path b : baeume) {
                    String name = Reset.ruhename(b.getFileName().toString(), LocalDate.now(), belegt);
                    belegt.add(name);
                    Path neu = b.resolveSibling(name);
                    Files.move(b, neu, StandardCopyOption.ATOMIC_MOVE);
                    Files.writeString(neu.resolve(Reset.RUHT), "Heroic Map: ruht seit " + LocalDate.now() + ", Spielzeit fiel von " + von
                            + " auf " + bis + "\n", StandardCharsets.UTF_8);
                    geruht.add(name);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Heroic Map: Bäume in {} nach dem Reset nicht ganz ruhend", welt, e);
            }
            mc.execute(() -> {
                baeume.forEach(Downloads.INSTANZ::gibFrei);
                if (satz != null) {
                    Selbst.INSTANZ.waehle(mc, satz.massstab());
                }
                Selbst.INSTANZ.neuGewaehlt(mc);
                HeroicMap.weltOrdnerGewechselt();
                melde(mc, "heroicmap.reset.unsicher", "Heroic Map: {} wohl zurückgesetzt, ruhen: {}", List.of(welt, geruht));
            });
        });
    }

    /** Hält die Schlüssel aller Bäume gegen einen Download; geht einer nicht, gibt es alle frei und sagt nein. */
    private static boolean halte(List<Path> baeume) {
        List<Path> gehalten = new ArrayList<>();
        for (Path b : baeume) {
            if (!Downloads.INSTANZ.halte(b)) {
                gehalten.forEach(Downloads.INSTANZ::gibFrei);
                LOGGER.info("Heroic Map: Reset in {} wartet, ein Download läuft", b);
                return false;
            }
            gehalten.add(b);
        }
        return true;
    }

    /** Eine Zeile im Chat und im Log. */
    private static void melde(Minecraft mc, String schluessel, String log, List<?> was) {
        LOGGER.info(log, was.toArray());
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.translatable(schluessel));
        }
    }

    /** Für den Gametest: ändert den Ausweis der Welt, in der der Spieler ist, im Speicher. */
    void fuerTest(UnaryOperator<Ausweis> aenderung) {
        ausweis = aenderung.apply(ausweis);
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
        if (!geprueft && ++ticks >= OHNE_UID_NACH) {
            pruefeOhneUid(mc);
        }
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
