package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

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
            context.waitFor(mc -> hatKnopf(mc, "heroicmap.liste.ruht"), 600);
            context.clickScreenButton("heroicmap.liste.ruht");
            context.waitTicks(2);
            pruefe(welt, DOWNLOAD, "nach „ruht“ in der Kartenliste");

            // Im Fenster der Karten heisst der Knopf jetzt „Selbst · ruht“; ein Klick zeigt die eigene wieder.
            context.runOnClient(mc -> mc.gui.setScreen(new Auswahl(null)));
            context.waitTicks(2);
            context.clickScreenButton("heroicmap.selbst.ruht");
            context.waitTicks(2);
            pruefe(welt, eigene, "nach „Selbst · ruht“");

            // Esc: Nichts ändert sich, keine Datei.
            Laden.loesche(welt.resolve(eigene));
            Files.deleteIfExists(welt.resolve(Zeigt.DATEI));
            selbst(context);
            context.waitFor(mc -> mc.gui.screen() instanceof AlteKarte, 100);
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitTicks(2);
            if (Files.exists(welt.resolve(eigene)) || Files.exists(welt.resolve(Zeigt.DATEI))) {
                throw new AssertionError("Esc legte eine Karte oder zeigt.txt an");
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
            context.waitFor(mc -> hatKnopf(mc, "heroicmap.liste.zeiger_setzen"), 600);
            context.clickScreenButton("heroicmap.liste.zeiger_setzen");
            context.waitTicks(2);
            if (!"main".equals(Zeiger.name(welt))) {
                throw new AssertionError("Kein Zeiger auf main: " + Zeiger.name(welt));
            }
            pruefe(welt, eigene, "mit Zeiger, ohne Namen erst die eigene");
            context.waitFor(mc -> hatKnopf(mc, "heroicmap.liste.ruht"), 100);
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
            context.runOnClient(mc -> mc.gui.setScreen(null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Hat der Schirm einen Knopf mit diesem Schlüssel? */
    private static boolean hatKnopf(net.minecraft.client.Minecraft mc, String schluessel) {
        return mc.gui.screen() != null && mc.gui.screen().children().stream().anyMatch(w -> w instanceof net.minecraft.client.gui.components.Button b
                && b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().equals(schluessel));
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
