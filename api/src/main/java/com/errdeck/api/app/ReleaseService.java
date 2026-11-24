package com.errdeck.api.app;

import com.errdeck.api.app.ApiException.Kind;
import com.errdeck.api.app.ReleaseRepository.Release;
import com.errdeck.api.app.ReleaseRepository.ReleaseSummary;
import com.errdeck.api.app.ReleaseRepository.Token;
import com.errdeck.api.domain.Role;
import com.errdeck.core.files.ArtifactStore;
import com.errdeck.core.sourcemap.SourceMap;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 릴리스 등록, 소스맵 업로드, 프로젝트 토큰(7.2, 15.2). 업로드는 빌드 스크립트가 프로젝트 토큰으로 한다.
 * 토큰 발급과 폐기는 콘솔의 OWNER 가 한다(15.4 의 "키와 토큰").
 */
public class ReleaseService {

    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._@+-]{0,99}");
    private static final Pattern ARTIFACT = Pattern.compile("[A-Za-z0-9_@.+-][A-Za-z0-9_@.+/-]{0,254}\\.map");

    public record IssuedToken(long id, String name, String token) {
    }

    private final ReleaseRepository releases;
    private final ArtifactStore store;
    private final AccessPolicy access;
    private final long maxArtifactBytes;
    private final SecureRandom random = new SecureRandom();

    public ReleaseService(ReleaseRepository releases, ArtifactStore store, AccessPolicy access, long maxArtifactBytes) {
        this.releases = releases;
        this.store = store;
        this.access = access;
        this.maxArtifactBytes = maxArtifactBytes;
    }

    public Release register(long projectId, String version) {
        return releases.upsertRelease(projectId, validVersion(version));
    }

    /**
     * 소스맵 하나를 올린다. 소스맵인지 해석해 보고 받는다. 깨진 파일을 받아 두면 이벤트가 올 때마다 해석에 실패한다.
     * 릴리스가 없으면 만든다. 빌드 스크립트가 등록을 빼먹어도 짝이 맞게 한다.
     */
    public void upload(long projectId, String version, String name, byte[] content) {
        if (name == null || !ARTIFACT.matcher(name).matches() || name.contains("..")) {
            throw new ApiException(Kind.BAD_REQUEST, "파일 이름은 .map 으로 끝나는 상대 경로다(예: assets/index-a1.js.map)");
        }
        if (content.length == 0 || content.length > maxArtifactBytes) {
            throw new ApiException(Kind.BAD_REQUEST, "소스맵은 비어 있지 않고 " + maxArtifactBytes / 1024 / 1024 + "MB 이하여야 한다");
        }
        try {
            SourceMap.parse(content);
        } catch (IllegalArgumentException e) {
            throw new ApiException(Kind.BAD_REQUEST, "소스맵이 아니다: " + e.getMessage());
        }
        Release release = register(projectId, version);
        String path = store.put(projectId, release.version(), name, content);
        releases.upsertArtifact(release.id(), name, content.length, sha256(content), path);
    }

    public List<ReleaseSummary> list(long userId, long projectId) {
        access.requireProject(userId, projectId, Role.VIEWER);
        return releases.releases(projectId, 50);
    }

    /** 원문은 한 번만 보여 주고 해시만 저장한다(10.1). */
    public IssuedToken createToken(long userId, long projectId, String name) {
        access.requireProject(userId, projectId, Role.OWNER);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = "edt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long id = releases.createToken(projectId, name.strip(), AuthService.hash(token));
        return new IssuedToken(id, name.strip(), token);
    }

    public List<Token> tokens(long userId, long projectId) {
        access.requireProject(userId, projectId, Role.OWNER);
        return releases.tokens(projectId);
    }

    public void revokeToken(long userId, long projectId, long tokenId) {
        access.requireProject(userId, projectId, Role.OWNER);
        if (!releases.revokeToken(projectId, tokenId)) {
            throw ApiException.notFound("토큰");
        }
    }

    private static String validVersion(String version) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new ApiException(Kind.BAD_REQUEST, "릴리스 이름은 영문, 숫자, '.', '_', '@', '+', '-' 로 100자 이하다");
        }
        return version;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
