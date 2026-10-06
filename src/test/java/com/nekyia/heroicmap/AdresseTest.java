package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

/** Die Prüfung der Adresse aus einer freigabe. Siehe docs/download.md, „Sicherheit“. */
class AdresseTest {

    private static InetAddress ip(String adresse) throws UnknownHostException {
        return InetAddress.getByName(adresse);
    }

    private static Adresse.Urteil pruefe(String url, InetAddress spielserver) {
        return Adresse.pruefe(URI.create(url), spielserver);
    }

    @Test
    void nurHttpUndHttpsOhneZusatz() throws UnknownHostException {
        assertEquals(Adresse.Urteil.SCHEMA, pruefe("ftp://1.1.1.1/baum", ip("1.1.1.1")));
        assertEquals(Adresse.Urteil.SCHEMA, pruefe("file:///etc/passwd", ip("1.1.1.1")));
        // Ein ? würde /map.json schlucken; Userinfo und Fragment braucht keine Karte.
        assertEquals(Adresse.Urteil.SCHEMA, pruefe("https://1.1.1.1/baum?a=", ip("8.8.8.8")));
        assertEquals(Adresse.Urteil.SCHEMA, pruefe("https://1.1.1.1/baum#a", ip("8.8.8.8")));
        assertEquals(Adresse.Urteil.SCHEMA, pruefe("https://a@1.1.1.1/baum", ip("8.8.8.8")));
        assertEquals(Adresse.Urteil.GUT, pruefe("https://1.1.1.1/baum", ip("8.8.8.8")));
        assertEquals(Adresse.Urteil.GUT, pruefe("http://1.1.1.1:8080/baum", ip("8.8.8.8")));
    }

    @Test
    void fernerSpielserverKeinZielImHeimnetz() throws UnknownHostException {
        assertEquals(Adresse.Urteil.HEIMNETZ, pruefe("http://127.0.0.1/baum", ip("1.1.1.1")));
        assertEquals(Adresse.Urteil.HEIMNETZ, pruefe("http://192.168.1.1/baum", ip("1.1.1.1")));
        assertEquals(Adresse.Urteil.HEIMNETZ, pruefe("http://100.64.0.1/baum", ip("1.1.1.1")));
        // Ohne bekannte Verbindung gilt der Spielserver als fern.
        assertEquals(Adresse.Urteil.HEIMNETZ, pruefe("http://192.168.1.1/baum", null));
    }

    @Test
    void naherSpielserverNurErSelbst() throws UnknownHostException {
        // Geht die Spielverbindung ins Heimnetz oder ein Tailnet, ist nur der Spielserver selbst ein Ziel.
        assertEquals(Adresse.Urteil.GUT, pruefe("http://192.168.1.20:8080/baum", ip("192.168.1.20")));
        assertEquals(Adresse.Urteil.HEIMNETZ, pruefe("http://192.168.1.1/baum", ip("192.168.1.20")));
        assertEquals(Adresse.Urteil.HEIMNETZ, pruefe("http://1.1.1.1/baum", ip("192.168.1.20")));
        assertEquals(Adresse.Urteil.HEIMNETZ, pruefe("http://100.64.0.2/baum", ip("100.64.0.1")));
        assertEquals(Adresse.Urteil.HEIMNETZ, pruefe("http://192.168.1.1/baum", ip("127.0.0.1")));
        // loopback ist derselbe Rechner, ob v4 oder v6.
        assertEquals(Adresse.Urteil.GUT, pruefe("http://127.0.0.1/baum", ip("127.0.0.1")));
        assertEquals(Adresse.Urteil.GUT, pruefe("http://[::1]/baum", ip("127.0.0.1")));
    }

    @Test
    void ipv4InIpv6AusDns() throws UnknownHostException {
        byte[] b = new byte[16];
        b[10] = (byte) 0xFF;
        b[11] = (byte) 0xFF;
        b[12] = (byte) 192;
        b[13] = (byte) 168;
        b[14] = 1;
        b[15] = 1;
        // So kommt ::ffff:192.168.1.1 aus einem AAAA-Eintrag: als Inet6Address, für die isSiteLocal falsch ist.
        InetAddress verpackt = Inet6Address.getByAddress(null, b, -1);
        assertTrue(verpackt instanceof Inet6Address);
        assertFalse(verpackt.isSiteLocalAddress());
        assertTrue(Adresse.nah(verpackt));
        assertTrue(Adresse.gleich(verpackt, ip("192.168.1.1")));
        b[12] = 1;
        b[13] = 1;
        b[14] = 1;
        b[15] = 1;
        assertFalse(Adresse.nah(Inet6Address.getByAddress(null, b, -1)));
    }

    @Test
    void nah() throws UnknownHostException {
        for (String nah : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.1.1", "::1",
            "fd00::1", "fe80::1", "0.0.0.0", "0.1.2.3", "100.64.0.1", "100.127.255.255"}) {
            assertTrue(Adresse.nah(ip(nah)), nah);
        }
        for (String fern : new String[] {"1.1.1.1", "8.8.8.8", "100.63.255.255", "100.128.0.1", "2001:4860:4860::8888"}) {
            assertFalse(Adresse.nah(ip(fern)), fern);
        }
    }
}
