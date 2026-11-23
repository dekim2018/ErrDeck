package com.errdeck.loadgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OptionsTest {

    @Test
    void 옵션을_읽고_없는_값은_기본값을_쓴다() {
        Options options = Options.parse(new String[] {"sustained", "--dsn=http://k@localhost:8080/1", "--rate=200", "--duration=5m"});

        assertEquals(Scenario.SUSTAINED, options.scenario());
        assertEquals(200, options.rate());
        assertEquals(Duration.ofMinutes(5), options.duration());
        assertEquals(1000, options.fingerprints());
        assertEquals(1, options.batch());
        assertEquals(Duration.ofSeconds(3), options.timeout());
    }

    @Test
    void 폭주는_지문을_한_종류로_고정한다() {
        Options options = Options.parse(new String[] {"burst", "--dsn=http://k@localhost:8080/1", "--fingerprints=50"});

        assertEquals(1, options.fingerprints());
    }

    @Test
    void 초당_봉투_수는_이벤트_수를_묶음_크기로_나눈_것이다() {
        Options options = Options.parse(new String[] {"burst", "--dsn=http://k@localhost:8080/1", "--rate=5000", "--batch=50"});

        assertEquals(100.0, options.envelopesPerSecond());
    }

    @Test
    void 틀린_옵션을_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"spike", "--dsn=http://k@h/1"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"burst"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"burst", "--dsn=http://k@h/1", "--rat=1"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"burst", "--dsn=http://k@h/1", "--rate=0"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"burst", "--dsn=http://k@h/1", "rate=1"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"burst", "--dsn=http://k@h/1", "--duration=1h"}));
    }

    @Test
    void 시간_형식을_읽는다() {
        assertEquals(Duration.ofMillis(500), Options.parseDuration("500ms"));
        assertEquals(Duration.ofSeconds(30), Options.parseDuration("30s"));
        assertEquals(Duration.ofMinutes(5), Options.parseDuration("5m"));
    }
}
