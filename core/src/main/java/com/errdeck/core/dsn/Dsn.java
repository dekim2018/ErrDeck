package com.errdeck.core.dsn;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * SDK가 이벤트를 어디로, 어떤 키로 보낼지 담은 주소. 형식은 {@code https://{공개키}@{호스트}/{프로젝트ID}} 다(5.2).
 *
 * <p>공개키는 브라우저 코드에 그대로 들어가는 값이라 비밀이 아니다. 그래서 {@link #toString()} 에서도 가리지 않는다.
 */
public record Dsn(String scheme, String publicKey, String host, int port, long projectId) {

    /** 프로젝트 ID는 ClickHouse의 UInt32 열에 들어간다(10.2). */
    static final long MAX_PROJECT_ID = 0xFFFF_FFFFL;

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern PROJECT_ID = Pattern.compile("[1-9][0-9]{0,9}");

    public Dsn {
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalArgumentException("DSN의 스킴은 https 나 http 여야 한다: " + scheme);
        }
        if (publicKey == null || !KEY.matcher(publicKey).matches()) {
            throw new IllegalArgumentException("DSN의 공개키는 영문, 숫자, '_', '-' 로만 이루어져야 한다");
        }
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("DSN에 호스트가 없다");
        }
        if (port != -1 && (port < 1 || port > 65535)) {
            throw new IllegalArgumentException("DSN의 포트가 범위를 벗어난다: " + port);
        }
        if (projectId < 1 || projectId > MAX_PROJECT_ID) {
            throw new IllegalArgumentException("DSN의 프로젝트 ID가 범위를 벗어난다: " + projectId);
        }
    }

    public static Dsn parse(String dsn) {
        if (dsn == null || dsn.isBlank()) {
            throw new IllegalArgumentException("DSN이 비어 있다");
        }
        URI uri;
        try {
            uri = new URI(dsn.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("DSN의 형식이 틀렸다: " + e.getMessage(), e);
        }
        if (uri.getRawUserInfo() == null) {
            throw new IllegalArgumentException("DSN에 공개키가 없다");
        }
        if (uri.getRawUserInfo().contains(":")) {
            throw new IllegalArgumentException("DSN에 비밀번호를 넣지 않는다");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("DSN에 쿼리나 프래그먼트를 넣지 않는다");
        }
        String path = uri.getRawPath();
        if (path == null || !path.startsWith("/") || !PROJECT_ID.matcher(path.substring(1)).matches()) {
            throw new IllegalArgumentException("DSN의 경로는 /{프로젝트ID} 여야 한다: " + path);
        }
        return new Dsn(uri.getScheme(), uri.getRawUserInfo(), uri.getHost(), uri.getPort(), Long.parseLong(path.substring(1)));
    }

    /** SDK가 봉투를 보낼 주소. */
    public URI envelopeUri() {
        return URI.create(origin() + "/ingest/" + projectId + "/envelope");
    }

    private String origin() {
        return scheme + "://" + host + (port == -1 ? "" : ":" + port);
    }

    @Override
    public String toString() {
        return scheme + "://" + publicKey + "@" + host + (port == -1 ? "" : ":" + port) + "/" + projectId;
    }
}
