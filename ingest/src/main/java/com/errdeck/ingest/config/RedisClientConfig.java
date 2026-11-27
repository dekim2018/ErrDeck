package com.errdeck.ingest.config;

import io.lettuce.core.resource.Delay;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.resolver.dns.DefaultDnsCache;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis 가 다른 주소로 다시 떠도 따라가게 한다(30 작업).
 *
 * <p>Lettuce 는 클래스패스에 netty-resolver-dns 가 있으면 그것으로 이름을 풀고, 그 캐시는 DNS 응답의 TTL 을 그대로 따른다.
 * 도커 내장 DNS 는 TTL 600초를 주므로 Redis 컨테이너를 새로 만들면 최대 10분 동안 옛 주소로만 다시 연결했다.
 * 캐시의 최대 TTL 을 짧게 두고, 재연결 간격의 상한(기본 30초)도 줄여 Redis 가 돌아온 뒤 오래 503 을 주지 않게 한다.
 * ingest, worker, api 가 같은 클래스를 하나씩 가진다. 셋이 함께 쓰는 스프링 모듈이 없다.
 */
@Configuration(proxyBeanMethods = false)
public class RedisClientConfig {

    @Bean
    ClientResourcesBuilderCustomizer redisReconnect(@Value("${errdeck.redis.dns-max-ttl}") Duration dnsMaxTtl,
                                                    @Value("${errdeck.redis.reconnect-max-delay}") Duration reconnectMaxDelay) {
        return builder -> builder
                .addressResolverGroup(addressResolverGroup(dnsMaxTtl))
                .reconnectDelay(() -> Delay.exponential(Duration.ofMillis(100), reconnectMaxDelay, 2, TimeUnit.MILLISECONDS));
    }

    static DnsAddressResolverGroup addressResolverGroup(Duration maxTtl) {
        return new DnsAddressResolverGroup(new DnsNameResolverBuilder()
                .datagramChannelType(NioDatagramChannel.class)
                .socketChannelType(NioSocketChannel.class)
                .resolveCache(dnsCache(maxTtl)));
    }

    /** 최소 TTL 0, 최대 TTL 은 설정값, 실패한 조회는 1초만 기억한다. Redis 가 아직 안 떠 이름이 없을 때 오래 굳지 않게 한다. */
    static DefaultDnsCache dnsCache(Duration maxTtl) {
        return new DefaultDnsCache(0, Math.toIntExact(maxTtl.toSeconds()), 1);
    }
}
