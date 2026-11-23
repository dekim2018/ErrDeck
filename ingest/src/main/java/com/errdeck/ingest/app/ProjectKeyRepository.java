package com.errdeck.ingest.app;

import com.errdeck.ingest.domain.ProjectKey;
import java.util.Optional;

public interface ProjectKeyRepository {

    Optional<ProjectKey> findByPublicKey(String publicKey);
}
