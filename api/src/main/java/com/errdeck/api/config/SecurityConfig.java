package com.errdeck.api.config;

import com.errdeck.api.security.InternalSecretFilter;
import com.errdeck.api.security.ProjectTokenFilter;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * api 의 필터 체인(15.2). 경로마다 체인을 따로 두고, 좁은 경로를 먼저 평가한다. 체인 하나에서 설정을 실수해도 다른 경로로
 * 번지지 않는다.
 *
 * <ol>
 *   <li>{@code /api/internal/**}: 공유 비밀(Alertmanager)</li>
 *   <li>{@code /api/projects/{id}/releases/**} 의 쓰기 요청: 프로젝트 토큰(빌드 스크립트)</li>
 *   <li>{@code /api/**}: 콘솔 사용자의 JWT. {@code /api/auth/**} 만 열어 둔다</li>
 *   <li>그 밖: 상태 확인, 오류 응답, 관리 포트의 지표만 열고 모두 막는다</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecretKey jwtKey(AuthProperties properties) {
        byte[] bytes = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("errdeck.auth.jwt-secret 은 32바이트 이상이어야 한다");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtKey, AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
        // 발급자와 대상이 이 서비스인 토큰만 받는다. 같은 비밀을 다른 서비스와 나눠 쓰게 되어도 섞이지 않는다.
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(properties.audience()));
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(List.of(new JwtIssuerValidator(properties.issuer()), audience)));
        return decoder;
    }

    @Bean
    @Order(1)
    SecurityFilterChain internal(HttpSecurity http, AuthProperties properties) throws Exception {
        return http.securityMatcher("/api/internal/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new InternalSecretFilter(properties.internalSecret()), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain projectToken(HttpSecurity http, JdbcClient jdbc) throws Exception {
        return http.securityMatcher(ProjectTokenFilter::matches)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ProjectTokenFilter(jdbc), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain console(HttpSecurity http, JwtDecoder jwtDecoder, ConsoleProperties console) throws Exception {
        return http.securityMatcher("/api/**")
                // 액세스 토큰을 헤더로 보내므로 CSRF 대상이 아니다. 쿠키를 쓰는 재발급 경로는 SameSite 와 경로 제한으로 막는다(15.2).
                .csrf(csrf -> csrf.disable())
                .cors(c -> c.configurationSource(cors(console)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.bearerTokenResolver(bearerTokenResolver()).jwt(jwt -> jwt.decoder(jwtDecoder)))
                .build();
    }

    @Bean
    @Order(4)
    SecurityFilterChain rest(HttpSecurity http, @Value("${management.server.port:-1}") int managementPort) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error", "/actuator/health", "/actuator/health/**").permitAll()
                        // 지표는 관리 포트로 온 요청만 연다. 관리 포트는 프록시에 연결하지 않는다(13.2). 관리 포트를 나누지 않은 로컬 개발에서는 연다.
                        .requestMatchers(request -> "/actuator/prometheus".equals(request.getRequestURI())
                                && (managementPort <= 0 || request.getLocalPort() == managementPort)).permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    /** {@code /api/auth/**} 에서는 Authorization 헤더를 읽지 않는다. 만료된 토큰을 붙인 채 로그인이나 재발급을 해도 401 이 나지 않게 한다. */
    private static BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
        return (HttpServletRequest request) -> request.getRequestURI().startsWith("/api/auth/") ? null : resolver.resolve(request);
    }

    /** 콘솔 개발 서버처럼 다른 출처의 화면이 API 를 부를 수 있게 한다. 리프레시 쿠키 때문에 자격 증명을 허용한다. */
    private static CorsConfigurationSource cors(ConsoleProperties console) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(console.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
