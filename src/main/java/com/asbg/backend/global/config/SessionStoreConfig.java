package com.asbg.backend.global.config;

import com.asbg.backend.global.exception.ErrorCode;
import com.asbg.backend.global.presentation.BaseResponse;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.session.FlushMode;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@Profile(SessionStoreConfig.ENABLED_PROFILE)
@EnableRedisHttpSession(redisNamespace = SessionStoreConfig.SESSION_NAMESPACE, flushMode = FlushMode.IMMEDIATE)
public class SessionStoreConfig {
    public static final String ENABLED_PROFILE = "!smoke & (!test | valkey-test)";
    public static final String SESSION_NAMESPACE = "asbg:auth:session";
    public static final String SESSION_COOKIE = "ASBG_SESSION";

    @Bean
    SessionRepositoryCustomizer<RedisSessionRepository> redisSessionCustomizer(@Value("${app.auth.idle-timeout}") Duration idleTimeout,
                           @Value("${app.auth.absolute-timeout}") Duration absoluteTimeout) {
        if (idleTimeout.toSeconds() < 1 || idleTimeout.toSeconds() > Integer.MAX_VALUE
                || absoluteTimeout.toSeconds() < 1) {
            throw new IllegalArgumentException("세션 만료 시간은 1초 이상이어야 합니다.");
        }
        return repository -> repository.setDefaultMaxInactiveInterval(idleTimeout);
    }

    @Bean
    DefaultCookieSerializer cookieSerializer(Environment environment) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(SESSION_COOKIE);
        serializer.setCookiePath("/");
        serializer.setSameSite("Lax");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(environment.acceptsProfiles(Profiles.of("prod")));
        return serializer;
    }

    @Bean
    FilterRegistrationBean<SessionRepositoryFilter<?>> redisSessionFilter(SessionRepositoryFilter<?> filter) {
        var registration = new FilterRegistrationBean<SessionRepositoryFilter<?>>(filter);
        registration.setName("springSessionRepositoryFilter");
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER);
        return registration;
    }

    @Bean
    FilterRegistrationBean<Filter> sessionStoreFailureFilter(JsonMapper mapper) {
        Filter filter = (request, response, chain) -> {
            var httpResponse = (jakarta.servlet.http.HttpServletResponse) response;
            try {
                chain.doFilter(request, response);
            } catch (DataAccessException exception) {
                if (httpResponse.isCommitted()) {
                    throw exception;
                }
                var unavailable = ErrorCode.SERVICE_UNAVAILABLE;
                byte[] body = mapper.writeValueAsBytes(BaseResponse.failure(unavailable, null));
                httpResponse.resetBuffer();
                httpResponse.setStatus(unavailable.status().value());
                httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
                httpResponse.setContentLength(body.length);
                httpResponse.getOutputStream().write(body);
            }
        };
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
