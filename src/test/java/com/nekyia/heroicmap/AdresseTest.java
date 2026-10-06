package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

/** Die Prüfung der Adresse aus einer freigabe. Siehe docs/download.md, „Sicherheit“. */
class AdresseTest {

    private static InetAddress ip(String adresse) throws UnknownHostException {
        return InetAddress.getByName(adresse);
    }

    @Test
    void nurHttpUndHttpsOhneZusatz() throws UnknownHostException {
        assertEquals(Adresse.Urteil.SCHEMA, Adresse.pruefe(URI.create("ftp://1.1.1.1/baum"), ip("1.1.1.1")));
        assertEquals(Adresse.Urteil.SCHEMA, Adresse.pruefe(URI.create("file:///etc/passwd"), ip("1.1.1.1")));
        // Ein ? würde /map.json schlucken; Userinfo und Fragment braucht keine Karte.
        assertEquals(Adresse.Urteil.SCHEMA, Adresse.pruefe(URI.create("https://1.1.1.1/baum?a="), ip("8.8.8.8")));
        assertEquals(Adresse.Urteil.SCHEMA, Adresse.pruefe(URI.create("https://1.1.1.1/baum#a"), ip("8.8.8.8")));
        assertEquals(Adresse.Urteil.SCHEMA, Adresse.pruefe(URI.create("https://a@1.1.1.1/baum"), ip("8.8.8.8")));
        assertEquals(Adresse.Urteil.GUT, Adresse.pruefe(URI.create("https://1.1.1.1/baum"), ip("8.8.8.8")));
        assertEquals(Adresse.Urteil.GUT, Adresse.pruefe(URI.create("http://1.1.1.1:8080/baum"), ip("8.8.8.8")));
    }

    @Test
    void heimnetzNurMitVerbindungInsHeimnetz() throws UnknownHostException {
        assertEquals(Adresse.Urteil.HEIMNETZ, Adresse.pruefe(URI.create("http://127.0.0.1/baum"), ip("1.1.1.1")));
        assertEquals(Adresse.Urteil.HEIMNETZ, Adresse.pruefe(URI.create("http://192.168.1.1/baum"), ip("1.1.1.1")));
        assertEquals(Adresse.Urteil.HEIMNETZ, Adresse.pruefe(URI.create("http://100.64.0.1/baum"), ip("1.1.1.1")));
        // Ohne bekannte Verbindung gilt der Spielserver als fern.
        assertEquals(Adresse.Urteil.HEIMNETZ, Adresse.pruefe(URI.create("http://192.168.1.1/baum"), null));
        assertEquals(Adresse.Urteil.GUT, Adresse.pruefe(URI.create("http://192.168.1.1/baum"), ip("192.168.1.20")));
        assertEquals(Adresse.Urteil.GUT, Adresse.pruefe(URI.create("http://127.0.0.1/baum"), ip("127.0.0.1")));
    }

    @Test
    void nah() throws UnknownHostException {
        for (String nah : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.1.1", "::1",
            "fd00::1", "fe80::1", "0.0.0.0", "0.1.2.3", "100.64.0.1", "100.127.255.255", "::ffff:192.168.1.1"}) {
            assertTrue(Adresse.nah(InetAddress.getAllByName(nah)), nah);
        }
        for (String fern : new String[] {"1.1.1.1", "8.8.8.8", "100.63.255.255", "100.128.0.1", "2001:4860:4860::8888"}) {
            assertFalse(Adresse.nah(InetAddress.getAllByName(fern)), fern);
        }
    }
}
