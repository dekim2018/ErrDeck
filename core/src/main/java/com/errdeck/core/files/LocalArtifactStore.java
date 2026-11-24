package com.errdeck.core.files;

import com.errdeck.core.envelope.Gzip;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 로컬 볼륨 어댑터. 위치는 {@code {기준}/{프로젝트}/{릴리스의 SHA-256}/{파일 이름의 SHA-256}.gz} 로 정해져 있어,
 * worker 는 DB를 묻지 않고 (프로젝트, 릴리스, 파일 이름)만으로 찾는다(CHOICES 15-1). 해시를 쓰는 것은 릴리스와 파일 이름에
 * 경로 문자({@code ../})가 섞여도 기준 폴더 밖으로 나가지 못하게 하기 위해서다.
 */
public final class LocalArtifactStore implements ArtifactStore {

    /** 압축을 푼 소스맵 하나의 상한. 작게 압축된 거대한 파일로 메모리를 다 쓰지 않게 한다. */
    static final long MAX_UNCOMPRESSED = 64L * 1024 * 1024;

    private final Path root;

    public LocalArtifactStore(Path root) {
        this.root = root;
    }

    @Override
    public String put(long projectId, String release, String name, byte[] content) {
        Path path = path(projectId, release, name);
        try {
            Files.createDirectories(path.getParent());
            // 다 쓴 뒤에 이름을 바꿔, 읽는 쪽이 반쯤 쓴 파일을 보지 않게 한다.
            Path temp = Files.createTempFile(path.getParent(), "upload-", ".tmp");
            Files.write(temp, Gzip.compress(content));
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return root.relativize(path).toString();
    }

    @Override
    public Optional<byte[]> get(long projectId, String release, String name) {
        try {
            return Optional.of(Gzip.decompress(Files.readAllBytes(path(projectId, release, name)), MAX_UNCOMPRESSED));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path path(long projectId, String release, String name) {
        return root.resolve(Long.toString(projectId)).resolve(sha256(release)).resolve(sha256(name) + ".gz");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
