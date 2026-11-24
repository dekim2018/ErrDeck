package com.errdeck.sdk.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.errdeck.sdk.ErrDeckClient;
import com.errdeck.sdk.support.FakeIngest;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 웹 요청 밖의 예외(스케줄, 비동기, 다른 스레드)를 잡는지, 설정이 없으면 꺼지는지 본다. */
@SpringBootTest(classes = BackgroundCaptureTest.App.class,
        properties = {"spring.main.web-application-type=none", "errdeck.flush-interval=100ms"})
class BackgroundCaptureTest {

    static final FakeIngest INGEST = new FakeIngest();

    @DynamicPropertySource
    static void dsn(DynamicPropertyRegistry registry) {
        registry.add("errdeck.dsn", INGEST::dsn);
    }

    @AfterAll
    static void stop() {
        INGEST.close();
    }

    @Autowired
    Jobs jobs;

    @Test
    void 스케줄_작업의_예외를_보내고_다음_실행은_계속된다() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(INGEST.events()).extracting(e -> e.primaryException().value()).contains("스케줄 실패"));
    }

    @Test
    void 비동기_메서드의_예외를_보낸다() {
        jobs.fireAndForget();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(INGEST.events()).extracting(e -> e.primaryException().value()).contains("비동기 실패"));
    }

    @Test
    void 다른_스레드에서_잡히지_않은_예외를_보낸다() throws Exception {
        Thread thread = new Thread(() -> {
            throw new IllegalStateException("스레드 실패");
        });
        thread.start();
        thread.join();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(INGEST.events()).extracting(e -> e.primaryException().value()).contains("스레드 실패"));
    }

    @Test
    void dsn_이_없거나_꺼져_있으면_켜지지_않는다() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ErrDeckAutoConfiguration.class));

        runner.run(context -> assertThat(context).doesNotHaveBean(ErrDeckClient.class));
        runner.withPropertyValues("errdeck.dsn=http://k@localhost:1/1", "errdeck.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ErrDeckClient.class));
        runner.withPropertyValues("errdeck.dsn=http://k@localhost:1/1")
                .run(context -> assertThat(context).hasSingleBean(ErrDeckClient.class));
    }

    @EnableAutoConfiguration
    @EnableScheduling
    @EnableAsync
    @Import(Jobs.class)
    static class App {
    }

    @Component
    static class Jobs {

        private final AtomicBoolean failed = new AtomicBoolean();

        @Scheduled(fixedDelay = 200)
        void tick() {
            if (failed.compareAndSet(false, true)) {
                throw new IllegalStateException("스케줄 실패");
            }
        }

        @Async
        public void fireAndForget() {
            throw new IllegalStateException("비동기 실패");
        }
    }
}
