package com.asbg.backend.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("smoke")
class OpenApiConfigTest {
    @Autowired
    private MockMvc mvc;

    @Test
    void publishesReusableContractsWithoutAddingBusinessOperations() throws Exception {
        String responses = "$.components.responses";
        String json = ".content['application/json']";
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("reservation API"))
                .andExpect(jsonPath("$.paths").isEmpty())
                .andExpect(jsonPath("$.components.securitySchemes").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.BaseResponse.properties.success").exists())
                .andExpect(jsonPath("$.components.schemas.BaseResponse.properties.code").exists())
                .andExpect(jsonPath("$.components.schemas.BaseResponse.properties.message").exists())
                .andExpect(jsonPath("$.components.schemas.BaseResponse.properties.data").exists())
                .andExpect(jsonPath("$.components.schemas.FieldErrorResponse.properties.errors").exists())
                .andExpect(jsonPath(responses + ".InvalidInput" + json + ".example.code").value("COMMON_400"))
                .andExpect(jsonPath(responses + ".InvalidInput" + json + ".example.data.errors[0].field").value("username"))
                .andExpect(jsonPath(responses + ".Unauthorized" + json + ".schema['$ref']")
                        .value("#/components/schemas/BaseResponse"))
                .andExpect(jsonPath(responses + ".Unauthorized" + json + ".example.code").value("COMMON_401"))
                .andExpect(jsonPath(responses + ".Unauthorized" + json + ".example.data").value(nullValue()))
                .andExpect(jsonPath(responses + ".Forbidden" + json + ".example.code").value("COMMON_403"));
    }

    @Test
    void keepsSwaggerUiAndHealthPublicInSmoke() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
