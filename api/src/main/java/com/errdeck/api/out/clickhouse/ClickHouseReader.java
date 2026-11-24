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

    private final ClickHouseHttp clickHouse;
    private final String database;

    public ClickHouseReader(ClickHouseHttp clickHouse, String database) {
        this.clickHouse = clickHouse;
        this.database = database;
    }

    /** 한 줄에 JSON 하나로 받아 행마다 맵으로 돌려준다. 정수는 Long, 실수는 Double 이다. */
    public List<Map<String, Object>> query(ScopedQuery query) {
        String body = clickHouse.execute(database, query.sql() + " FORMAT JSONEachRow", query.parameters());
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
