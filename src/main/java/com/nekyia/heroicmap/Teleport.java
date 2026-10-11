package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * „Hierher teleportieren“ auf der Vollbildkarte: der Befehl mit fester Höhe, woher die Höhe kommt, und ob der Server
 * den Befehl dem Spieler erlaubt. Der Mod umgeht keine Rechte; die prüft der Server.
 * Siehe docs/vollbildkarte.md, „Teleportieren“.
 */
final class Teleport {

    /** Ohne Block oder ohne Chunk steht dieser Wert in Boden und Höhen der Karte. */
    static final short OHNE = Short.MIN_VALUE;
    /** So gross ist die Datei einer Region höchstens, gepackt; ungepackt hat der Boden genau 512 KiB, die Höhen 32 KiB. */
    static final int MAX_GEPACKT = 1 << 20;
    private static final Logger LOGGER = LoggerFactory.getLogger(HeroicMap.ID);

    /** Woher die Höhen einer Karte kommen: Ordner unter {@code /tiles}, Kante einer Zelle in Blöcken, unterster und oberster Block. */
    record Quelle(String ordner, int zelle, int minY, int maxY) {

        /** Werte je Seite einer Region. */
        int seite() {
            return 512 / zelle;
        }
    }

    private Teleport() {
    }

    /** Auf (x, y, z), in der Mitte des Blocks: fest, auch in Chunks, die weder Client noch Server geladen haben. */
    static String befehl(String dimension, int x, int y, int z) {
        return String.format(Locale.ROOT, "execute in %s run tp @s %.1f %d %.1f", dimension, x + 0.5, y, z + 0.5);
    }

    /**
     * Ohne bekannte Höhe: auf den obersten Block, der Bewegung aufhält, ohne Laub. Die Höhe liest der Server selbst;
     * hat er den Chunk nicht geladen, weist er den Befehl ab („That position is not loaded“).
     */
    static String befehl(String dimension, int x, int z) {
        return String.format(Locale.ROOT,
                "execute in %s positioned %.1f 0 %.1f positioned over motion_blocking_no_leaves run tp @s ~ ~ ~",
                dimension, x + 0.5, z + 0.5);
    }

    /** Der Server schickt nur die Befehle, die der Spieler nutzen darf; fehlt einer, gibt es den Eintrag nicht. */
    static boolean erlaubt(CommandDispatcher<?> befehle) {
        return befehle.getRoot().getChild("execute") != null && befehle.getRoot().getChild("tp") != null;
    }

    /**
     * Teleportiert nach (x, z) in {@code dimension}, der des Spielers. Hat der Client den Chunk, nimmt er die Höhe
     * aus seiner Heightmap; sonst aus Boden oder Höhen der Karte, eine Region per HTTP; ohne beides sucht der
     * Server den Boden, und der Chat sagt es.
     */
    static void los(Minecraft mc, Satz satz, String dimension, int x, int z) {
        ClientPacketListener verbindung = mc.getConnection();
        ClientLevel level = mc.level;
        LevelChunk chunk = chunk(level, x, z);
        if (chunk != null) {
            // Der erste freie Block über dem obersten, der Bewegung oder Flüssigkeit hält, ohne Laub: über Wasser die Oberfläche.
            verbindung.sendCommand(befehl(dimension, x, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1, z));
            return;
        }
        Quelle q = satz != null && dimension.equals(satz.dimension()) ? quelle(satz) : null;
        URI uri = q == null ? null : uri(Symbole.INSTANZ.basis(), q, x, z);
        if (uri == null) {
            ohneHoehe(mc, verbindung, dimension, x, z);
            return;
        }
        InetAddress server = Symbole.INSTANZ.spielserver();
        CompletableFuture.supplyAsync(() -> hole(Symbole.CLIENT, uri, server, Symbole.FRIST, q, x, z)).thenAccept(y -> mc.execute(() -> {
            if (mc.getConnection() != verbindung) {
                return;
            }
            if (y != null) {
                verbindung.sendCommand(befehl(dimension, x, y, z));
            } else {
                ohneHoehe(mc, verbindung, dimension, x, z);
            }
        }));
    }

    /** Der Chunk um (x, z), wenn der Client ihn hat, sonst null; {@code ClientLevel.hasChunk} sagt immer ja (per javap). */
    static LevelChunk chunk(ClientLevel level, int x, int z) {
        return level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
    }

