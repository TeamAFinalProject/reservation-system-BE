package com.asbg.backend.domain.member.service;

import com.asbg.backend.domain.member.domain.Member;
import com.asbg.backend.domain.member.repository.MemberRepository;
import com.asbg.backend.global.config.SessionStoreConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@Profile(SessionStoreConfig.ENABLED_PROFILE)
class MemberAuthenticationConfig {
    @Bean
    AuthenticationManager memberAuthenticationManager(MemberRepository members, PasswordEncoder passwordEncoder) {
        var provider = new DaoAuthenticationProvider(username ->
                members.findByUsername(username)
                        .map(MemberCredentials::new)
                        .orElseThrow(() -> new UsernameNotFoundException("회원 인증 실패")));
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    static final class MemberCredentials extends User {
        private final long memberId;

        MemberCredentials(Member member) {
            super(member.getUsername(), member.getPassword(), List.of());
            memberId = member.getMemberId();
        }

        long memberId() {
            return memberId;
        }
    }
}
