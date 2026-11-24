package com.errdeck.api.out.clickhouse;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * ClickHouse 의 HTTP 인터페이스로 SQL 하나를 실행한다. 스키마 실행기만 쓰므로 드라이버 대신 JDK 클라이언트로 충분하다.
 */
public final class ClickHouseHttp {

    private final HttpClient client;
    private final URI url;
    private final String user;
    private final String password;
    private final Duration timeout;

    public ClickHouseHttp(URI url, String user, String password, Duration timeout) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** @param database 비우면 기본 데이터베이스에서 실행한다 */
    public String execute(String database, String sql) {
        return execute(database, sql, java.util.Map.of());
    }

    /**
     * @param parameters 쿼리 매개변수. {@code {이름:타입}} 자리에 들어간다. 값을 SQL 에 이어 붙이지 않는다
     */
    public String execute(String database, String sql, java.util.Map<String, String> parameters) {
        StringBuilder query = new StringBuilder();
        if (database != null) {
            query.append("?database=").append(URLEncoder.encode(database, StandardCharsets.UTF_8));
        }
        parameters.forEach((name, value) -> query.append(query.isEmpty() ? '?' : '&').append("param_").append(name).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        HttpRequest request = HttpRequest.newBuilder(url.resolve("/" + query))
                .timeout(timeout)
                .header("X-ClickHouse-User", user)
                .header("X-ClickHouse-Key", password)
                .POST(HttpRequest.BodyPublishers.ofString(sql))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ClickHouseException("ClickHouse 에 연결하지 못했다: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClickHouseException("ClickHouse 호출 중 중단되었다", e);
        }
        if (response.statusCode() != 200) {
            throw new ClickHouseException("ClickHouse 가 " + response.statusCode() + " 로 거절했다: " + response.body().strip());
        }
        return response.body();
    }
}
