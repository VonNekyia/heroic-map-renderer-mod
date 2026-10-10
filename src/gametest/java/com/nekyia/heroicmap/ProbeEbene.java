package com.nekyia.heroicmap;

import com.nekyia.heroicmap.api.HeroicMapClientApi;

/**
 * Ein Client-Mod im Kleinen: Über den Entrypoint {@code heroicmap} legt er beim Start die Ebene
 * {@value #ID} an, mit Nadel, Kreis, Linie und Schrift, dazu ein Banner, das v1 übergeht. Siehe docs/api.md.
 */
public final class ProbeEbene implements HeroicMapClientApi.Listener {

    static final String ID = "heroicmap-tests:probe";
    static final String EINTRAG = "{\"id\":\"" + ID + "\",\"name\":{\"de\":\"Probe\",\"en\":\"Probe\"},\"order\":9}";
    static final String OBJEKTE = """
            [{"type":"pin","id":"turm","at":[3,-3],"name":"Turm","color":"#3070E0"},
             {"type":"circle","id":"teich","center":[-6,4],"radius":3,"fill":"#30A0E080"},
             {"type":"line","points":[[-8,-8],[8,-6]],"stroke":{"color":"#E0C030","width":2}},
             {"type":"label","text":"Probe","path":[[-6,8],[6,8]],"size":2},
             {"type":"banner","at":[0,0],"name":"Weg","image":"weg.png"}]""";
    /** Was {@link HeroicMapClientApi#put} beim Start gab; null, solange {@link #ready()} nicht lief. */
    static volatile Boolean angenommen;

    @Override
    public void ready() {
        angenommen = HeroicMapClientApi.put(EINTRAG, OBJEKTE);
    }
}
