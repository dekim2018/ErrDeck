package com.errdeck.api.out.clickhouse;

import com.errdeck.api.app.query.ScopedQuery;
import com.errdeck.core.json.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * api 사용자(읽기 전용, 실행 시간 10초, 메모리 512MB, 스레드 2개, 10.5)로 {@link ScopedQuery} 만 실행한다.
 * 쿼리 문자열을 직접 받는 메서드는 두지 않는다. 프로젝트 조건이 빠진 쿼리가 나갈 길을 막는다.
 */
public class ClickHouseReader {

    /** 동시 조회 상한(14.3). 가상 스레드에는 천장이 없어 직접 둔다. 17 작업에서 상한 없이 초당 300번 조회하자 api 가 힙 부족으로 죽었다. */
    public static final int MAX_CONCURRENT = 8;
    private static final long WAIT_MILLIS = 500;

    private final ClickHouseHttp clickHouse;
    private final String database;
    private final java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(MAX_CONCURRENT);

    public ClickHouseReader(ClickHouseHttp clickHouse, String database) {
        this.clickHouse = clickHouse;
        this.database = database;
    }

    /** 한 줄에 JSON 하나로 받아 행마다 맵으로 돌려준다. 정수는 Long, 실수는 Double 이다. 자리가 0.5초 안에 나지 않으면 503 이다. */
    public List<Map<String, Object>> query(ScopedQuery query) {
        try {
            if (!slots.tryAcquire(WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw new com.errdeck.api.app.ApiException(com.errdeck.api.app.ApiException.Kind.UNAVAILABLE, "조회가 몰려 잠시 뒤 다시 해야 한다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new com.errdeck.api.app.ApiException(com.errdeck.api.app.ApiException.Kind.UNAVAILABLE, "조회가 중단되었다");
        }
        String body;
        try {
            body = clickHouse.execute(database, query.sql() + " FORMAT JSONEachRow", query.parameters());
        } finally {
            slots.release();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (!line.isBlank()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) Json.parse(line);
                rows.add(row);
            }
        }
        return rows;
    }
}
