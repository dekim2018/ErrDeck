package com.errdeck.core.files;

import java.util.Optional;

/**
 * 릴리스 파일(소스맵) 저장소(7.2). 처음에는 로컬 볼륨 어댑터만 두고, S3 호환 저장소는 필요할 때 더한다.
 * api 가 쓰고 worker 가 읽는다. 둘이 같은 볼륨을 붙인다.
 */
public interface ArtifactStore {

    /** 압축해서 저장하고 저장한 위치를 돌려준다. 같은 위치에 있으면 덮어쓴다. */
    String put(long projectId, String release, String name, byte[] content);

    /** 압축을 풀어 돌려준다. 없으면 비어 있다. */
    Optional<byte[]> get(long projectId, String release, String name);
}
