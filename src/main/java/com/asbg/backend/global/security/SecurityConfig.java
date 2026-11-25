package com.asbg.backend.global.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            Environment environment,
            CorsConfigurationSource corsConfigurationSource,
            CustomAuthenticationEntryPoint entryPoint,
            CustomAccessDeniedHandler accessDeniedHandler
    ) {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll();
                    requests.requestMatchers(HttpMethod.HEAD, "/actuator/health").permitAll();
                    if (environment.acceptsProfiles(Profiles.of("smoke & !prod"))) {
                        String[] swaggerPaths = {"/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
                                "/swagger-ui.html", "/swagger-ui/**"};
                        requests.requestMatchers(HttpMethod.GET, swaggerPaths).permitAll();
                        requests.requestMatchers(HttpMethod.HEAD, swaggerPaths).permitAll();
                    }
                    requests.anyRequest().authenticated();
                })
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:}") List<String> configuredOrigins
    ) {
        List<String> origins = configuredOrigins.stream()
                .map(String::strip)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (origins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalArgumentException("CORS에는 명시적인 Origin만 설정할 수 있습니다.");
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Accept", "Authorization", "Content-Type"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
