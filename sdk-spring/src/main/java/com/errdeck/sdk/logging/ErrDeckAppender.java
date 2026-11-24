package com.errdeck.sdk.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.errdeck.sdk.ErrDeckClient;
import com.errdeck.sdk.InternalLog;
import com.errdeck.sdk.RequestContext;
import java.util.function.Supplier;

/**
 * {@code log.error} 에 예외가 함께 넘어오면 보낸다(11.2). Redis 스트림 소비자나 이벤트 리스너처럼 웹 요청 밖에서
 * 예외를 잡아 로그만 남기는 코드에서 쓴다. SDK 자신의 로그는 다시 잡지 않는다.
 */
public class ErrDeckAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    public static final String NAME = "ERRDECK";

    private final ErrDeckClient client;
    private final Supplier<RequestContext> currentRequest;

    public ErrDeckAppender(ErrDeckClient client, Supplier<RequestContext> currentRequest) {
        this.client = client;
        this.currentRequest = currentRequest;
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            if (!event.getLevel().isGreaterOrEqual(Level.ERROR) || InternalLog.NAME.equals(event.getLoggerName())) {
                return;
            }
            if (event.getThrowableProxy() instanceof ThrowableProxy proxy && proxy.getThrowable() != null) {
                client.capture(proxy.getThrowable(), currentRequest.get());
            }
        } catch (RuntimeException ignored) {
            // 로그 처리 중에 감시 대상으로 예외를 던지지 않는다.
        }
    }
}
