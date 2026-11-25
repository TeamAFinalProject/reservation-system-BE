package com.asbg.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration(proxyBeanMethods = false)
@Profile("!smoke")
@EnableJpaAuditing
public class JpaAuditingConfig {
}
