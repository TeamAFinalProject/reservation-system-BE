package com.asbg.backend.global.exception;

import com.asbg.backend.global.presentation.BaseResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpStatus.NOT_ACCEPTABLE;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_XML;
import static org.springframework.http.MediaType.TEXT_PLAIN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GlobalExceptionHandlerTest.TestController.class)
@Import(GlobalExceptionHandlerTest.TestController.class)
class GlobalExceptionHandlerTest {
    @Autowired
    private MockMvc mvc;

    @Test
    void preservesCreatedStatusAndSupportsNoContent() throws Exception {
        mvc.perform(post("/sample").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"example@example.org","password":"example-password"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("COMMON_SUCCESS"))
                .andExpect(jsonPath("$.message").value("요청에 성공했습니다."))
                .andExpect(jsonPath("$.data").value(1));
        mvc.perform(get("/sample/empty")).andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @Test
    void mapsBusinessException() throws Exception {
        expectFailure(mvc.perform(get("/sample/conflict")), 409, "COMMON_409")
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void returnsFieldErrorsWithoutRejectedValues() throws Exception {
        expectFailure(mvc.perform(post("/sample").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"invalid","password":"hidden"}
                                """)), 400, "COMMON_400")
                .andExpect(jsonPath("$.data.errors[*].field").value(hasItems("email", "password")))
                .andExpect(jsonPath("$.data.errors[?(@.field == 'email')].message")
                        .value(hasItems("올바른 이메일 형식이 아닙니다.")))
                .andExpect(jsonPath("$.data.errors[*].rejectedValue").isEmpty())
                .andExpect(content().string(not(containsString("hidden"))));
    }

    @Test
    void mapsInvalidAndMissingParameters() throws Exception {
        expectFailure(mvc.perform(get("/sample/count").param("count", "0")), 400, "COMMON_400")
                .andExpect(jsonPath("$.data.errors[0].field").value("count"));
        expectFailure(mvc.perform(get("/sample/count").param("count", "text")), 400, "COMMON_400");
        expectFailure(mvc.perform(get("/sample/count")), 400, "COMMON_400");
    }

    @Test
    void mapsMalformedJsonAndFrameworkErrors() throws Exception {
        expectFailure(mvc.perform(post("/sample").contentType(APPLICATION_JSON).content("{")), 400, "COMMON_400_BODY");
        expectFailure(mvc.perform(post("/sample").contentType(TEXT_PLAIN).content("plain")), 415, "COMMON_415");
        expectFailure(mvc.perform(get("/sample")), 405, "COMMON_405").andExpect(header().string("Allow", "POST"));
        expectFailure(mvc.perform(get("/missing")), 404, "COMMON_404");
        expectFailure(mvc.perform(get("/sample/count").param("count", "1").accept(APPLICATION_XML)),
                406, "COMMON_406").andExpect(content().contentType(APPLICATION_JSON));
        expectFailure(mvc.perform(get("/sample/status")), 406, "COMMON_406")
                .andExpect(content().string(not(containsString("internal detail"))));
    }

    @Test
    void preservesAnnotatedStatusOnExceptionAndCauseWithoutExposingReason() throws Exception {
        for (String path : new String[]{"/sample/annotated", "/sample/wrapped"}) {
            expectFailure(mvc.perform(get(path)), 404, "COMMON_404")
                    .andExpect(jsonPath("$.data").value(nullValue()))
                    .andExpect(content().string(not(containsString("internal detail"))));
        }
    }

    @Test
    void hidesUnexpectedErrorDetails() throws Exception {
        expectFailure(mvc.perform(get("/sample/failure")), 500, "COMMON_500")
                .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(content().string(not(containsString("internal detail"))));
    }

    private ResultActions expectFailure(ResultActions result, int statusCode, String code) throws Exception {
        return result.andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isString());
    }

    @ResponseStatus(code = NOT_FOUND, reason = "internal detail")
    static class SampleNotFoundException extends RuntimeException {
    }

    @RestController
    static class TestController {
        @PostMapping("/sample")
        ResponseEntity<BaseResponse<Integer>> create(@Valid @RequestBody SampleRequest request) {
            return ResponseEntity.status(201).body(BaseResponse.success(1));
        }

        @GetMapping("/sample/empty")
        ResponseEntity<Void> empty() {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/sample/conflict")
        void conflict() {
            throw new BusinessException(ErrorCode.CONFLICT);
        }

        @GetMapping("/sample/count")
        int count(@RequestParam @Min(1) int count) {
            return count;
        }

        @GetMapping("/sample/status")
        void status() {
            throw new ResponseStatusException(NOT_ACCEPTABLE, "internal detail");
        }

        @GetMapping("/sample/annotated")
        void annotated() {
            throw new SampleNotFoundException();
        }

        @GetMapping("/sample/wrapped")
        void wrapped() {
            throw new IllegalStateException(new SampleNotFoundException());
        }

        @GetMapping("/sample/failure")
        void failure() {
            throw new IllegalStateException("internal detail");
        }

        record SampleRequest(@Email String email, @Size(min = 8) String password) {
        }
    }
}
