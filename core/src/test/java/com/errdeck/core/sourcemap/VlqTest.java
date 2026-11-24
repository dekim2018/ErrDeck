package com.errdeck.core.sourcemap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class VlqTest {

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource({"A, 0", "C, 1", "D, -1", "E, 2", "F, -2", "gB, 16", "hB, -16", "2H, 123"})
    void Base64_VLQ_숫자를_읽는다(String text, int expected) {
        int[] position = {0};

        assertEquals(expected, Vlq.decode(text, position));
        assertEquals(text.length(), position[0]);
    }

    @Test
    void 이어서_여러_숫자를_읽는다() {
        int[] position = {0};
        String text = "AAgBC";

        assertEquals(0, Vlq.decode(text, position));
        assertEquals(0, Vlq.decode(text, position));
        assertEquals(16, Vlq.decode(text, position));
        assertEquals(1, Vlq.decode(text, position));
    }

    @Test
    void 숫자_중간에_끝나거나_Base64_가_아니면_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> Vlq.decode("g", new int[] {0}));
        assertThrows(IllegalArgumentException.class, () -> Vlq.decode("!", new int[] {0}));
        assertThrows(IllegalArgumentException.class, () -> Vlq.decode("한", new int[] {0}));
    }
}
