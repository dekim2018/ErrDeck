package com.errdeck.api.app.query;

import com.errdeck.api.app.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * api 가 ClickHouse 에 보내는 모든 쿼리를 만드는 곳(15.4, 10.5). 사용자가 볼 수 있는 프로젝트 ID 조건과 기간 조건을 빠짐없이
 * 넣는다. 컨트롤러 하나에서 권한 검사를 빠뜨려도 다른 시스템의 데이터가 나가지 않게 하는 두 번째 방어선이다.
 *
 * <p>값은 SQL 에 이어 붙이지 않고 ClickHouse 의 쿼리 매개변수({@code {이름:타입}})로 넘긴다. 열 이름과 식은 코드에 적힌
 * 상수만 받는다.
 *
 * <pre>
 * ScopedQuery.from("issue_stats_1m", visibleProjectIds, "minute", from, to)
 *         .select("fingerprint", "sum(times) AS times")
 *         .where("fingerprint IN {fps:Array(FixedString(32))}", "fps", fingerprints)
 *         .groupBy("fingerprint")
 * </pre>
 */
public final class ScopedQuery {

    /** 조회 기간의 상한(10.5). */
    public static final Duration MAX_RANGE = Duration.ofDays(30);

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final Pattern PARAMETER = Pattern.compile("[a-z][a-z0-9_]*");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final String table;
    private final TreeSet<Long> projectIds;
    private final String timeColumn;
    private final Instant from;
    private final Instant to;
    private final List<String> select = new ArrayList<>();
    private final List<String> conditions = new ArrayList<>();
    private final List<String> groupBy = new ArrayList<>();
    private final List<String> orderBy = new ArrayList<>();
    private final Map<String, String> parameters = new LinkedHashMap<>();
    private Integer limit;

    private ScopedQuery(String table, Collection<Long> projectIds, String timeColumn, Instant from, Instant to) {
        if (!IDENTIFIER.matcher(table).matches() || !IDENTIFIER.matcher(timeColumn).matches()) {
            throw new IllegalArgumentException("테이블과 시각 열 이름이 틀렸다");
        }
        if (!from.isBefore(to)) {
            throw new ApiException(ApiException.Kind.BAD_REQUEST, "기간의 시작이 끝보다 앞서야 한다");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new ApiException(ApiException.Kind.BAD_REQUEST, "조회 기간은 최대 30일이다");
        }
        this.table = table;
        this.projectIds = new TreeSet<>(projectIds);
        this.timeColumn = timeColumn;
        this.from = from;
        this.to = to;
    }

    /**
     * @param visibleProjectIds 사용자가 볼 수 있는 프로젝트. 비어 있으면 아무 행도 나오지 않는 쿼리가 된다
     */
    public static ScopedQuery from(String table, Collection<Long> visibleProjectIds, String timeColumn, Instant from, Instant to) {
        return new ScopedQuery(table, visibleProjectIds, timeColumn, from, to);
    }

    public ScopedQuery select(String... expressions) {
        select.addAll(List.of(expressions));
        return this;
    }

    /** 조건 하나와 그 안의 매개변수 하나. 값은 문자열로 넘기고 ClickHouse 가 타입에 맞게 읽는다. */
    public ScopedQuery where(String condition, String parameter, Object value) {
        if (!PARAMETER.matcher(parameter).matches() || parameter.startsWith("scope_")) {
            throw new IllegalArgumentException("매개변수 이름이 틀렸다: " + parameter);
        }
        conditions.add(condition);
        parameters.put(parameter, format(value));
        return this;
    }

    public ScopedQuery groupBy(String... expressions) {
        groupBy.addAll(List.of(expressions));
        return this;
    }

    public ScopedQuery orderBy(String... expressions) {
        orderBy.addAll(List.of(expressions));
        return this;
    }

    public ScopedQuery limit(int limit) {
        this.limit = limit;
        return this;
    }

    public String sql() {
        if (select.isEmpty()) {
            throw new IllegalStateException("SELECT 할 열이 없다");
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", select))
                .append(" FROM ").append(table)
                .append(" WHERE ").append(projectIds.isEmpty() ? "0" : "project_id IN {scope_projects:Array(UInt32)}")
                .append(" AND ").append(timeColumn).append(" >= {scope_from:DateTime64(3)}")
                .append(" AND ").append(timeColumn).append(" < {scope_to:DateTime64(3)}");
        for (String condition : conditions) {
            sql.append(" AND (").append(condition).append(')');
        }
        if (!groupBy.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", groupBy));
        }
        if (!orderBy.isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", orderBy));
        }
        if (limit != null) {
            sql.append(" LIMIT ").append(limit);
        }
        return sql.toString();
    }

    /** ClickHouse HTTP 인터페이스에 {@code param_이름} 으로 넘길 값. */
    public Map<String, String> parameters() {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("scope_projects", format(projectIds));
        all.put("scope_from", DATE_TIME.format(from));
        all.put("scope_to", DATE_TIME.format(to));
        all.putAll(parameters);
        return all;
    }

    public Collection<Long> projectIds() {
        return List.copyOf(projectIds);
    }

    /** 배열은 ClickHouse 배열 리터럴로, 그 밖은 문자열로. 배열 안의 문자열은 작은따옴표로 감싸고 이스케이프한다. */
    static String format(Object value) {
        if (value instanceof Collection<?> collection) {
            List<String> items = new ArrayList<>();
            for (Object item : collection) {
                items.add(item instanceof Number ? item.toString() : "'" + item.toString().replace("\\", "\\\\").replace("'", "\\'") + "'");
            }
            return "[" + String.join(",", items) + "]";
        }
        if (value instanceof Instant instant) {
            return DATE_TIME.format(instant);
        }
        return String.valueOf(value);
    }
}
