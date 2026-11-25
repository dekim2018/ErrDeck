package com.errdeck.sdk.autoconfigure;

import com.errdeck.sdk.tracing.ErrDeckTracer;
import com.errdeck.sdk.tracing.TracingClientInterceptor;
import com.errdeck.sdk.tracing.TracingDataSource;
import com.errdeck.sdk.web.ErrDeckTracingFilter;
import com.errdeck.sdk.webflux.ErrDeckTracingWebFilter;
import java.time.Duration;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.errdeck.core.dsn.Dsn;
import com.errdeck.sdk.ErrDeck;
import com.errdeck.sdk.ErrDeckClient;
import com.errdeck.sdk.ErrDeckOptions;
import com.errdeck.sdk.logging.ErrDeckAppender;
import com.errdeck.sdk.transport.HttpTransport;
import com.errdeck.sdk.web.ErrDeckExceptionResolver;
import com.errdeck.sdk.web.ErrDeckServletFilter;
import com.errdeck.sdk.web.ServletRequests;
import com.errdeck.sdk.webflux.ErrDeckWebFilter;
import java.time.Clock;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.TaskUtils;
import org.springframework.util.ErrorHandler;

/**
 * 의존성을 추가하고 {@code errdeck.dsn} 을 설정하면 켜진다(11.2). 감시 대상에 있는 것(서블릿, WebFlux, Logback)에만 붙는다.
 */
