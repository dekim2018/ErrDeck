package com.errdeck.core.fingerprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FrameNamesTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            com.live.FanService$$SpringCGLIB$$0                      | com.live.FanService
            com.live.FanService$$SpringCGLIB$$1                      | com.live.FanService
            com.live.FanService$$EnhancerBySpringCGLIB$$3f2a1b9c     | com.live.FanService
            com.live.FanService$$FastClassBySpringCGLIB$$77aa01      | com.live.FanService
            jdk.proxy2.$Proxy123                                     | jdk.proxy.$Proxy
            jdk.proxy3.$Proxy45                                      | jdk.proxy.$Proxy
            com.sun.proxy.$Proxy7                                    | com.sun.proxy.$Proxy
            com.live.Settle$$Lambda$123/0x0000000800c0a440           | com.live.Settle$$Lambda
            com.live.Settle$$Lambda/0x000071c0010a5a58               | com.live.Settle$$Lambda
            com.live.Settle$$Lambda.0x000071c0010a5a58               | com.live.Settle$$Lambda
            com.live.FanService                                      | com.live.FanService
            com.live.FanService$Inner                                | com.live.FanService$Inner
            """)
    void 클래스_이름의_꼬리를_뗀다(String className, String expected) {
        assertEquals(expected, FrameNames.normalizeClass(className));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            lambda$settle$3           | lambda$settle
            lambda$settle$12          | lambda$settle
            lambda$static$0           | lambda$static
            lambda$new$1              | lambda$new
            settle                    | settle
            access$000                | access$000
            """)
    void 람다_메서드의_끝_번호를_뗀다(String method, String expected) {
        assertEquals(expected, FrameNames.normalizeMethod(method));
    }

    @Test
    void 리플렉션_접근자_프레임을_알아본다() {
        assertTrue(FrameNames.isReflectionAccessor("jdk.internal.reflect.GeneratedMethodAccessor12", "invoke"));
        assertTrue(FrameNames.isReflectionAccessor("jdk.internal.reflect.DirectMethodHandleAccessor", "invoke"));
        assertTrue(FrameNames.isReflectionAccessor("sun.reflect.NativeMethodAccessorImpl", "invoke0"));
        assertTrue(FrameNames.isReflectionAccessor("java.lang.reflect.Method", "invoke"));
        assertTrue(FrameNames.isReflectionAccessor("java.lang.invoke.LambdaForm$MH/0x0000000801010800", "invoke"));
        assertFalse(FrameNames.isReflectionAccessor("java.lang.reflect.Method", "getName"));
        assertFalse(FrameNames.isReflectionAccessor("com.live.FanService", "invoke"));
        assertFalse(FrameNames.isReflectionAccessor(null, "invoke"));
    }
}
