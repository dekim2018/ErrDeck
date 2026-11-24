package com.errdeck.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * 서비스 두 개를 지나는 요청(18 작업). {@code /demo/chain/{id}} 가 다른 데모 앱의 {@code /demo/items/{id}} 를 부르고,
 * 그쪽은 반복문 안에서 쿼리를 날린다(N+1). 추적 화면에서 한 그림과 반복 쿼리 표시를 확인한다.
 */
@RestController
public class ChainController {

    private final RestClient downstream;
    private final JdbcClient jdbc;

    public ChainController(RestClient.Builder builder, @Value("${demo.downstream-url}") String downstreamUrl, JdbcClient jdbc) {
        this.downstream = builder.baseUrl(downstreamUrl).build();
        this.jdbc = jdbc;
    }

    @GetMapping("/demo/chain/{id}")
    public Map<String, Object> chain(@PathVariable int id) {
        List<?> items = downstream.get().uri("/demo/items/{id}", id).retrieve().body(List.class);
        return Map.of("order", id, "items", items == null ? List.of() : items);
    }

    @GetMapping("/demo/items/{id}")
    public List<Map<String, Object>> items(@PathVariable int id) {
        List<Integer> ids = jdbc.sql("SELECT id FROM item WHERE order_id = ?").param(id % 5).query(Integer.class).list();
        List<Map<String, Object>> out = new ArrayList<>();
        // 일부러 한 건씩 읽는다. 추적에서 같은 쿼리가 반복으로 접혀 보여야 한다(8.5).
        for (Integer itemId : ids) {
            out.add(jdbc.sql("SELECT id, name FROM item WHERE id = ?").param(itemId).query().singleRow());
        }
        return out;
    }

    @Configuration(proxyBeanMethods = false)
    static class Clients {

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }
}
