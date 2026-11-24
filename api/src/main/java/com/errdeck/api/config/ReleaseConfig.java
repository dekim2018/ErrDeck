package com.errdeck.api.config;

import com.errdeck.api.app.AccessPolicy;
import com.errdeck.api.app.ReleaseRepository;
import com.errdeck.api.app.ReleaseService;
import com.errdeck.core.files.ArtifactStore;
import com.errdeck.core.files.LocalArtifactStore;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

@Configuration
public class ReleaseConfig {

    /** 소스맵 볼륨. worker 도 같은 볼륨을 같은 경로로 붙인다(7.2). */
    @Bean
    ArtifactStore artifactStore(@Value("${errdeck.files.dir}") String dir) {
        return new LocalArtifactStore(Path.of(dir));
    }

    @Bean
    ReleaseService releaseService(ReleaseRepository releases, ArtifactStore store, AccessPolicy access,
                                  @Value("${errdeck.files.max-artifact-size}") DataSize maxArtifactSize) {
        return new ReleaseService(releases, store, access, maxArtifactSize.toBytes());
    }
}
