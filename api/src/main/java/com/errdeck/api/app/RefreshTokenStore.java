package com.errdeck.api.app;

import java.time.Duration;

/** 리프레시 토큰. 원문은 두지 않고 SHA-256 만 둔다. */
public interface RefreshTokenStore {

    /** 회전 결과. */
    sealed interface Use {
        /** 처음 썼다. 새 토큰을 저장했다. */
        record Active(long userId) implements Use {
        }

        /** 방금 쓴 토큰이 다시 왔다. 탭 두 개나 재시도로 본다. */
        record RecentlyUsed(long userId) implements Use {
        }

        /** 오래전에 쓴 토큰이 다시 왔다. 탈취로 본다. */
        record Reused(long userId) implements Use {
        }

        /** 없거나 만료되었다. */
        record Unknown() implements Use {
        }
    }

    void save(String tokenHash, long userId, Duration ttl);

    /** 쓴 것으로 표시하고 새 토큰을 저장한다. 같은 토큰으로 동시에 와도 한 번만 Active 가 된다. */
    Use rotate(String tokenHash, String nextHash, Duration ttl);

    void delete(String tokenHash);

    /** 그 사용자의 아직 쓰지 않은 토큰을 모두 지운다. */
    void revokeAll(long userId);
}
