package com.errdeck.core.dsn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DsnTest {

    @Test
    void 공개키와_호스트와_프로젝트_ID를_읽는다() {
        Dsn dsn = Dsn.parse("https://a1b2c3d4@errdeck.example.com/42");

        assertEquals("https", dsn.scheme());
        assertEquals("a1b2c3d4", dsn.publicKey());
        assertEquals("errdeck.example.com", dsn.host());
        assertEquals(-1, dsn.port());
        assertEquals(42, dsn.projectId());
    }

    @Test
    void 봉투를_보낼_주소를_만든다() {
        assertEquals(URI.create("https://errdeck.example.com/ingest/42/envelope"),
                Dsn.parse("https://key@errdeck.example.com/42").envelopeUri());
        assertEquals(URI.create("http://localhost:8080/ingest/7/envelope"),
                Dsn.parse("http://key@localhost:8080/7").envelopeUri());
    }

    @Test
    void 문자열로_바꾸면_원래_DSN이다() {
        String text = "http://key_1-2@localhost:8080/7";

        assertEquals(text, Dsn.parse(text).toString());
    }

    @Test
    void 프로젝트_ID는_UInt32_범위까지_받는다() {
        assertEquals(4_294_967_295L, Dsn.parse("https://k@h/4294967295").projectId());
        assertThrows(IllegalArgumentException.class, () -> Dsn.parse("https://k@h/4294967296"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "errdeck.example.com/42",
            "ftp://key@h/42",
            "https://h/42",
            "https://key:secret@h/42",
            "https://key@/42",
            "https://key@h",
            "https://key@h/",
            "https://key@h/0",
            "https://key@h/012",
            "https://key@h/abc",
            "https://key@h/prefix/42",
            "https://key@h/42?x=1",
            "https://key@h/42#x",
            "https://ke.y@h/42",
            "https://key@h:0/42",
    })
    void 형식이_틀린_DSN을_거부한다(String dsn) {
        assertThrows(IllegalArgumentException.class, () -> Dsn.parse(dsn));
    }
}
