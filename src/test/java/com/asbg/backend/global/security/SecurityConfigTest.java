package com.asbg.backend.global.security;

import com.asbg.backend.global.presentation.BaseResponse;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS;
import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD;
import static org.springframework.http.HttpHeaders.ORIGIN;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.cors.allowed-origins=https://frontend.example.test, https://other.example.test")
@AutoConfigureMockMvc
@ActiveProfiles("smoke")
@Import(SecurityConfigTest.ProtectedController.class)
class SecurityConfigTest {
    private static final String ALLOWED_ORIGIN = "https://frontend.example.test";
    private static final String BLOCKED_ORIGIN = "https://unlisted.example.test";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ApplicationContext context;

    @Test
    void returnsCommonUnauthorizedAndDoesNotCacheTheRequest() throws Exception {
        var result = mvc.perform(get("/security-test/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_401"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void allowsAuthenticatedRequestsAndReturnsCommonForbiddenOnCsrfDenial() throws Exception {
        mvc.perform(get("/security-test/protected").with(user("test-member")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mvc.perform(post("/security-test/protected").with(user("test-member")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_403"))
                .andExpect(jsonPath("$.message").value("접근 권한이 없습니다."))
                .andExpect(jsonPath("$.data").value(nullValue()));
        mvc.perform(post("/security-test/protected").with(user("test-member")).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void doesNotExposeDefaultUserOrLoginSchemes() throws Exception {
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
        mvc.perform(get("/login")).andExpect(status().isUnauthorized());
        mvc.perform(get("/security-test/protected").with(httpBasic("test-member", "unused")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(WWW_AUTHENTICATE));
    }

    @Test
    void allowsInternalErrorDispatchButRequiresAuthenticationForDirectErrorRequests() throws Exception {
        mvc.perform(get("/error").with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
                    return request;
                }))
                .andExpect(status().isInternalServerError());
        mvc.perform(get("/error")).andExpect(status().isUnauthorized());
    }

    @Test
    void hashesPasswordsWithBcrypt() {
        String rawPassword = "example-password";
        String hash = passwordEncoder.encode(rawPassword);
        assertThat(passwordEncoder).isInstanceOf(BCryptPasswordEncoder.class);
        assertThat(hash).isNotEqualTo(rawPassword);
        assertThat(passwordEncoder.matches(rawPassword, hash)).isTrue();
        assertThat(passwordEncoder.matches("different", hash)).isFalse();
    }

    @Test
    void validatesCorsOriginsAndPreflight() throws Exception {
        mvc.perform(get("/actuator/health").header(ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().doesNotExist(ACCESS_CONTROL_ALLOW_CREDENTIALS));
        mvc.perform(get("/actuator/health").header(ORIGIN, BLOCKED_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(ACCESS_CONTROL_ALLOW_ORIGIN));
        mvc.perform(options("/security-test/protected").header(ORIGIN, ALLOWED_ORIGIN)
                        .header(ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type, Authorization, X-Browser-Id, X-Admission-Token"))
                .andExpect(status().isOk())
                .andExpect(header().string(ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(ACCESS_CONTROL_ALLOW_HEADERS,
                        "Content-Type, Authorization, X-Browser-Id, X-Admission-Token"));
        mvc.perform(options("/security-test/protected").header(ORIGIN, BLOCKED_ORIGIN)
                        .header(ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void preservesSmokeSwaggerAndHealthWithoutExposingSensitiveEndpoints() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
        for (String endpoint : new String[]{"env", "configprops", "heapdump"}) {
            mvc.perform(get("/actuator/" + endpoint)).andExpect(status().isUnauthorized());
            mvc.perform(get("/actuator/" + endpoint).with(user("test-member"))).andExpect(status().isNotFound());
        }
    }

    @RestController
    static class ProtectedController {
        @GetMapping("/security-test/protected")
        BaseResponse<String> read() {
            return BaseResponse.success("allowed");
        }

        @PostMapping("/security-test/protected")
        BaseResponse<String> write() {
            return BaseResponse.success("allowed");
        }
    }
}
