package com.errdeck.core.fingerprint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MessageScrubberTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            User 1024 not found                                         | User <num> not found
            Order 3.14 exceeds limit 2                                  | Order <num> exceeds limit <num>
            HikariPool-1 - Connection is not available after 3000ms     | HikariPool-<num> - Connection is not available after <num>ms
            Session 0f3c2a5e-8b1d-4c7a-9e2f-6a4b8c1d2e3f expired          | Session <uuid> expired
            Object at 0x7f3a9c00 is locked                              | Object at <hex> is locked
            Commit 9b14f42a1c not found                                 | Commit <hex> not found
            Column "user_name" cannot be null                           | Column <str> cannot be null
            No property 'nickName 2' found                              | No property <str> found
            can't parse value 'abc'                                     | can't parse value <str>
            utf8 encoder failed for IPv4 address                        | utf8 encoder failed for IPv4 address
            deadbeef and cafebabe are words here                        | deadbeef and cafebabe are words here
            """)
    void 요청마다_달라지는_값을_자리_표시로_바꾼다(String message, String expected) {
        assertEquals(expected, MessageScrubber.scrub(message));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            User 1024 not found | User 77 not found
            Session 0f3c2a5e-8b1d-4c7a-9e2f-6a4b8c1d2e3f expired | Session 7d1e9a40-2c6b-4f8e-b3a1-5c9d0e7f2a6b expired
            Column "a" cannot be null | Column "bbb" cannot be null
            """)
    void 값만_다른_두_메시지는_같아진다(String first, String second) {
        assertEquals(MessageScrubber.scrub(first), MessageScrubber.scrub(second));
    }
}
