package com.errdeck.sdk;

import java.util.function.Supplier;

/**
 * 코드에서 직접 예외를 보낼 때 쓰는 진입점. 예: {@code ErrDeck.capture(e)}.
 * 설정이 없거나 꺼져 있으면 아무것도 하지 않는다. 어떤 경우에도 예외를 던지지 않는다.
 */
public final class ErrDeck {

    private static volatile ErrDeckClient client;
    private static volatile Supplier<RequestContext> currentRequest = () -> null;

    private ErrDeck() {
    }

    public static void capture(Throwable throwable) {
        try {
            ErrDeckClient c = client;
            if (c != null) {
                c.capture(throwable, currentRequest.get());
            }
        } catch (Throwable e) {
            InternalLog.warn("facade", "예외를 보내지 못했다: " + e);
        }
    }

    /** 자동 설정이 부른다. */
    public static void install(ErrDeckClient installed, Supplier<RequestContext> requestSupplier) {
        client = installed;
        currentRequest = requestSupplier == null ? () -> null : requestSupplier;
    }

    public static void uninstall(ErrDeckClient installed) {
        if (client == installed) {
            client = null;
            currentRequest = () -> null;
        }
    }

    static ErrDeckClient client() {
        return client;
    }
}
