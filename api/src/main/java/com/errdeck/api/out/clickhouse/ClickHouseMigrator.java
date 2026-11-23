package com.errdeck.api.out.clickhouse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 번호 붙인 SQL 파일({@code clickhouse/001_이름.sql})을 순서대로 실행하고, 실행한 번호를 {@code schema_migrations} 에 남긴다(17.6).
 *
 * <p>Flyway 는 ClickHouse 를 제대로 지원하지 않아 직접 만든다. 같은 번호는 다시 실행하지 않는다.
 * 파일 안의 문장도 {@code IF NOT EXISTS} 로 써서, 번호를 남기기 전에 멈췄다 다시 돌아도 같은 상태가 된다.
 */
public final class ClickHouseMigrator {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseMigrator.class);
    private static final Pattern FILE = Pattern.compile("(\\d{3})_[a-z0-9_]+\\.sql");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-z_]+)}");
    /** 자리 표시에 넣는 값은 SQL 에 그대로 들어가므로 따옴표나 공백이 섞이지 않게 막는다. */
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9_-]+");

    public record Migration(int version, String name, String sql) {
    }

    private final ClickHouseHttp clickHouse;
    private final String database;
    private final Map<String, String> placeholders;

    public ClickHouseMigrator(ClickHouseHttp clickHouse, String database, Map<String, String> placeholders) {
        placeholders.forEach((key, value) -> {
            if (value == null || !SAFE_VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("ClickHouse 스키마의 " + key + " 값은 영문, 숫자, '_', '-' 로만 이루어져야 한다");
            }
        });
        this.clickHouse = clickHouse;
        this.database = database;
        this.placeholders = Map.copyOf(placeholders);
    }

    /** @return 이번에 실행한 번호들 */
    public List<Integer> migrate() {
        return migrate(load());
    }

    List<Integer> migrate(List<Migration> migrations) {
        clickHouse.execute(null, "CREATE DATABASE IF NOT EXISTS " + database);
        clickHouse.execute(database, """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                  version    UInt32,
                  name       String,
                  applied_at DateTime DEFAULT now()
                )
                ENGINE = MergeTree
                ORDER BY version""");
        Set<Integer> applied = appliedVersions();
        List<Integer> ran = new ArrayList<>();
        for (Migration migration : migrations) {
            if (applied.contains(migration.version())) {
                continue;
            }
            for (String statement : statements(substitute(migration.sql()))) {
                clickHouse.execute(database, statement);
            }
            clickHouse.execute(database, "INSERT INTO schema_migrations (version, name) VALUES ("
                    + migration.version() + ", '" + migration.name() + "')");
            log.info("ClickHouse 스키마 {} 을 적용했다", migration.name());
            ran.add(migration.version());
        }
        return ran;
    }

    public Set<Integer> appliedVersions() {
        Set<Integer> versions = new HashSet<>();
        for (String line : clickHouse.execute(database, "SELECT version FROM schema_migrations FORMAT TabSeparated").split("\n")) {
            if (!line.isBlank()) {
                versions.add(Integer.parseInt(line.strip()));
            }
        }
        return versions;
    }

    static List<Migration> load() {
        try {
            List<Migration> migrations = new ArrayList<>();
            Set<Integer> versions = new HashSet<>();
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:clickhouse/*.sql")) {
                Matcher name = FILE.matcher(resource.getFilename());
                if (!name.matches()) {
                    throw new IllegalStateException("ClickHouse 스키마 파일 이름은 001_이름.sql 형식이어야 한다: " + resource.getFilename());
                }
                int version = Integer.parseInt(name.group(1));
                if (!versions.add(version)) {
                    throw new IllegalStateException("ClickHouse 스키마 번호가 겹친다: " + version);
                }
                migrations.add(new Migration(version, resource.getFilename(), resource.getContentAsString(StandardCharsets.UTF_8)));
            }
            migrations.sort(Comparator.comparingInt(Migration::version));
            return migrations;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String substitute(String sql) {
        Matcher matcher = PLACEHOLDER.matcher(sql);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = matcher.group(1).equals("database") ? database : placeholders.get(matcher.group(1));
            if (value == null) {
                throw new IllegalStateException("ClickHouse 스키마의 자리 표시에 값이 없다: " + matcher.group());
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** HTTP 인터페이스는 요청 하나에 문장 하나만 받는다. 줄 끝의 세미콜론으로 나누고 주석만 있는 조각은 버린다. */
    static List<String> statements(String sql) {
        List<String> statements = new ArrayList<>();
        for (String chunk : sql.split(";\\s*(\\n|$)")) {
            boolean hasCode = chunk.lines().anyMatch(line -> !line.isBlank() && !line.strip().startsWith("--"));
            if (hasCode) {
                statements.add(chunk.strip());
            }
        }
        return statements;
    }
}
