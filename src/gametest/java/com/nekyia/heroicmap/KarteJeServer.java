package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * „Selbst“ über einem Download, wie ein Spieler: die Frage nach der alten Karte mit „Behalten, ruht“, Esc und
 * „Löschen“, dann „ruht“ in der Kartenliste und „Selbst · ruht“ im Fenster der Karten. Im Einzelspieler, also für
 * einen Server ohne Namen; die Regeln je Server prüft KarteJeServerTest. Siehe docs/selbst.md, „Wahl“.
 */
public final class KarteJeServer implements FabricClientGameTest {

    private static final String OBEN = "minecraft:overworld", DOWNLOAD = "probe-download";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext spiel = context.worldBuilder().create(); var _ = Abschluss.langsamerTakt(context, spiel)) {
            spiel.getConnection().waitForChunksRender();
            // Reste früherer Läufe in diesem Ordner weg, dann ein Download der Oberwelt wie vom Server.
            Bilder.leereWelt(context);
            Path welt = context.computeOnClient(mc -> Downloads.weltOrdner());
            download(welt);

            // Behalten: Die eigene Karte ist an, der Download ruht und bleibt auf der Platte.
            selbst(context);
            context.waitFor(mc -> mc.gui.screen() instanceof AlteKarte, 100);
            context.clickScreenButton("heroicmap.alte.behalten_ohne");
            context.waitTicks(2);
            String eigene = Selbst.baum(OBEN);
            pruefe(welt, eigene, "nach „Behalten, ruht“");
            if (!Files.isDirectory(welt.resolve(DOWNLOAD)) || !Downloads.ruht(welt.resolve(DOWNLOAD), Zeigt.OHNE)) {
                throw new AssertionError("Der Download ruht nicht oder ist weg");
            }

            // In der Kartenliste den Download wieder an: Dann ruht die eigene Karte.
            context.runOnClient(mc -> mc.gui.setScreen(new Kartenliste(null)));
            context.waitFor(mc -> knopf(mc, "heroicmap.liste.ruht") != null, 600);
            context.clickScreenButton("heroicmap.liste.ruht");
            context.waitTicks(2);
            pruefe(welt, DOWNLOAD, "nach „ruht“ in der Kartenliste");

            // Im Fenster der Karten heisst der Knopf jetzt „Selbst · ruht“; ein Klick zeigt die eigene wieder.
            context.runOnClient(mc -> mc.gui.setScreen(new Auswahl(null)));
            context.waitTicks(2);
            klicke(context, "heroicmap.selbst.ruht");
            context.waitTicks(2);
            pruefe(welt, eigene, "nach „Selbst · ruht“");

            // Esc: Nichts ändert sich, keine Datei. Die eigene Karte löscht der Worker, nach dem, was er noch in sie
            // zeichnet; von hier aus gelöscht, legte er sie wieder an.
            AtomicBoolean weg = new AtomicBoolean();
            context.runOnClient(mc -> Selbst.INSTANZ.loesche(welt.resolve(eigene), weg::set));
            context.waitFor(mc -> weg.get(), 600);
            Files.deleteIfExists(welt.resolve(Zeigt.DATEI));
            selbst(context);
            context.waitFor(mc -> mc.gui.screen() instanceof AlteKarte, 100);
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitTicks(2);
            if (Files.exists(welt.resolve(eigene))) {
                throw new AssertionError("Esc legte eine eigene Karte an");
            }
            if (Files.exists(welt.resolve(Zeigt.DATEI))) {
                throw new AssertionError("Esc schrieb zeigt.txt");
            }
            pruefe(welt, DOWNLOAD, "nach Esc");

            // Löschen: Der Download geht von der Platte, dann ist die eigene Karte an.
            context.clickScreenButton("heroicmap.selbst.knopf");
            context.waitFor(mc -> mc.gui.screen() instanceof AlteKarte, 100);
            context.clickScreenButton("heroicmap.alte.loeschen");
            context.waitFor(mc -> !Files.exists(welt.resolve(DOWNLOAD)) && Files.exists(welt.resolve(eigene)), 600);
            pruefe(welt, eigene, "nach „Löschen“");

            // Der Zeiger: Neben diesem Ordner liegt dieselbe Welt von main mit einem Download. Unten in der Kartenliste
            // „Karte von main nutzen“, dann den Download dort an, dann „lösen“. Siehe docs/download.md, „Kartenliste“.
            download(Zeiger.ordner(welt, "main"), "probe-main");
            context.runOnClient(mc -> mc.gui.setScreen(new Kartenliste(null)));
            context.waitFor(mc -> knopf(mc, "heroicmap.liste.zeiger_setzen") != null, 600);
            klicke(context, "heroicmap.liste.zeiger_setzen");
            context.waitTicks(2);
            if (!"main".equals(Zeiger.name(welt))) {
                throw new AssertionError("Kein Zeiger auf main: " + Zeiger.name(welt));
            }
            pruefe(welt, eigene, "mit Zeiger, ohne Namen erst die eigene");
            context.waitFor(mc -> knopf(mc, "heroicmap.liste.ruht") != null, 100);
            context.clickScreenButton("heroicmap.liste.ruht");
            context.waitTicks(2);
            pruefe(welt, "probe-main", "nach „ruht“ beim Download im Ziel");
            if (!Files.exists(Zeiger.ordner(welt, "main").resolve("probe-main")) || Files.exists(Zeiger.ordner(welt, "main").resolve(eigene))) {
                throw new AssertionError("Im Ziel fehlt der Download, oder die eigene Karte liegt dort");
            }
            context.clickScreenButton("heroicmap.liste.zeiger_loesen");
            context.waitTicks(2);
            if (Zeiger.name(welt) != null) {
                throw new AssertionError("Der Zeiger ist nach „lösen“ noch da");
            }
            pruefe(welt, eigene, "nach „lösen“");

            netz(context, welt, eigene);
            kopie(context, welt, eigene);
            context.runOnClient(mc -> mc.gui.setScreen(null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * „Kopie“: Die Karte von main bekommt Kacheln aus dem Testsatz. Solange es die eigene Karte gibt, ist „Kopie“ grau. Nach
     * dem Löschen macht ein Klick die Karte von main zur eigenen, und dieser Server zeigt sie. „Selbst“ zeichnet die geladenen
     * Chunks darüber als PNG; deren WebP-Links in der Kopie sind weg, die WebP von main sind wie im Testsatz. Siehe
     * docs/selbst.md, „Kopie“.
     */
    private static void kopie(ClientGameTestContext context, Path welt, String eigene) throws IOException {
        Path main = Zeiger.ordner(welt, "main").resolve("probe-main");
        Bilder.testsatz(main.resolve("4"));
        context.runOnClient(mc -> mc.gui.setScreen(new Kartenliste(null)));
        context.waitFor(mc -> knopf(mc, "heroicmap.liste.kopie") != null, 600);
        if (context.computeOnClient(mc -> knopf(mc, "heroicmap.liste.kopie").active)) {
            throw new AssertionError("„Kopie“ nicht grau, obwohl es die eigene Karte gibt");
        }
        AtomicBoolean weg = new AtomicBoolean();
        context.runOnClient(mc -> Selbst.INSTANZ.loesche(welt.resolve(eigene), weg::set));
        context.waitFor(mc -> weg.get(), 600);
        context.runOnClient(mc -> mc.gui.setScreen(new Kartenliste(null)));
        context.waitFor(mc -> knopf(mc, "heroicmap.liste.kopie") != null && knopf(mc, "heroicmap.liste.kopie").active, 600);
        klicke(context, "heroicmap.liste.kopie");
        Path kopie = welt.resolve(eigene);
        // Erst die Datei, dann eine eigene Meldung: Ein Warten bis zur Zeitgrenze zählte als Fang nicht.
        context.waitFor(mc -> Files.exists(kopie.resolve("satz.json")), 600);
        context.waitTicks(20);
        if (!Selbst.gehoert(kopie, Zeigt.OHNE)) {
            throw new AssertionError("Die Kopie ist keine eigene Karte");
        }
        if (!eigene.equals(Zeigt.baum(welt, Zeigt.OHNE, OBEN))) {
            throw new AssertionError("Dieser Server zeigt die Kopie nicht: " + Zeigt.baum(welt, Zeigt.OHNE, OBEN));
        }
        pruefe(welt, eigene, "nach „Kopie“");
        // Selbst zeichnet die geladenen Chunks über die Kopie.
        Path stufe = kopie.resolve("4/2");
        context.waitFor(mc -> !pngs(stufe).isEmpty(), 2400);
        for (Path png : pngs(stufe)) {
            String rel = stufe.relativize(png).toString().replace('\\', '/').replace(".png", ".webp");
            if (Files.exists(stufe.resolve(rel))) {
                throw new AssertionError("Neben der neuen " + png + " liegt noch der Link auf die WebP");
            }
            byte[] vorher;
            try (var rein = Bilder.class.getResourceAsStream("/satz/4/2/" + rel)) {
                vorher = rein == null ? null : rein.readAllBytes();
            }
            if (vorher != null && !java.util.Arrays.equals(vorher, Files.readAllBytes(main.resolve("4/2/" + rel)))) {
                throw new AssertionError("Die WebP von main hat sich geändert: " + rel);
            }
        }
    }

    /** Die PNG-Kacheln unter {@code stufe}; die selbst gezeichneten. */
    private static java.util.List<Path> pngs(Path stufe) {
        try (var alle = Files.walk(stufe)) {
            return alle.filter(p -> p.toString().endsWith(".png")).toList();
        } catch (IOException e) {
            return java.util.List.of();
        }
    }

    /**
     * Dieselbe Welt unter einer anderen Adresse: Unten in der Kartenliste steht neben „Karte von main nutzen“ auch „Karte von
     * play.example.net nutzen“. Ein Klick setzt den Zeiger, dann den Download dort an, dann „lösen“. Siehe docs/download.md,
     * „Kartenliste“.
     */
    private static void netz(ClientGameTestContext context, Path welt, String eigene) throws IOException {
        Path dort = welt.getParent().getParent().resolve("play.example.net").resolve(welt.getFileName().toString());
        Laden.loesche(dort);
        download(dort, "probe-netz");
        context.runOnClient(mc -> mc.gui.setScreen(new Kartenliste(null)));
        context.waitFor(mc -> knopf(mc, "heroicmap.liste.zeiger_setzen", "play.example.net") != null, 600);
        klicke(context, "heroicmap.liste.zeiger_setzen", "play.example.net");
        context.waitTicks(2);
        if (!"@play.example.net".equals(Zeiger.name(welt))) {
            throw new AssertionError("Kein Zeiger auf play.example.net: " + Zeiger.name(welt));
        }
        pruefe(welt, eigene, "mit Zeiger über Adressen, ohne Namen erst die eigene");
        context.waitFor(mc -> knopf(mc, "heroicmap.liste.ruht") != null, 100);
        context.clickScreenButton("heroicmap.liste.ruht");
        context.waitTicks(2);
        pruefe(welt, "probe-netz", "nach „ruht“ beim Download unter der anderen Adresse");
        context.clickScreenButton("heroicmap.liste.zeiger_loesen");
        context.waitTicks(2);
        if (Zeiger.name(welt) != null) {
            throw new AssertionError("Der Zeiger über Adressen ist nach „lösen“ noch da");
        }
        pruefe(welt, eigene, "nach „lösen“ über Adressen");
        Laden.loesche(dort);
    }

    /** Der Knopf mit diesem Schlüssel auf dem Schirm, mit {@code args} unter seinen Argumenten, sonst null. */
    private static Button knopf(Minecraft mc, String schluessel, Object... args) {
        return mc.gui.screen() == null ? null : mc.gui.screen().children().stream()
                .filter(w -> w instanceof Button b && b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(schluessel)
                        && java.util.Arrays.asList(t.getArgs()).containsAll(java.util.List.of(args)))
                .map(w -> (Button) w).findFirst().orElse(null);
    }

    /**
     * Klickt mit der Maus auf den Knopf mit diesem Schlüssel und {@code args}. clickScreenButton vergleicht den Text ohne
     * Argumente und findet einen Knopf wie „Selbst · ruht · 4 px“ nicht.
     */
    private static void klicke(ClientGameTestContext context, String schluessel, Object... args) {
        double[] mitte = context.computeOnClient(mc -> {
            Button b = knopf(mc, schluessel, args);
            if (b == null) {
                throw new AssertionError("Kein Knopf " + schluessel);
            }
            return new double[] {b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0};
        });
        int k = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
        context.getInput().setCursorPos(mitte[0] * k, mitte[1] * k);
        context.waitTick();
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
    }

    /** Das Fenster der Karten und darin „Selbst“, mit dem Massstab der Vorgabe. */
    private static void selbst(ClientGameTestContext context) {
        context.runOnClient(mc -> mc.gui.setScreen(new Auswahl(null)));
        context.waitTicks(2);
        context.clickScreenButton("heroicmap.selbst.knopf");
    }

    private static void download(Path welt) throws IOException {
        download(welt, DOWNLOAD);
    }

    private static void download(Path welt, String baum) throws IOException {
        Satz.schreibe(welt.resolve(baum), "Probe", OBEN, 4);
        Path ordner = Files.createDirectories(welt.resolve(baum).resolve("4"));
        Files.writeString(ordner.resolve("map.json"), "{\"tileSize\":256,\"scale\":4,\"minZoom\":0,\"maxZoom\":8}");
    }

    private static void pruefe(Path welt, String baum, String wann) {
        Satz s = Satz.fuer(welt, OBEN, Zeigt.OHNE);
        String gezeigt = s == null ? null : s.ordner().getParent().getFileName().toString();
        if (!baum.equals(gezeigt)) {
            throw new AssertionError("Gezeigt " + wann + ": " + gezeigt + " statt " + baum);
        }
    }
}
