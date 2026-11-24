package com.errdeck.api.config;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param ingestUrl      DSN 을 만들 때 쓰는 ingest 의 바깥 주소
 * @param allowedOrigins 콘솔 화면이 다른 출처에서 뜰 때(개발 서버) API 를 부를 수 있는 출처
 * @param grafanaUrl     이슈 상세의 그라파나 링크가 가리키는 주소(12.4)
 */
@ConfigurationProperties("errdeck.console")
public record ConsoleProperties(URI ingestUrl, List<String> allowedOrigins, URI grafanaUrl) {
}