    private static void ohneHoehe(Minecraft mc, ClientPacketListener verbindung, String dimension, int x, int z) {
        verbindung.sendCommand(befehl(dimension, x, z));
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.translatable("heroicmap.karte.teleport_ohne_hoehe"));
        }
    }

    /** Die Quelle aus {@code map.json} des Satzes, oder null; siehe {@link #quelle(JsonObject)}. */
    static Quelle quelle(Satz satz) {
        try {
            return quelle(JsonParser.parseString(Files.readString(satz.ordner().resolve("map.json"), StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Der Boden ohne Laub je Block, sonst die Höhen je 4 × 4, wie {@code map.json} sie nennt; nur in der Ablage einer
     * Wurzel ({@code ../ground/{x}.{z}.bin}), so liegen sie unter {@code /tiles/ground/}. Null ohne beides oder ohne
     * {@code minY} und {@code maxY}.
     */
    static Quelle quelle(JsonObject karte) {
        if (!karte.has("minY") || !karte.has("maxY")) {
            return null;
        }
        int min = karte.get("minY").getAsInt(), max = karte.get("maxY").getAsInt();
        if (muster(karte, "ground", "groundCell", 1)) {
            return new Quelle("ground", 1, min, max);
        }
        return muster(karte, "heights", "heightsCell", 4) ? new Quelle("heights", 4, min, max) : null;
    }

    private static boolean muster(JsonObject karte, String feld, String zelle, int kante) {
        return karte.has(feld) && ("../" + feld + "/{x}.{z}.bin").equals(karte.get(feld).getAsString())
                && karte.has(zelle) && karte.get(zelle).getAsInt() == kante;
    }

    /** Die Datei der Region um (x, z): {@code <basis>/<ordner>/<rx>.<rz>.bin}; null ohne Basis. */
    static URI uri(URI basis, Quelle q, int x, int z) {
        if (basis == null) {
            return null;
        }
        String b = basis.toString();
        return URI.create((b.endsWith("/") ? b : b + "/") + q.ordner() + "/" + Math.floorDiv(x, 512) + "." + Math.floorDiv(z, 512) + ".bin");
    }

    /**
     * Holt die Datei der Region wie ein Bild einer Ebene, Adresse geprüft, ohne Weiterleitung, höchstens
     * {@link #MAX_GEPACKT} Byte in {@code frist}, und gibt die Höhe zum Stehen über (x, z); null bei jedem Fehler.
     */
    static Integer hole(HttpClient client, URI uri, InetAddress spielserver, Duration frist, Quelle q, int x, int z) {
        try {
            Adresse.Urteil urteil = Adresse.pruefe(uri, spielserver);
            if (urteil != Adresse.Urteil.GUT) {
                throw new IOException(urteil.name());
            }
            HttpRequest anfrage = HttpRequest.newBuilder(uri).timeout(frist).GET().build();
            HttpResponse<byte[]> antwort = Laden.sende(client, anfrage, MAX_GEPACKT, Laden.Grund.NETZ, frist, uri.getPath());
            if (antwort.statusCode() != 200) {
                throw new IOException("HTTP " + antwort.statusCode());
            }
            return stehen(entpacke(antwort.body(), 2 * q.seite() * q.seite()), q, x, z);
        } catch (IOException | Laden.Fehler | RuntimeException e) {
            LOGGER.warn("Heroic Map: Höhe für den Teleport aus {} nicht geladen: {}", uri, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /** Entpackt den zlib-Strom auf genau {@code laenge} Byte; ein kürzerer oder längerer ist ein Fehler, so packt keine Datei mehr aus. */
    static byte[] entpacke(byte[] gepackt, int laenge) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(gepackt);
            byte[] aus = new byte[laenge];
            int n = 0;
            while (n < laenge && !inflater.finished()) {
                int k = inflater.inflate(aus, n, laenge - n);
                if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                n += k;
            }
            if (n != laenge || !inflater.finished() && inflater.inflate(new byte[1]) > 0) {
                throw new IOException("nicht " + laenge + " Byte");
            }
            return aus;
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
    }

    /**
     * Die Höhe zum Stehen über (x, z) aus den entpackten Werten der Region: i16 little-endian, zeilenweise nach z, der
     * oberste Block, also plus 1; null ohne Wert oder ausserhalb von {@code minY} bis {@code maxY}.
     */
    static Integer stehen(byte[] werte, Quelle q, int x, int z) {
        int i = 2 * (Math.floorMod(z, 512) / q.zelle() * q.seite() + Math.floorMod(x, 512) / q.zelle());
        short w = (short) ((werte[i] & 0xFF) | (werte[i + 1] << 8));
        return w == OHNE || w < q.minY() || w > q.maxY() ? null : w + 1;
    }
}
