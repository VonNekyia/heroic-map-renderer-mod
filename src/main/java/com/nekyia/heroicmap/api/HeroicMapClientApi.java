package com.nekyia.heroicmap.api;

import com.nekyia.heroicmap.Ebenen;

/**
 * Layers of other client mods on the Heroic Map minimap and full map. A layer has an id {@code modid:name}
 * and holds pins, map text, areas, circles and lines in the same JSON as the layers a server sends. It shows
 * in the layer list with its own switch, like a server layer. See docs/api.md.
 *
 * <p>Interne Umsetzung in {@code Ebenen}; diese Klasse ist nur die öffentliche Schnittstelle. Wer hier etwas ändert,
 * hebt {@link #VERSION}.
 */
public final class HeroicMapClientApi {

    /** The version of this API; it goes up with every change. */
    public static final int VERSION = 1;

    /**
     * Entrypoint {@code heroicmap} in fabric.mod.json: Heroic Map calls {@link #ready()} once when the client has
     * started. Only then does the JVM load your classes that use this API, so you need no check of your own.
     */
    public interface Listener {

        /** Heroic Map is loaded; put your layers now or any time later. */
        void ready();
    }

    private HeroicMapClientApi() {
    }

    /**
     * Adds a layer or replaces it as a whole. {@code entry} is one entry of the layer list, {@code {"id", "name":
     * {"de", "en"}, "visible", "order"}}; {@code objects} is the {@code objects} array of a layer part. Any thread.
     * False if either does not parse or the id is not {@code namespace:path}, or starts with {@code heroicmap:}.
     * A server layer with the same id hides this one while the server has it.
     */
    public static boolean put(String entry, String objects) {
        return Ebenen.vonMod(entry, objects);
    }

    /** Removes the layer {@code id} of this mod; a server layer with the same id stays. Any thread. */
    public static void remove(String id) {
        Ebenen.ohneMod(id);
    }
}
