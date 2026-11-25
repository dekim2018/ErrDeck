package com.errdeck.sdk.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.sdk.ErrDeckClient;
import com.errdeck.sdk.Redaction;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** DSN 이 비면 SDK 가 꺼지고, 경로의 비밀을 가린다(23 작업, CoPage 연결). */
class SwitchAndRedactionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ErrDeckAutoConfiguration.class));

    @AfterEach
    void reset() {
        Redaction.configure(List.of());
    }

    @Test
    void DSN_이_없거나_비어_있으면_꺼진다() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ErrDeckClient.class));
        runner.withPropertyValues("errdeck.dsn=").run(context -> assertThat(context).doesNotHaveBean(ErrDeckClient.class));
        runner.withPropertyValues("errdeck.dsn=  ").run(context -> assertThat(context).doesNotHaveBean(ErrDeckClient.class));
    }

    @Test
    void DSN_이_있으면_켜지고_경로_가리기를_설정한다() {
        runner.withPropertyValues("errdeck.dsn=http://key@127.0.0.1:9/1", "errdeck.redact-paths[0]=(/s/)[^/?#]+",
                        "errdeck.redact-paths[1]=(/api/share/)[^/?#]+")
                .run(context -> {
                    assertThat(context).hasSingleBean(ErrDeckClient.class);
                    assertThat(Redaction.apply("https://copage.app/s/AbC123xyz/edit")).isEqualTo("https://copage.app/s/[Filtered]/edit");
                    assertThat(Redaction.apply("/api/share/tok-1?x=1")).isEqualTo("/api/share/[Filtered]?x=1");
                    assertThat(Redaction.apply("/api/pages/3")).isEqualTo("/api/pages/3");
                });
    }

    @Test
    void 괄호가_없는_패턴은_통째로_가린다() {
        Redaction.configure(List.of("secret-[0-9]+"));
        assertThat(Redaction.apply("/x/secret-42/y")).isEqualTo("/x/[Filtered]/y");
    }
}
