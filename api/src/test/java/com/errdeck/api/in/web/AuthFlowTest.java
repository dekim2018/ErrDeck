package com.errdeck.api.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.errdeck.api.support.ApiClient;
import com.errdeck.api.support.TestInfra;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 가입, 로그인, 재발급(회전과 재사용 감지), 로그아웃(15.2). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "errdeck.auth.refresh-reuse-grace=300ms")
class AuthFlowTest extends TestInfra {

    @LocalServerPort
    int port;

    private ApiClient api;
    private String email;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
        email = "u-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void 가입한_뒤_로그인하면_액세스_토큰과_HttpOnly_리프레시_쿠키를_받는다() {
        assertThat(api.post("/api/auth/signup", credentials(), null).status()).isEqualTo(201);

        ApiClient.Response login = api.post("/api/auth/login", credentials(), null);

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.json().get("accessToken")).isInstanceOf(String.class);
        assertThat(login.setCookies()).anySatisfy(c -> assertThat(c)
                .startsWith("errdeck_refresh=").contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/auth"));
        assertThat(api.get("/api/me", (String) login.json().get("accessToken")).status()).isEqualTo(200);
    }

    @Test
    void 같은_이메일로_두_번_가입하면_409다() {
        api.post("/api/auth/signup", credentials(), null);

        assertThat(api.post("/api/auth/signup", Map.of("email", email.toUpperCase(), "password", "password-1234"), null).status())
                .isEqualTo(409);
    }

    @Test
    void 비밀번호가_틀리거나_없는_이메일이면_401이다() {
        api.post("/api/auth/signup", credentials(), null);

        assertThat(api.post("/api/auth/login", Map.of("email", email, "password", "wrong-password"), null).status()).isEqualTo(401);
        assertThat(api.post("/api/auth/login", Map.of("email", "none-" + email, "password", "password-1234"), null).status())
                .isEqualTo(401);
    }

    @Test
    void 형식이_틀린_요청은_400이다() {
        assertThat(api.post("/api/auth/signup", Map.of("email", "not-email", "password", "password-1234"), null).status()).isEqualTo(400);
        assertThat(api.post("/api/auth/signup", Map.of("email", email, "password", "short"), null).status()).isEqualTo(400);
    }

    @Test
    void 토큰이_없거나_틀리면_401이다() {
        assertThat(api.get("/api/me", null).status()).isEqualTo(401);
        assertThat(api.get("/api/me", "not-a-jwt").status()).isEqualTo(401);
    }

    @Test
    void 재발급하면_쿠키가_바뀌고_새_쿠키로_다시_재발급할_수_있다() {
        String first = loginCookie();

        ApiClient.Response refreshed = refresh(first);
        String second = refreshed.cookie("errdeck_refresh").orElseThrow();

        assertThat(refreshed.status()).isEqualTo(200);
        assertThat(second).isNotEqualTo(first);
        assertThat(refresh(second).status()).isEqualTo(200);
    }

    @Test
    void 방금_쓴_쿠키가_다시_오면_동시_재발급으로_보고_429를_준다() {
        String first = loginCookie();
        refresh(first);

        assertThat(refresh(first).status()).isEqualTo(429);
    }

    @Test
    void 오래전에_쓴_쿠키가_다시_오면_탈취로_보고_모든_세션을_끊는다() throws Exception {
        String first = loginCookie();
        String second = refresh(first).cookie("errdeck_refresh").orElseThrow();
        Thread.sleep(500);

        assertThat(refresh(first).status()).isEqualTo(401);
        assertThat(refresh(second).status()).isEqualTo(401);
    }

    @Test
    void 로그아웃하면_그_쿠키로_재발급할_수_없다() {
        String cookie = loginCookie();

        ApiClient.Response logout = api.send("POST", "/api/auth/logout", null, null, "errdeck_refresh=" + cookie, Map.of());

        assertThat(logout.status()).isEqualTo(204);
        assertThat(refresh(cookie).status()).isEqualTo(401);
    }

    private String loginCookie() {
        api.post("/api/auth/signup", credentials(), null);
        return api.post("/api/auth/login", credentials(), null).cookie("errdeck_refresh").orElseThrow();
    }

    private ApiClient.Response refresh(String cookie) {
        return api.send("POST", "/api/auth/refresh", null, null, "errdeck_refresh=" + cookie, Map.of());
    }

    private Map<String, String> credentials() {
        return Map.of("email", email, "password", "password-1234");
    }
}
