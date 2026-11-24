package com.errdeck.core.net;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * 사용자가 넣은 주소(업타임 모니터, 알림 웹훅)를 서버가 대신 부르기 전에 확인한다(9.4). 호스트를 직접 DNS 로 해석해
 * 나온 주소 중 하나라도 루프백, 사설, 링크 로컬, 공유 대역이면 거부한다. 로컬 개발에서 같은 도커 네트워크의 서비스를
 * 부를 수 있게 허용할 호스트를 따로 받는다.
 */
public final class UrlGuard {

    private final Set<String> allowedHosts;

    public UrlGuard(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** @throws IllegalArgumentException 부를 수 없는 주소 */
    public URI check(String url) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("주소 형식이 틀렸다");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("http 나 https 주소만 쓸 수 있다");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("호스트가 없다");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("주소에 사용자 정보를 넣을 수 없다");
        }
        if (allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            return uri;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("호스트를 찾을 수 없다: " + host);
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                throw new IllegalArgumentException("내부 주소로는 보낼 수 없다: " + host + " → " + address.getHostAddress());
            }
        }
        return uri;
    }

    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            // 100.64.0.0/10(통신사 공유 대역), 0.0.0.0/8
            return first == 0 || first == 100 && second >= 64 && second <= 127;
        }
        if (address instanceof Inet6Address) {
            // fc00::/7 고유 로컬
            return (b[0] & 0xfe) == 0xfc;
        }
        return false;
    }
}
