package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * „Ort …“ in der Kartenliste wie ein Spieler, mit Maus und Tasten: Der ganze Ordner einer Welt ohne Server zieht nach
 * {@code karte-build}, Wegpunkte und eigene Karte mit. „Nur diese Karte“ auf einen Baum, der dort schon liegt, ist grau, auf
 * einen freien nicht; Esc bricht ab, und nichts zieht um. Dann zieht nur die Karte in den Ordner eines neuen Hashs. Eine Karte
 * aus dem Einzelspieler zieht nicht um. Siehe docs/download.md, „Ort ändern“.
 */
public final class OrtAendern implements FabricClientGameTest {

    private static final String OBEN = "minecraft:overworld", ADRESSE = "ort-probe.example.net";
    private static final String HASH = "3fa1c2d4e5f60718", NEU = "0123456789abcdef";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext spiel = context.worldBuilder().create(); var _ = Abschluss.langsamerTakt(context, spiel)) {
            spiel.getConnection().waitForChunksRender();
            Bilder.leereWelt(context);
            Path adresse = Downloads.wurzel().resolve(ADRESSE);
            if (Files.exists(adresse)) {
                Laden.loesche(adresse);
            }
            // Zwei Ordner derselben Welt: ohne Server mit Download, eigener Karte und Wegpunkten, und der von main.
            Path ohne = adresse.resolve("welt-" + HASH), main = adresse.resolve("karte-main/welt-" + HASH);
            Path build = adresse.resolve("karte-build/welt-" + HASH);
            download(ohne.resolve("probe"));
            Path eigene = Selbst.anlegen(ohne, OBEN, 4);
            Files.writeString(ohne.resolve("wegpunkte.json"), "{\"wegpunkte\":[]}");
            Files.writeString(ohne.resolve(Zeigt.DATEI), "- " + OBEN + " " + eigene.getFileName() + "\n");
            download(main.resolve("probe"));
            Files.writeString(main.resolve(Zeigt.DATEI), "main " + OBEN + " probe\n");
            Path hier = context.computeOnClient(mc -> Downloads.weltOrdner());
            download(hier.resolve("probe-hier"));

            // Aus dem Einzelspieler: „Ort …“ grau.
            liste(context);
            Button grau = ort(context, hier.resolve("probe-hier"));
            if (context.computeOnClient(mc -> grau.active)) {
                throw new AssertionError("„Ort …“ einer Karte aus dem Einzelspieler ist nicht grau");
            }

            // Ganzer Ordner, die Vorgabe: Am jetzigen Ort ist „Umziehen“ grau; Server build, „Umziehen“.
            liste(context);
            fenster(context, ohne.resolve("probe"));
            aktiv(context, false, "am jetzigen Ort");
            tippe(context, 1, "build");
            aktiv(context, true, "mit Server build");
            klicke(context, knopf(context, "heroicmap.ort.umziehen"));
            context.waitFor(mc -> mc.gui.screen() instanceof Kartenliste && !Files.exists(ohne), 200);
            Path eigeneNeu = build.resolve(Selbst.baum(OBEN, "build"));
            if (!Files.exists(build.resolve("wegpunkte.json")) || !Files.exists(build.resolve("probe/satz.json"))) {
                throw new AssertionError("Wegpunkte oder Download zogen nicht mit");
            }
            if (!Selbst.gehoert(eigeneNeu, "build") || !eigeneNeu.getFileName().toString().equals(Zeigt.baum(build, "build", OBEN))) {
                throw new AssertionError("Die eigene Karte gehört nicht build, oder zeigt.txt sagt es nicht");
            }

            // Nur diese Karte, von main nach build: Dort liegt probe schon, „Umziehen“ grau; nach build2 nicht. Esc bricht ab.
            liste(context);
            fenster(context, main.resolve("probe"));
            klicke(context, knopf(context, "heroicmap.ort.karte"));
            context.waitTicks(2);
            tippe(context, 1, "build");
            aktiv(context, false, "auf einen Baum, der dort liegt");
            tippe(context, 1, "build2");
            aktiv(context, true, "auf einen freien Ort");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitFor(mc -> mc.gui.screen() instanceof Kartenliste, 100);
            if (!Files.exists(main.resolve("probe/satz.json")) || Files.exists(adresse.resolve("karte-build2"))) {
                throw new AssertionError("Esc zog die Karte um");
            }

            // Nur diese Karte, in den Ordner eines neuen Hashs: Sie zieht um, ihre Zeile in zeigt.txt fällt weg.
            liste(context);
            fenster(context, main.resolve("probe"));
            klicke(context, knopf(context, "heroicmap.ort.karte"));
            context.waitTicks(2);
            tippe(context, 2, NEU);
            aktiv(context, true, "mit neuem Hash");
            klicke(context, knopf(context, "heroicmap.ort.umziehen"));
            Path neu = adresse.resolve("karte-main/welt-" + NEU + "/probe");
            context.waitFor(mc -> mc.gui.screen() instanceof Kartenliste && Files.exists(neu.resolve("satz.json")), 200);
            if (Files.exists(main.resolve("probe")) || Zeigt.baum(main, "main", OBEN) != null) {
                throw new AssertionError("Die Karte liegt noch am alten Ort, oder zeigt.txt nennt sie dort noch");
            }
            if (!Files.exists(main.resolve(Zeigt.DATEI))) {
                throw new AssertionError("Der ganze Ordner zog um, nicht nur die Karte");
            }
            groessen(context, build.resolve("probe"));
            Laden.loesche(adresse);
            context.runOnClient(mc -> mc.gui.setScreen(null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Das Fenster zu {@code baum} bei 1920 × 1080 mit GS 3 und 4, 1280 × 720 mit GS 3 und 1280 × 960 mit GS 4, also 320
     * Einheiten breit: Knöpfe und Felder ganz auf dem Schirm, nichts übereinander, die Hinweise rechts der Felder auch.
     * Je ein Bildschirmfoto. Danach das Fenster wie vorher.
     */
    private static void groessen(ClientGameTestContext context, Path baum) {
        int[] vorher = context.computeOnClient(mc -> new int[] {mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.options.guiScale().get()});
        List<String> fehler = new ArrayList<>();
        try {
            for (int[] f : new int[][] {{1920, 1080, 3}, {1920, 1080, 4}, {1280, 720, 3}, {1280, 960, 4}}) {
                context.getInput().resizeWindow(f[0], f[1]);
                context.runOnClient(mc -> {
                    mc.options.guiScale().set(f[2]);
                    mc.resizeGui();
                    Laden.AufPlatte karte = new Laden.AufPlatte(baum, baum.toString(), Satz.lies(baum), 0);
                    mc.gui.setScreen(new OrtFenster(new Kartenliste(null), karte, Ort.teile(Downloads.wurzel(), baum.getParent())));
                });
                context.waitTicks(3);
                String fall = f[0] + " × " + f[1] + " GS " + f[2];
                context.takeScreenshot(TestScreenshotOptions.of("ort-" + f[1] + "-gs" + f[2]).disableCounterPrefix());
                fehler.addAll(context.computeOnClient(mc -> {
                    int b = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
                    List<AbstractWidget> l = mc.gui.screen().children().stream().filter(c -> c instanceof AbstractWidget)
                            .map(c -> (AbstractWidget) c).toList();
                    List<String> fl = new ArrayList<>();
                    if (mc.getWindow().getGuiScale() != f[2]) {
                        fl.add(fall + ": GS " + mc.getWindow().getGuiScale());
                    }
                    int hinweis = Math.max(mc.font.width(Component.translatable("heroicmap.ort.server.hinweis")),
                            mc.font.width(Component.translatable("heroicmap.ort.hash.hinweis")));
                    for (int i = 0; i < l.size(); i++) {
                        AbstractWidget w = l.get(i);
                        int rechts = w.getX() + w.getWidth() + (w instanceof EditBox ? 6 + hinweis : 0);
                        if (w.getX() < 0 || w.getY() < 0 || rechts > b || w.getY() + w.getHeight() > h) {
                            fl.add(fall + ": " + w.getMessage().getString() + " nicht ganz auf dem Schirm");
                        }
                        for (int j = i + 1; j < l.size(); j++) {
                            AbstractWidget v = l.get(j);
                            if (w.getX() < v.getX() + v.getWidth() && v.getX() < w.getX() + w.getWidth() && w.getY() < v.getY() + v.getHeight()
                                    && v.getY() < w.getY() + w.getHeight()) {
                                fl.add(fall + ": " + w.getMessage().getString() + " über " + v.getMessage().getString());
                            }
                        }
                    }
                    return fl;
                }));
            }
        } finally {
            context.getInput().resizeWindow(vorher[0], vorher[1]);
            context.runOnClient(mc -> {
                mc.options.guiScale().set(vorher[2]);
                mc.resizeGui();
            });
            context.waitTicks(2);
        }
        if (!fehler.isEmpty()) {
            throw new AssertionError(String.join("; ", fehler));
        }
    }

    /** Eine frische Kartenliste, oben, nach dem Zählen. */
    private static void liste(ClientGameTestContext context) {
        context.runOnClient(mc -> mc.gui.setScreen(new Kartenliste(null)));
        context.waitFor(mc -> !orte(mc).isEmpty(), 600);
    }

    /** Die Knöpfe „Ort …“ der Liste, von oben. */
    private static List<Button> orte(Minecraft mc) {
        return mc.gui.screen() instanceof Kartenliste l ? l.children().stream()
                .filter(w -> w instanceof Button b && schluessel(b, "heroicmap.liste.ort")).map(w -> (Button) w).toList() : List.of();
    }

    /** Blättert mit dem Mausrad, bis die Zeile des Baums zu sehen ist, und gibt ihr „Ort …“. Die Liste ist frisch, oben. */
    private static Button ort(ClientGameTestContext context, Path baum) throws IOException {
        List<Path> baeume = Laden.bestand(Downloads.wurzel(), () -> false).karten().stream().map(Laden.AufPlatte::ordner).toList();
        int i = baeume.indexOf(baum);
        if (i < 0) {
            throw new AssertionError("Nicht in der Kartenliste: " + baum);
        }
        int runter = Math.max(0, i - context.computeOnClient(mc -> orte(mc).size()) + 1);
        context.getInput().setCursorPos(10, 10);
        for (int s = 0; s < runter; s++) {
            context.getInput().scroll(-1);
            context.waitTick();
        }
        // Geblättert beginnt die Liste mit der Zeile runter.
        return context.computeOnClient(mc -> orte(mc).get(i - runter));
    }

    /** Klickt auf „Ort …“ in der Zeile des Baums und wartet auf das Fenster. */
    private static void fenster(ClientGameTestContext context, Path baum) throws IOException {
        klicke(context, ort(context, baum));
        context.waitFor(mc -> mc.gui.screen() instanceof OrtFenster, 100);
    }

    private static boolean schluessel(Button b, String s) {
        return b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(s);
    }

    /** Der Knopf mit diesem Schlüssel auf dem Schirm. */
    private static Button knopf(ClientGameTestContext context, String s) {
        return context.computeOnClient(mc -> mc.gui.screen().children().stream().filter(w -> w instanceof Button b && schluessel(b, s))
                .map(w -> (Button) w).findFirst().orElseThrow(() -> new AssertionError("Kein Knopf " + s)));
    }

    private static void aktiv(ClientGameTestContext context, boolean soll, String wann) {
        context.waitTicks(2);
        if (context.computeOnClient(mc -> knopf(context, "heroicmap.ort.umziehen").active) != soll) {
            throw new AssertionError("„Umziehen“ " + (soll ? "grau " : "nicht grau ") + wann);
        }
    }

    /** Klickt mit der Maus in die Mitte des Knopfs. */
    private static void klicke(ClientGameTestContext context, AbstractWidget w) {
        double[] mitte = context.computeOnClient(mc -> new double[] {w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0});
        maus(context, mitte[0], mitte[1]);
    }

    private static void maus(ClientGameTestContext context, double x, double y) {
        int k = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
        context.getInput().setCursorPos(x * k, y * k);
        context.waitTick();
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTick();
    }

    /** Klickt ans rechte Ende des Felds {@code i} im Fenster „Ort ändern“, löscht seinen Inhalt mit der Rücktaste und tippt {@code text}. */
    private static void tippe(ClientGameTestContext context, int i, String text) {
        EditBox f = context.computeOnClient(mc -> mc.gui.screen().children().stream().filter(w -> w instanceof EditBox)
                .map(w -> (EditBox) w).toList().get(i));
        double[] ende = context.computeOnClient(mc -> new double[] {f.getX() + f.getWidth() - 3, f.getY() + f.getHeight() / 2.0});
        maus(context, ende[0], ende[1]);
        int n = context.computeOnClient(mc -> f.getValue().length());
        for (int k = 0; k < n; k++) {
            context.getInput().pressKey(InputConstants.KEY_BACKSPACE);
        }
        context.getInput().typeChars(text);
        context.waitTick();
    }

    private static void download(Path baum) throws IOException {
        Satz.schreibe(baum, "Probe", OBEN, 4);
        Path ordner = Files.createDirectories(baum.resolve("4"));
        Files.writeString(ordner.resolve("map.json"), "{\"tileSize\":256,\"scale\":4,\"minZoom\":0,\"maxZoom\":8}");
    }
}
