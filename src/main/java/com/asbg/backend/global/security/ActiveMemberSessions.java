package com.asbg.backend.global.security;

import com.asbg.backend.global.config.SessionStoreConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@Profile(SessionStoreConfig.ENABLED_PROFILE)
public class ActiveMemberSessions {
    private static final String PREFIX = "asbg:auth:active:";
    private static final String PENDING_PREFIX = "pending:";
    private static final Duration PENDING_TIMEOUT = Duration.ofSeconds(10);
    private static final RedisScript<Long> CONFIRM = RedisScript.of("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                redis.call('psetex', KEYS[1], ARGV[3], ARGV[2])
                return 1
            end
            return 0
            """, Long.class);
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            local owner = redis.call('get', KEYS[1])
            if owner == ARGV[1] or owner == ARGV[2] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final SessionRepository<?> sessions;
    private final Duration absoluteTimeout;

    public ActiveMemberSessions(StringRedisTemplate redis, SessionRepository<?> sessions,
                                @Value("${app.auth.absolute-timeout}") Duration absoluteTimeout) {
        this.redis = redis;
        this.sessions = sessions;
        this.absoluteTimeout = absoluteTimeout;
    }

    public boolean isActive(long memberId, String sessionId) {
        return sessionId.equals(redis.opsForValue().get(PREFIX + memberId));
    }

    public boolean claim(long memberId, String sessionId) {
        String key = PREFIX + memberId;
        String existing = redis.opsForValue().get(key);
        if (existing != null) {
            if (existing.startsWith(PENDING_PREFIX) || sessions.findById(existing) != null) {
                return false;
            }
            release(memberId, existing);
        }
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, PENDING_PREFIX + sessionId, PENDING_TIMEOUT));
    }

    public boolean confirm(long memberId, String sessionId) {
        return Long.valueOf(1).equals(redis.execute(CONFIRM, List.of(PREFIX + memberId),
                PENDING_PREFIX + sessionId, sessionId, Long.toString(absoluteTimeout.toMillis())));
    }

    public void release(long memberId, String sessionId) {
        redis.execute(RELEASE, List.of(PREFIX + memberId), sessionId, PENDING_PREFIX + sessionId);
    }
}
