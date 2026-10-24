package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * „Ort …“ in der Kartenliste wie ein Spieler, mit Maus und Tasten: Der ganze Ordner einer Welt ohne Server zieht nach
 * {@code karte-build}, Wegpunkte und eigene Karte mit. „Nur diese Karte“ auf einen Baum, der dort schon liegt, ist grau, auf
 * einen freien nicht; Esc bricht ab, und nichts zieht um. Dann zieht nur die Karte in den Ordner eines neuen Hashs, und der
 * Ordner von build in den Einzelspieler und zurück. In der Welt des Spielers sind ganzer Ordner und gezeigter Download grau.
 * Siehe docs/download.md, „Ort ändern“.
 */
public final class OrtAendern implements FabricClientGameTest {

    private static final String OBEN = "minecraft:overworld", ADRESSE = "ort-probe.example.net";
    private static final String HASH = "3fa1c2d4e5f60718", NEU = "0123456789abcdef";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext spiel = context.worldBuilder().create(); var _ = Abschluss.langsamerTakt(context, spiel)) {
            spiel.getConnection().waitForChunksRender();
            Bilder.leereWelt(context);
            Path adresse = Downloads.wurzel().resolve(ADRESSE), einzel = Downloads.wurzel().resolve("einzelspieler_ort_probe");
            Path alteAblage = adresse.resolveSibling(ADRESSE + "_25565");
            for (Path rest : new Path[] {adresse, einzel, alteAblage}) {
                if (Files.exists(rest)) {
                    Laden.loesche(rest);
                }
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

            // Die Welt, in der der Spieler ist, aus dem Einzelspieler: Das Ziel ist vorgewählt. Als ganzer Ordner grau, weil der
            // Spieler darin ist; nur der Download grau, weil Minimap und Vollbildkarte ihn zeigen.
            liste(context);
            fenster(context, hier.resolve("probe-hier"));
            if (context.computeOnClient(mc -> knopf(context, "heroicmap.ort.ziel.einzelspieler").active)) {
                throw new AssertionError("Bei einer Karte aus dem Einzelspieler ist das Ziel Einzelspieler nicht vorgewählt");
            }
            tippe(context, 0, "anderer");
            grund(context, "heroicmap.ort.hier", "ganzer Ordner der Welt, in der der Spieler ist");
            klicke(context, knopf(context, "heroicmap.ort.karte"));
            context.waitTicks(2);
            grund(context, "heroicmap.ort.gezeigt", "der gezeigte Download");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitFor(mc -> mc.gui.screen() instanceof Kartenliste, 100);

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

            pfade(context, build.resolve("probe"));
            ausweis(context, spiel, hier);
            ruhen(context, spiel, hier);

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
            // Ganzer Ordner von build in den Einzelspieler: Speicherstand statt Adresse und Server. Die eigene Karte gehört danach
            // keinem Server. Dann zurück nach build.
            liste(context);
            fenster(context, build.resolve("probe"));
            klicke(context, knopf(context, "heroicmap.ort.ziel.einzelspieler"));
            context.waitTicks(2);
            tippe(context, 0, "Ort Probe");
            aktiv(context, true, "in den Einzelspieler");
            klicke(context, knopf(context, "heroicmap.ort.umziehen"));
            Path einzelWelt = einzel.resolve("welt-" + HASH);
            context.waitFor(mc -> mc.gui.screen() instanceof Kartenliste && !Files.exists(build), 200);
            if (!Files.exists(einzelWelt.resolve("wegpunkte.json")) || !Selbst.gehoert(einzelWelt.resolve(Selbst.baum(OBEN, Zeigt.OHNE)), Zeigt.OHNE)) {
                throw new AssertionError("Im Einzelspieler fehlen die Wegpunkte, oder die eigene Karte gehört noch einem Server");
            }
            liste(context);
            fenster(context, einzelWelt.resolve("probe"));
            klicke(context, knopf(context, "heroicmap.ort.ziel.server"));
            context.waitTicks(2);
            tippe(context, 0, ADRESSE);
            tippe(context, 1, "build");
            aktiv(context, true, "zurück auf den Server");
            klicke(context, knopf(context, "heroicmap.ort.umziehen"));
            context.waitFor(mc -> mc.gui.screen() instanceof Kartenliste && !Files.exists(einzel), 200);
            if (!Files.exists(build.resolve("probe/satz.json")) || !Selbst.gehoert(build.resolve(Selbst.baum(OBEN, "build")), "build")) {
                throw new AssertionError("Zurück auf dem Server fehlt der Download, oder die eigene Karte gehört nicht build");
            }

            doppelt(context, adresse.resolve("karte-main/welt-" + HASH + "/top"), alteAblage.resolve("karte-main/welt-" + HASH + "/top"));
            groessen(context, build.resolve("probe"));
            Laden.loesche(adresse);
            Laden.loesche(alteAblage);
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
                    Laden.AufPlatte karte = new Laden.AufPlatte(baum, baum.toString(), Satz.lies(baum), 0, 0);
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

    /**
     * Der Pfad des Baums in der Kartenliste bei 854 × 480 mit GS 2 und 1280 × 960 mit GS 4: in der Mitte gekürzt, vorn die
     * Adresse, soweit Platz ist, hinten der Baum, und schmal genug für die Zeile. Mit der Maus über dem Pfad zeigt ein Tooltip
     * ihn ganz, gesehen im Bildschirmfoto. Danach das Fenster wie vorher. Siehe docs/download.md, „Kartenliste“.
     */
    private static void pfade(ClientGameTestContext context, Path baum) throws IOException {
        int[] vorher = context.computeOnClient(mc -> new int[] {mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.options.guiScale().get()});
        Laden.AufPlatte e = Laden.bestand(Downloads.wurzel(), () -> false).karten().stream().filter(k -> k.ordner().equals(baum)).findFirst()
                .orElseThrow(() -> new AssertionError("Nicht in der Kartenliste: " + baum));
        try {
            for (int[] f : new int[][] {{854, 480, 2}, {1280, 960, 4}}) {
                context.getInput().resizeWindow(f[0], f[1]);
                context.runOnClient(mc -> {
                    mc.options.guiScale().set(f[2]);
                    mc.resizeGui();
                });
                liste(context);
                Button knopf = ort(context, baum);
                String fall = "GS " + f[2];
                // Die Zeile reicht bis 16 Einheiten vor „Ort …“.
                String[] text = context.computeOnClient(mc -> new String[] {((Kartenliste) mc.gui.screen()).pfad(e),
                        String.valueOf(knopf.getX() - 16 - mc.font.width(((Kartenliste) mc.gui.screen()).pfad(e)))});
                if (text[0].equals(e.pfad()) || !text[0].contains("…") || !text[0].endsWith("/probe") || Integer.parseInt(text[1]) < 0) {
                    throw new AssertionError(fall + ": Pfad „" + text[0] + "“ nicht in der Mitte gekürzt, ohne Baum oder zu breit");
                }
                if (f[2] == 2 && !text[0].startsWith("ort-probe")) {
                    throw new AssertionError(fall + ": Pfad „" + text[0] + "“ ohne Adresse vorn");
                }
                int k = f[2], y = context.computeOnClient(mc -> knopf.getY());
                context.getInput().setCursorPos(1, 1);
                context.waitTicks(2);
                Path ohne = context.takeScreenshot(TestScreenshotOptions.of("pfad-gs" + k + "-ohne").disableCounterPrefix());
                context.getInput().setCursorPos(12 * k, (y + 5) * k);
                context.waitTicks(2);
                Path mit = context.takeScreenshot(TestScreenshotOptions.of("pfad-gs" + k).disableCounterPrefix());
                int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
                double tooltip = unterschied(ohne, mit, k, new int[] {0, Math.max(0, y - 24), breite, y + 48});
                if (tooltip < 100) {
                    throw new AssertionError(fall + ": kein Tooltip mit dem ganzen Pfad, " + tooltip + " Einheiten anders");
                }
            }
        } finally {
            context.getInput().resizeWindow(vorher[0], vorher[1]);
            context.runOnClient(mc -> {
                mc.options.guiScale().set(vorher[2]);
                mc.resizeGui();
            });
            context.waitTicks(2);
        }
    }

    /** Wie breit in Einheiten der Bereich ist, in dem sich zwei Bildschirmfotos im Kasten {x0, y0, x1, y1} unterscheiden. */
    private static double unterschied(Path a, Path b, int k, int[] kasten) {
        try {
            BufferedImage x = ImageIO.read(a.toFile()), y = ImageIO.read(b.toFile());
            int links = Integer.MAX_VALUE, rechts = Integer.MIN_VALUE;
            for (int py = kasten[1] * k; py < Math.min(kasten[3] * k, x.getHeight()); py++) {
                for (int px = kasten[0] * k; px < Math.min(kasten[2] * k, x.getWidth()); px++) {
                    if ((x.getRGB(px, py) & 0xFFFFFF) != (y.getRGB(px, py) & 0xFFFFFF)) {
                        links = Math.min(links, px);
                        rechts = Math.max(rechts, px);
                    }
                }
            }
            return rechts < links ? 0 : (rechts - links + 1) / (double) k;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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

    /**
     * Zwei gleiche Bäume unter {@code <host>} und {@code <host>_25565}, die Ablage ist IP: Die Zeile des ungenutzten zeigt gelb
     * „doppelt“, die des genutzten nicht. Mit der Maus über ihrer zweiten Zeile zeigt bei beiden ein Tooltip die andere.
     * „Löschen“ mit Rückfrage nimmt den ungenutzten weg. Gesehen im Bildschirmfoto. Siehe docs/download.md, „Kartenliste“.
     */
    private static void doppelt(ClientGameTestContext context, Path genutzt, Path ungenutzt) throws IOException {
        download(genutzt);
        download(ungenutzt);
        int k = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
        for (Path baum : new Path[] {genutzt, ungenutzt}) {
            liste(context);
            Button zeile = ort(context, baum);
            int y = context.computeOnClient(mc -> zeile.getY()), platz = context.computeOnClient(mc -> zeile.getX() - 16);
            context.getInput().setCursorPos(1, 1);
            context.waitTicks(2);
            String name = baum == genutzt ? "genutzt" : "ungenutzt";
            Path ohne = context.takeScreenshot(TestScreenshotOptions.of("doppelt-" + name).disableCounterPrefix());
            int gelb = gelb(ohne, k, new int[] {8, y + 10, 8 + platz, y + 20});
            if ((gelb > 0) != (baum == ungenutzt)) {
                throw new AssertionError("„doppelt“ " + (gelb > 0 ? "an der genutzten Karte" : "fehlt an der ungenutzten") + ", " + gelb + " gelbe Pixel");
            }
            context.getInput().setCursorPos(12 * k, (y + 15) * k);
            context.waitTicks(2);
            Path mit = context.takeScreenshot(TestScreenshotOptions.of("doppelt-" + name + "-tooltip").disableCounterPrefix());
            int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
            if (unterschied(ohne, mit, k, new int[] {0, Math.max(0, y - 30), breite, y + 70}) < 100) {
                throw new AssertionError(name + ": kein Tooltip mit der anderen Karte");
            }
        }
        // Löschen mit Rückfrage, wie ein Spieler.
        liste(context);
        Button zeile = ort(context, ungenutzt);
        Button loeschen = context.computeOnClient(mc -> mc.gui.screen().children().stream()
                .filter(w -> w instanceof Button b && schluessel(b, "heroicmap.liste.loeschen") && b.getY() == zeile.getY())
                .map(w -> (Button) w).findFirst().orElseThrow(() -> new AssertionError("Kein „Löschen“ in der Zeile")));
        klicke(context, loeschen);
        context.waitFor(mc -> mc.gui.screen() instanceof ConfirmScreen, 100);
        klicke(context, knopf(context, "gui.yes"));
        context.waitFor(mc -> !Files.exists(ungenutzt), 200);
        if (!Files.exists(genutzt.resolve("satz.json"))) {
            throw new AssertionError("Die genutzte Karte ist weg");
        }
    }

    /**
     * Der Ausweis der Einzelspielerwelt: Ihr Ordner gibt es, seit der Test dort einen Download anlegte. Nach einem Wechsel in den
     * Nether und zurück steht er im Ordner, mit beiden Weltschlüsseln, ohne Netz und Server, mit dem Hash des Ordners und einer
     * Spielzeit, die gekommen ist und nicht fällt. Siehe docs/download.md, „Ausweis“.
     */
    private static void ausweis(ClientGameTestContext context, TestSingleplayerContext spiel, Path hier) {
        // Kreativ und zurück an dieselbe Stelle: Sonst stürbe der Spieler am Fall oder in der Wand, und der Tod verdeckte die Fenster danach.
        spiel.getServer().runCommand("gamemode creative @a");
        String stelle = context.computeOnClient(mc -> mc.player.getX() + " " + mc.player.getY() + " " + mc.player.getZ());
        for (String[] ziel : new String[][] {{"minecraft:the_nether", "0 100 0"}, {"minecraft:overworld", stelle}}) {
            String dimension = ziel[0];
            spiel.getServer().runCommand("execute in " + dimension + " run tp @a " + ziel[1]);
            context.waitFor(mc -> mc.level != null && mc.level.dimension().identifier().toString().equals(dimension), 600);
            context.waitFor(mc -> mc.level.getGameTime() > 0, 600);
            context.waitTicks(5);
        }
        Ausweis a = Ausweis.lies(hier);
        if (a == null) {
            throw new AssertionError("Kein Ausweis in " + hier);
        }
        Ausweis.Welt oben = a.welten().get("minecraft:overworld"), nether = a.welten().get("minecraft:the_nether");
        if (!a.netz().isEmpty() || !a.server().isEmpty() || !hier.getFileName().toString().equals("welt-" + a.hash())) {
            throw new AssertionError("Ausweis im Einzelspieler mit Netz, Server oder falschem Hash: " + a);
        }
        if (oben == null || nether == null || oben.tage() != 1 || oben.alterMax() <= 0 || nether.alterMax() <= 0
                || oben.alterZuletzt() < oben.alterErstes()) {
            throw new AssertionError("Ausweis ohne beide Welten, Besuch oder Spielzeit: " + a);
        }
    }

    /**
     * Ein unsicherer Reset im Einzelspieler, ohne UID: Im Nether bekommt der Ausweis für die Oberwelt eine Spielzeit von 10⁸,
     * dann zurück. Nach dem Warten ohne UID ruht der Download der Oberwelt unter {@code …-bis-<tag>} mit Marke, und die Welt
     * zeigt ihn nicht mehr. Ein Zeiger unter eine andere Adresse geht mit, denn dort liegt die alte Welt. Siehe docs/download.md,
     * „Reset“.
     */
    private static void ruhen(ClientGameTestContext context, TestSingleplayerContext spiel, Path hier) throws IOException {
        Path dort = hier.getParent().getParent().resolve("zweit.example.net").resolve(hier.getFileName().toString());
        Laden.loesche(dort);
        download(dort.resolve("probe-dort"));
        Zeiger.setze(hier, "@zweit.example.net");
        if (Zeiger.ziel(hier) == null) {
            throw new AssertionError("Der Zeiger auf zweit.example.net gilt vor dem Reset nicht");
        }
        String stelle = context.computeOnClient(mc -> mc.player.getX() + " " + mc.player.getY() + " " + mc.player.getZ());
        spiel.getServer().runCommand("execute in minecraft:the_nether run tp @a 0 100 0");
        context.waitFor(mc -> mc.level != null && mc.level.dimension().identifier().toString().equals("minecraft:the_nether"), 600);
        context.runOnClient(mc -> Ausweise.INSTANZ.fuerTest(a -> a.alter("minecraft:overworld", 100_000_000L)));
        spiel.getServer().runCommand("execute in minecraft:overworld run tp @a " + stelle);
        context.waitFor(mc -> mc.level != null && mc.level.dimension().identifier().toString().equals("minecraft:overworld"), 600);
        // Zehn Sekunden Warten auf eine UID, dazu Luft für den Worker.
        context.waitTicks(300);
        String ruht = "probe-hier-bis-" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        if (Files.exists(hier.resolve("probe-hier")) || !Files.exists(hier.resolve(ruht).resolve(Reset.RUHT))) {
            throw new AssertionError("Nach dem Reset ruht der Download nicht unter " + ruht);
        }
        if (Zeiger.name(hier) != null) {
            throw new AssertionError("Nach dem Reset steht der Zeiger unter eine andere Adresse noch: " + Zeiger.name(hier));
        }
        Laden.loesche(dort);
        if (context.computeOnClient(mc -> Satz.fuer(hier, "minecraft:overworld", Zeigt.OHNE)) != null) {
            throw new AssertionError("Die Welt zeigt den ruhenden Baum noch");
        }
    }

    /** Wie viele gelbe Pixel, wie die Marke „doppelt“, das Bildschirmfoto im Kasten {x0, y0, x1, y1} in Einheiten hat. */
    private static int gelb(Path bild, int k, int[] kasten) {
        try {
            BufferedImage b = ImageIO.read(bild.toFile());
            int n = 0;
            for (int py = kasten[1] * k; py < Math.min(kasten[3] * k, b.getHeight()); py++) {
                for (int px = kasten[0] * k; px < Math.min(kasten[2] * k, b.getWidth()); px++) {
                    int c = b.getRGB(px, py);
                    if ((c >> 16 & 0xFF) > 200 && (c >> 8 & 0xFF) > 200 && (c & 0xFF) < 130) {
                        n++;
                    }
                }
            }
            return n;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    /** „Umziehen“ ist grau, und das Fenster nennt genau diesen Grund. */
    private static void grund(ClientGameTestContext context, String schluessel, String wann) {
        aktiv(context, false, wann);
        String grund = context.computeOnClient(mc -> mc.gui.screen() instanceof OrtFenster f ? f.grund() : "kein Fenster");
        if (!schluessel.equals(grund)) {
            throw new AssertionError(wann + ": Grund " + grund + " statt " + schluessel);
        }
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
