package com.asbg.backend.global.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.springframework.http.HttpHeaders.ORIGIN;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.cors.allowed-origins=")
@AutoConfigureMockMvc
@ActiveProfiles({"prod", "test"})
class ProdSecurityTest {
    @Autowired
    private MockMvc mvc;

    @Test
    void keepsHealthPublicAndSwaggerAndSensitiveActuatorEndpointsDisabled() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        for (String path : new String[]{"/v3/api-docs", "/swagger-ui/index.html",
                "/actuator/env", "/actuator/configprops", "/actuator/heapdump"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(user("test-member"))).andExpect(status().isNotFound());
        }
    }

    @Test
    void rejectsCrossOriginRequestsWhenNoOriginIsConfigured() throws Exception {
        mvc.perform(get("/actuator/health").header(ORIGIN, "https://unlisted.example.test"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
