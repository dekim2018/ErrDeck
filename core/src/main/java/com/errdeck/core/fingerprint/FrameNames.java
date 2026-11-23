package com.errdeck.core.fingerprint;

import java.util.regex.Pattern;

/**
 * 실행할 때마다, 또는 코드가 조금만 바뀌어도 달라지는 클래스와 메서드 이름의 꼬리를 뗀다(6.3).
 */
public final class FrameNames {

    /** {@code FanService$$SpringCGLIB$$0}, 옛 형식 {@code $$EnhancerBySpringCGLIB$$3f2a1b}, {@code $$FastClassBySpringCGLIB$$...}. */
    private static final Pattern CGLIB = Pattern.compile("\\$\\$(?:EnhancerBy|FastClassBy)?SpringCGLIB\\$\\$.*$");
    /** {@code jdk.proxy2.$Proxy123}, {@code com.sun.proxy.$Proxy45}. 모듈 이름의 번호도 실행마다 다를 수 있다. */
    private static final Pattern PROXY_NUMBER = Pattern.compile("\\$Proxy[0-9]+");
    private static final Pattern PROXY_MODULE = Pattern.compile("^jdk\\.proxy[0-9]+\\.");
    /** {@code Foo$$Lambda$123/0x0000000800c0a440}, JDK 21 이후의 {@code Foo$$Lambda/0x000071c0010a5a58}. */
    private static final Pattern LAMBDA_CLASS = Pattern.compile("\\$\\$Lambda(?:\\$[0-9]+)?(?:[/.]0x[0-9a-fA-F]+)?");
    /** {@code lambda$settle$3}. 람다를 하나 더하면 뒤 번호가 밀린다. */
    private static final Pattern LAMBDA_METHOD = Pattern.compile("^(lambda\\$.+?)\\$[0-9]+$");

    private FrameNames() {
    }

    public static String normalizeClass(String className) {
        if (className == null) {
            return "";
        }
        String name = CGLIB.matcher(className).replaceAll("");
        name = PROXY_MODULE.matcher(name).replaceAll("jdk.proxy.");
        name = PROXY_NUMBER.matcher(name).replaceAll(java.util.regex.Matcher.quoteReplacement("$Proxy"));
        return LAMBDA_CLASS.matcher(name).replaceAll(java.util.regex.Matcher.quoteReplacement("$$Lambda"));
    }

    public static String normalizeMethod(String method) {
        if (method == null) {
            return "";
        }
        return LAMBDA_METHOD.matcher(method).replaceAll("$1");
    }

    /**
     * 리플렉션으로 메서드를 부를 때 끼어드는 프레임. JDK 버전과 호출 횟수에 따라 생기기도 하고 이름이 바뀌기도 한다.
     * 예: {@code jdk.internal.reflect.GeneratedMethodAccessor12}, {@code DirectMethodHandleAccessor}, {@code java.lang.reflect.Method.invoke}
     */
    public static boolean isReflectionAccessor(String className, String method) {
        if (className == null) {
            return false;
        }
        return className.startsWith("jdk.internal.reflect.")
                || className.startsWith("sun.reflect.")
                || className.startsWith("java.lang.invoke.LambdaForm$")
                || (className.equals("java.lang.reflect.Method") && "invoke".equals(method))
                || (className.equals("java.lang.reflect.Constructor") && "newInstance".equals(method));
    }
}
