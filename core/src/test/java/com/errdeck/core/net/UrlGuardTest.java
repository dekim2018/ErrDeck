package com.errdeck.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

class UrlGuardTest {

    private final UrlGuard guard = new UrlGuard(Set.of("demo-api"));

    @Test
    void 내부_주소는_거부한다() {
        for (String url : new String[] {"http://127.0.0.1/", "http://localhost:8080/", "http://169.254.169.254/latest/meta-data",
                "http://10.0.0.5/", "http://192.168.1.1/", "http://172.16.0.1/", "http://100.64.0.1/", "http://[::1]/", "http://0.0.0.0/"}) {
            assertThrows(IllegalArgumentException.class, () -> guard.check(url), url);
        }
    }

    @Test
    void http_와_https_만_받는다() {
        assertThrows(IllegalArgumentException.class, () -> guard.check("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> guard.check("gopher://example.com/"));
        assertThrows(IllegalArgumentException.class, () -> guard.check("http://user:pw@8.8.8.8/"));
    }

    @Test
    void 공개_주소와_허용한_호스트는_받는다() {
        assertEquals("8.8.8.8", guard.check("https://8.8.8.8/health").getHost());
        assertEquals("demo-api", guard.check("http://demo-api:8090/actuator/health").getHost());
    }
}