@AutoConfiguration
// DSN 이 없거나 빈 문자열이면 꺼진다. ${ERRDECK_DSN:} 처럼 비어 있는 기본값을 쓸 수 있게 한다.
@ConditionalOnExpression("!'${errdeck.dsn:}'.isBlank()")
@ConditionalOnBooleanProperty(name = "errdeck.enabled", matchIfMissing = true)
@EnableConfigurationProperties(ErrDeckProperties.class)
public class ErrDeckAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ErrDeckClient errDeckClient(ErrDeckProperties properties) {
        com.errdeck.sdk.Redaction.configure(properties.redactPaths());
        ErrDeckOptions defaults = ErrDeckOptions.defaults(Dsn.parse(properties.dsn()));
        ErrDeckOptions options = new ErrDeckOptions(
                defaults.dsn(),
                properties.environment(),
                properties.release(),
                properties.inAppPackages(),
                or(properties.queueCapacity(), defaults.queueCapacity()),
                or(properties.batchSize(), defaults.batchSize()),
                or(properties.flushInterval(), defaults.flushInterval()),
                or(properties.timeout(), defaults.timeout()),
                or(properties.shutdownFlush(), defaults.shutdownFlush()),
                or(properties.foldWindow(), defaults.foldWindow()),
                or(properties.foldThreshold(), defaults.foldThreshold()));
        ErrDeckClient client = new ErrDeckClient(options, new HttpTransport(options.dsn(), options.timeout()), Clock.systemUTC());
        ErrDeck.install(client, ErrDeckAutoConfiguration::currentRequest);
        return client;
    }

    /** 성능 추적(8장). {@code errdeck.tracing.enabled=false} 면 끈다. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(name = "errdeck.tracing.enabled", matchIfMissing = true)
    public ErrDeckTracer errDeckTracer(ErrDeckProperties properties) {
        ErrDeckTracer.Options defaults = ErrDeckTracer.Options.defaults(properties.environment(), properties.release());
        ErrDeckProperties.Tracing t = properties.tracing() == null ? new ErrDeckProperties.Tracing(null, null, null, null) : properties.tracing();
        ErrDeckTracer.Options options = new ErrDeckTracer.Options(defaults.environment(), defaults.release(),
                or(t.sampleRate(), defaults.sampleRate()), or(t.slowThreshold(), defaults.slowThreshold()),
                or(t.tailSampling(), defaults.tailSampling()), defaults.queueCapacity(), defaults.batchSize(), defaults.flushInterval(),
                defaults.shutdownFlush());
        Dsn dsn = Dsn.parse(properties.dsn());
        Duration timeout = or(properties.timeout(), ErrDeckOptions.defaults(dsn).timeout());
        return new ErrDeckTracer(options, new HttpTransport(dsn, timeout, true), Clock.systemUTC());
    }

    /**
     * 나가는 HTTP 호출(RestClient, RestTemplate)에 {@code traceparent} 를 붙이고, DataSource 를 감싸 SQL 을 스팬으로 남긴다(8.3).
     * 스프링 부트의 모듈에 기대지 않으려고 빈 후처리기로 붙인다.
     */
    @Bean
    @ConditionalOnBooleanProperty(name = "errdeck.tracing.enabled", matchIfMissing = true)
    public static BeanPostProcessor errDeckTracingInstrumentation() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof javax.sql.DataSource dataSource && !(bean instanceof org.springframework.beans.factory.FactoryBean<?>)) {
                    return TracingDataSource.wrap(dataSource);
                }
                if (CLIENTS_PRESENT && bean instanceof org.springframework.web.client.RestClient.Builder builder) {
                    builder.requestInterceptor(new TracingClientInterceptor());
                } else if (CLIENTS_PRESENT && bean instanceof org.springframework.web.client.RestTemplate template) {
                    template.getInterceptors().add(new TracingClientInterceptor());
                }
                return bean;
            }
        };
    }

    private static final boolean CLIENTS_PRESENT =
            org.springframework.util.ClassUtils.isPresent("org.springframework.web.client.RestClient", ErrDeckAutoConfiguration.class.getClassLoader());

    /** 다른 스레드에서 잡히지 않은 예외. 원래 핸들러는 그대로 부른다. 종료할 때 되돌린다. */
    @Bean
    public UncaughtExceptions errDeckUncaughtExceptions(ErrDeckClient client) {
        return new UncaughtExceptions(client);
    }

    /** {@code @Scheduled} 작업의 예외. 기본 동작(로그를 남기고 다음 실행을 계속)은 그대로 둔다. */
    @Bean
    public static BeanPostProcessor errDeckSchedulerErrorHandler(ObjectProvider<ErrDeckClient> client) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                ErrorHandler handler = t -> {
                    ErrDeckClient c = client.getIfAvailable();
                    if (c != null) {
                        c.capture(t, null);
                    }
                    TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER.handleError(t);
                };
                if (bean instanceof ThreadPoolTaskScheduler scheduler) {
                    scheduler.setErrorHandler(handler);
                } else if (bean instanceof SimpleAsyncTaskScheduler scheduler) {
                    scheduler.setErrorHandler(handler);
                }
                return bean;
            }
        };
    }

    /** 반환값이 없는 {@code @Async} 메서드의 예외. 감시 대상이 직접 정한 비동기 설정이 있으면 건드리지 않는다. */
    @Bean
    @ConditionalOnMissingBean(AsyncConfigurer.class)
    public AsyncConfigurer errDeckAsyncConfigurer(ErrDeckClient client) {
        AsyncUncaughtExceptionHandler fallback = new SimpleAsyncUncaughtExceptionHandler();
        return new AsyncConfigurer() {
            @Override
            public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
                return (ex, method, params) -> {
                    client.capture(ex, null);
                    fallback.handleUncaughtException(ex, method, params);
                };
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.servlet.HandlerExceptionResolver")
    static class Servlet {

        @Bean
        public ErrDeckExceptionResolver errDeckExceptionResolver() {
            return new ErrDeckExceptionResolver();
        }

        @Bean
        public ErrDeckServletFilter errDeckServletFilter(ErrDeckClient client) {
            return new ErrDeckServletFilter(client);
        }

        @Bean
        @ConditionalOnBooleanProperty(name = "errdeck.tracing.enabled", matchIfMissing = true)
        public ErrDeckTracingFilter errDeckTracingFilter(ErrDeckTracer tracer) {
            return new ErrDeckTracingFilter(tracer);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(name = "org.springframework.web.server.WebFilter")
    static class Reactive {

        @Bean
        public ErrDeckWebFilter errDeckWebFilter(ErrDeckClient client) {
            return new ErrDeckWebFilter(client);
        }

        @Bean
        @ConditionalOnBooleanProperty(name = "errdeck.tracing.enabled", matchIfMissing = true)
        public ErrDeckTracingWebFilter errDeckTracingWebFilter(ErrDeckTracer tracer) {
            return new ErrDeckTracingWebFilter(tracer);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
    static class Logging {

        @Bean
        public LogbackAppenderRegistration errDeckLogbackAppender(ErrDeckClient client) {
            return new LogbackAppenderRegistration(client);
        }
    }

    /** 루트 로거에 어펜더를 붙이고, 종료할 때 뗀다. */
    public static final class LogbackAppenderRegistration implements DisposableBean {

        private final ErrDeckAppender appender;
        private final Logger root;

        LogbackAppenderRegistration(ErrDeckClient client) {
            appender = new ErrDeckAppender(client, ErrDeckAutoConfiguration::currentRequest);
            Logger found = null;
            if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
                found = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
                appender.setContext(context);
                appender.setName(ErrDeckAppender.NAME);
                appender.start();
                found.addAppender(appender);
            }
            this.root = found;
        }

        @Override
        public void destroy() {
            if (root != null) {
                root.detachAppender(appender);
                appender.stop();
            }
        }
    }

    /** 기본 미처리 예외 핸들러를 감싼다. */
    public static final class UncaughtExceptions implements DisposableBean {

        private final Thread.UncaughtExceptionHandler previous;

        UncaughtExceptions(ErrDeckClient client) {
            this.previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                client.capture(error, null);
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                } else {
                    System.err.print("Exception in thread \"" + thread.getName() + "\" ");
                    error.printStackTrace();
                }
            });
        }

        @Override
        public void destroy() {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    /**
     * 지금 처리 중인 서블릿 요청의 정보. 요청 스레드에서 {@code ErrDeck.capture(e)} 나 {@code log.error} 를 부르면 붙인다.
     * 서블릿이 없는 앱(WebFlux, 배치)에서는 null.
     */
    static com.errdeck.sdk.RequestContext currentRequest() {
        if (!SERVLET_PRESENT) {
            return null;
        }
        try {
            return ServletRequests.current();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static final boolean SERVLET_PRESENT = org.springframework.util.ClassUtils.isPresent(
            "org.springframework.web.context.request.RequestContextHolder", ErrDeckAutoConfiguration.class.getClassLoader())
            && org.springframework.util.ClassUtils.isPresent("jakarta.servlet.http.HttpServletRequest",
            ErrDeckAutoConfiguration.class.getClassLoader());

    private static <T> T or(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
