package com.errdeck.api.support;

import com.errdeck.core.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 테스트용 api 호출기. 본문은 JSON 으로 보내고 받는다. */
public final class ApiClient {

    public record Response(int status, String body, List<String> setCookies) {

        @SuppressWarnings("unchecked")
        public Map<String, Object> json() {
            return (Map<String, Object>) Json.parse(body);
        }

        @SuppressWarnings("unchecked")
        public List<Object> jsonList() {
            return (List<Object>) Json.parse(body);
        }

        /** Set-Cookie 에서 이름이 같은 쿠키의 값. */
        public Optional<String> cookie(String name) {
            return setCookies.stream()
                    .filter(c -> c.startsWith(name + "="))
                    .map(c -> c.substring(name.length() + 1, c.indexOf(';') < 0 ? c.length() : c.indexOf(';')))
                    .findFirst();
        }
    }

    private final int port;
    private final HttpClient client = HttpClient.newHttpClient();

    public ApiClient(int port) {
        this.port = port;
    }

    public Response get(String path, String token) {
        return send("GET", path, null, token, null, Map.of());
    }

    public Response post(String path, Object body, String token) {
        return send("POST", path, body, token, null, Map.of());
    }

    public Response put(String path, Object body, String token) {
        return send("PUT", path, body, token, null, Map.of());
    }

    public Response patch(String path, Object body, String token) {
        return send("PATCH", path, body, token, null, Map.of());
    }

    public Response delete(String path, String token) {
        return send("DELETE", path, null, token, null, Map.of());
    }

    public Response send(String method, String path, Object body, String token, String cookie, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        headers.forEach(builder::header);
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), response.headers().allValues("Set-Cookie"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 가입하고 로그인해 액세스 토큰을 받는다. */
    public String signupAndLogin(String email) {
        post("/api/auth/signup", Map.of("email", email, "password", "password-1234"), null);
        return (String) post("/api/auth/login", Map.of("email", email, "password", "password-1234"), null).json().get("accessToken");
    }
}
