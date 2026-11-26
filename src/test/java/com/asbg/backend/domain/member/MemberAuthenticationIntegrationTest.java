package com.asbg.backend.domain.member;

import com.asbg.backend.domain.member.domain.MemberPrincipal;
import com.asbg.backend.domain.member.repository.MemberRepository;
import com.asbg.backend.global.config.SessionStoreConfig;
import com.asbg.backend.global.security.ActiveMemberSessions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=${VALKEY_PORT:6379}",
        "spring.datasource.url=${AUTH_TEST_DB_URL:jdbc:h2:mem:member-auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${AUTH_TEST_DB_USERNAME:sa}",
        "spring.datasource.password=${AUTH_TEST_DB_PASSWORD:}",
        "app.auth.idle-timeout=3s",
        "app.auth.absolute-timeout=8s",
        "app.cors.allowed-origins=https://frontend.example.test"
})
@ActiveProfiles({"test", "valkey-test"})
@EnabledIfEnvironmentVariable(named = "RUN_VALKEY_TESTS", matches = "true")
@Import(MemberAuthenticationIntegrationTest.ProtectedEndpoint.class)
class MemberAuthenticationIntegrationTest {
    private static final String PASSWORD = "test1234!";
    private static final String CSRF = "/api/auth/csrf";
    private static final String REGISTER = "/api/members";
    private static final String LOGIN = "/api/auth/login";
    private static final String LOGOUT = "/api/auth/logout";
    private static final String WITHDRAW = "/api/members/me";
    private static final String PROTECTED = "/integration/protected";
    private static final String ORIGIN = "https://frontend.example.test";

    @LocalServerPort
    private int port;

    @Autowired
    private JsonMapper json;

    @Autowired
    private MemberRepository members;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private SessionRepository<?> sessions;

    @Autowired
    private ActiveMemberSessions activeSessions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void registersNormalizedMemberAndStoresOnlyHash() throws Exception {
        var browser = new Browser();
        String username = username();
        var response = browser.write("POST", REGISTER, credentials(username.toUpperCase(java.util.Locale.ROOT), PASSWORD));
        expect(response, 201, "COMMON_SUCCESS");
        assertThat(body(response).path("data").path("username").asString()).isEqualTo(username);
        assertThat(response.body()).doesNotContain(PASSWORD, "password");
        var member = members.findByUsername(username).orElseThrow();
        assertThat(member.getCreatedAt()).isNotNull();
        assertThat(member.getPassword()).isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, member.getPassword())).isTrue();
        expect(browser.get(PROTECTED), 401, "COMMON_401");
        expect(browser.write("POST", REGISTER, credentials(username, PASSWORD)), 409, "MEMBER_409_1");
    }

    @Test
    void validatesCredentialsWithoutLeakingInputs() throws Exception {
        var browser = new Browser();
        for (String payload : new String[]{"{}", credentials("a!", PASSWORD), credentials(username(), "short"),
                credentials(username(), "가".repeat(25))}) {
            expect(browser.write("POST", REGISTER, payload), 400, "COMMON_400");
        }
        expect(browser.write("POST", REGISTER, "{"), 400, "COMMON_400_BODY");
        expect(browser.write("POST", REGISTER, credentials(username(), "가".repeat(24))), 201, "COMMON_SUCCESS");
    }

    @Test
    void protectsDatabaseUniquenessUnderConcurrentRegistrations() throws Exception {
        String username = username();
        var responses = concurrently(
                () -> new Browser().write("POST", REGISTER, credentials(username, PASSWORD)),
                () -> new Browser().write("POST", REGISTER, credentials(username, PASSWORD)));
        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
        var conflict = responses.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow();
        expect(conflict, 409, "MEMBER_409_1");
    }

    @Test
    void givesSameResponseForUnknownUsernameAndWrongPassword() throws Exception {
        String username = register();
        var browser = new Browser();
        var unknown = browser.write("POST", LOGIN, credentials(username(), PASSWORD));
        var wrongPassword = browser.write("POST", LOGIN, credentials(username, "incorrect"));
        expect(unknown, 401, "MEMBER_401_1");
        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(wrongPassword.body()).isEqualTo(unknown.body());
    }

    @Test
    void rotatesSessionAndCsrfTokenAndReusesAnAuthenticatedSession() throws Exception {
        String username = register();
        var browser = new Browser();
        String token = browser.csrf();
        String anonymousCookie = browser.cookie();
        var login = browser.send("POST", LOGIN, credentials(username.toUpperCase(java.util.Locale.ROOT), PASSWORD), token);
        expect(login, 200, "COMMON_SUCCESS");
        assertThat(login.body()).doesNotContain(PASSWORD, "sessionId", "token");
        String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(setCookie).contains("ASBG_SESSION=", "Path=/", "HttpOnly", "SameSite=Lax").doesNotContain("Secure", "Domain");
        assertThat(browser.cookie()).isNotEqualTo(anonymousCookie);
        long memberId = body(login).path("data").path("memberId").asLong();
        String sessionId = browser.sessionId();
        assertThat(redis.hasKey(SessionStoreConfig.SESSION_NAMESPACE + ":sessions:" + sessionId)).isTrue();
        assertThat(redis.opsForValue().get("asbg:auth:active:" + memberId)).isEqualTo(sessionId);
        var context = sessions.findById(sessionId).<org.springframework.security.core.context.SecurityContext>
                getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context.getAuthentication().getPrincipal()).isEqualTo(new MemberPrincipal(memberId, username));
        assertThat(context.getAuthentication().getCredentials()).isNull();
        assertThat(sessions.findById(browser.decode(anonymousCookie))).isNull();
        expect(browser.send("POST", LOGOUT, null, token), 403, "COMMON_403");
        expect(browser.get(PROTECTED), 200, null);
        expect(browser.write("POST", LOGIN, credentials(username, PASSWORD)), 200, "COMMON_SUCCESS");
        assertThat(browser.sessionId()).isEqualTo(sessionId);
    }

    @Test
    void deniesOtherBrowsersOnlyAfterCheckingCredentialsAndPreventsAccountSwitch() throws Exception {
        String username = register();
        String other = register();
        var first = new Browser();
        var second = new Browser();
        expect(first.write("POST", LOGIN, credentials(username, PASSWORD)), 200, "COMMON_SUCCESS");
        expect(second.write("POST", LOGIN, credentials(username, "incorrect")), 401, "MEMBER_401_1");
        expect(second.write("POST", LOGIN, credentials(username, PASSWORD)), 409, "MEMBER_409_2");
        expect(first.write("POST", LOGIN, credentials(other, PASSWORD)), 409, "MEMBER_409_2");
        expect(second.write("POST", LOGIN, credentials(other, PASSWORD)), 200, "COMMON_SUCCESS");
        expect(first.get(PROTECTED), 200, null);
    }

    @Test
    void allowsOnlyOneConcurrentLogin() throws Exception {
        String username = register();
        var first = new Browser();
        var second = new Browser();
        String firstToken = first.csrf();
        String secondToken = second.csrf();
        var responses = concurrently(
                () -> first.send("POST", LOGIN, credentials(username, PASSWORD), firstToken),
                () -> second.send("POST", LOGIN, credentials(username, PASSWORD), secondToken));
        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        assertThat(java.util.List.of(first.get(PROTECTED).statusCode(), second.get(PROTECTED).statusCode()))
                .containsExactlyInAnyOrder(200, 401);
    }

    @Test
    void expiresIdleSessionsAndAllowsRelogin() throws Exception {
        String username = register();
        var first = new Browser();
        expect(first.write("POST", LOGIN, credentials(username, PASSWORD)), 200, "COMMON_SUCCESS");
        Thread.sleep(3400);
        expect(first.get(PROTECTED), 401, "COMMON_401");
        expect(new Browser().write("POST", LOGIN, credentials(username, PASSWORD)), 200, "COMMON_SUCCESS");
    }

    @Test
    void expiresActiveSessionsAtAbsoluteDeadlineDespiteRepeatedLogin() throws Exception {
        String username = register();
        var browser = new Browser();
        expect(browser.write("POST", LOGIN, credentials(username, PASSWORD)), 200, "COMMON_SUCCESS");
        String original = browser.sessionId();
        for (int step = 0; step < 3; step++) {
            Thread.sleep(1900);
            expect(browser.get(PROTECTED), 200, null);
            expect(browser.write("POST", LOGIN, credentials(username, PASSWORD)), 200, "COMMON_SUCCESS");
            assertThat(browser.sessionId()).isEqualTo(original);
        }
        Thread.sleep(2500);
        expect(browser.get(PROTECTED), 401, "COMMON_401");
        expect(new Browser().write("POST", LOGIN, credentials(username, PASSWORD)), 200, "COMMON_SUCCESS");
    }

    @Test
    void logsOutAndIgnoresDelayedRevocationOfOldSession() throws Exception {
        String username = register();
        var first = new Browser();
        var second = new Browser();
        var login = first.write("POST", LOGIN, credentials(username, PASSWORD));
        expect(login, 200, "COMMON_SUCCESS");
        long memberId = body(login).path("data").path("memberId").asLong();
        String previous = first.sessionId();
        var replay = first.copy();
        String oldToken = first.csrf();
        var logout = first.send("POST", LOGOUT, null, oldToken);
        expectEmpty(logout);
        assertThat(logout.headers().firstValue("Set-Cookie").orElseThrow()).contains("ASBG_SESSION=", "Max-Age=0");
        assertThat(sessions.findById(previous)).isNull();
        expect(replay.get(PROTECTED), 401, "COMMON_401");
        expect(first.send("POST", LOGIN, credentials(username, PASSWORD), oldToken), 403, "COMMON_403");
        expect(second.write("POST", LOGIN, credentials(username, PASSWORD)), 200, "COMMON_SUCCESS");
        expect(replay.send("POST", LOGOUT, null, oldToken), 403, "COMMON_403");
        activeSessions.release(memberId, previous);
        expect(second.get(PROTECTED), 200, null);
    }

    @Test
    void withdrawsAfterPasswordRecheckAndAllowsReregistration() throws Exception {
        String username = register();
        var browser = new Browser();
        var login = browser.write("POST", LOGIN, credentials(username, PASSWORD));
        expect(login, 200, "COMMON_SUCCESS");
        var replay = browser.copy();
        expect(new Browser().write("DELETE", WITHDRAW, password(PASSWORD)), 401, "COMMON_401");
        expect(browser.write("DELETE", WITHDRAW, password("incorrect")), 401, "MEMBER_401_2");
        expect(browser.get(PROTECTED), 200, null);
        expectEmpty(browser.write("DELETE", WITHDRAW, password(PASSWORD)));
        assertThat(members.findByUsername(username)).isEmpty();
        expect(replay.get(PROTECTED), 401, "COMMON_401");
        expect(browser.write("POST", LOGIN, credentials(username, PASSWORD)), 401, "MEMBER_401_1");
        expect(browser.write("POST", REGISTER, credentials(username, PASSWORD)), 201, "COMMON_SUCCESS");
    }

    @Test
    void revokesAuthenticationOnForeignKeyConflictAndAllowsLoginAgain() throws Exception {
        String username = register();
        long memberId = members.findByUsername(username).orElseThrow().getMemberId();
        jdbc.execute("create table auth_test_reference (id bigint primary key references members(member_id))");
        try {
            jdbc.update("insert into auth_test_reference values (?)", memberId);
            var browser = new Browser();
            expect(browser.write("POST", LOGIN, credentials(username, PASSWORD)), 200, "COMMON_SUCCESS");
            expect(browser.write("DELETE", WITHDRAW, password(PASSWORD)), 409, "MEMBER_409_3");
            assertThat(members.existsById(memberId)).isTrue();
            expect(browser.get(PROTECTED), 401, "COMMON_401");
            expect(new Browser().write("POST", LOGIN, credentials(username, PASSWORD)), 200, "COMMON_SUCCESS");
        } finally {
            jdbc.execute("drop table auth_test_reference");
        }
    }

    @Test
    void cannotLogInDuringWithdrawal() throws Exception {
        String username = register();
        var first = new Browser();
        var second = new Browser();
        expect(first.write("POST", LOGIN, credentials(username, PASSWORD)), 200, "COMMON_SUCCESS");
        String firstToken = first.csrf();
        String secondToken = second.csrf();
        var responses = concurrently(
                () -> first.send("DELETE", WITHDRAW, password(PASSWORD), firstToken),
                () -> second.send("POST", LOGIN, credentials(username, PASSWORD), secondToken));
        assertThat(responses.getFirst().statusCode()).isEqualTo(204);
        assertThat(responses.getLast().statusCode()).isIn(401, 409);
        assertThat(members.findByUsername(username)).isEmpty();
        expect(first.get(PROTECTED), 401, "COMMON_401");
        expect(second.get(PROTECTED), 401, "COMMON_401");
    }

    @Test
    void enforcesCsrfAndCredentialCors() throws Exception {
        var browser = new Browser();
        expect(browser.send("POST", REGISTER, credentials(username(), PASSWORD), null), 403, "COMMON_403");
        expect(browser.send("POST", REGISTER, credentials(username(), PASSWORD), "invalid"), 403, "COMMON_403");
        var allowed = browser.preflight(ORIGIN);
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains(ORIGIN);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
        var denied = browser.preflight("https://unlisted.example.test");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void rejectsAnOtherwisePresentSessionWhenItsClaimDisappears() throws Exception {
        String username = register();
        var browser = new Browser();
        var login = browser.write("POST", LOGIN, credentials(username, PASSWORD));
        long memberId = body(login).path("data").path("memberId").asLong();
        String sessionId = browser.sessionId();
        activeSessions.release(memberId, sessionId);
        assertThat(sessions.findById(sessionId)).isNotNull();
        expect(browser.get(PROTECTED), 401, "COMMON_401");
        expect(new Browser().write("POST", LOGIN, credentials(username, PASSWORD)), 200, "COMMON_SUCCESS");
    }

    private String register() throws Exception {
        String username = username();
        expect(new Browser().write("POST", REGISTER, credentials(username, PASSWORD)), 201, "COMMON_SUCCESS");
        return username;
    }

    private String username() {
        return "member_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String credentials(String username, String password) {
        return json.writeValueAsString(java.util.Map.of("username", username, "password", password));
    }

    private String password(String password) {
        return json.writeValueAsString(java.util.Map.of("password", password));
    }

    private JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    private void expect(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        if (code != null) {
            assertThat(body(response).path("code").asString()).isEqualTo(code);
        }
    }

    private void expectEmpty(HttpResponse<String> response) {
        expect(response, 204, null);
        assertThat(response.body()).isEmpty();
    }

    private <T> java.util.List<T> concurrently(java.util.concurrent.Callable<T> first,
                                              java.util.concurrent.Callable<T> second) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var firstResult = executor.submit(() -> { start.await(); return first.call(); });
            var secondResult = executor.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return java.util.List.of(firstResult.get(15, TimeUnit.SECONDS), secondResult.get(15, TimeUnit.SECONDS));
        }
    }

    private final class Browser {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

        HttpResponse<String> get(String path) throws Exception {
            return send("GET", path, null, null);
        }

        String csrf() throws Exception {
            var response = get(CSRF);
            expect(response, 200, "COMMON_SUCCESS");
            assertThat(body(response).path("data").path("headerName").asString()).isEqualTo("X-CSRF-TOKEN");
            return body(response).path("data").path("token").asString();
        }

        HttpResponse<String> write(String method, String path, String payload) throws Exception {
            return send(method, path, payload, csrf());
        }

        HttpResponse<String> send(String method, String path, String payload, String token) throws Exception {
            var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
            if (token != null) {
                builder.header("X-CSRF-TOKEN", token);
            }
            if (payload != null) {
                builder.header("Content-Type", "application/json");
            }
            var requestBody = payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload);
            return client.send(builder.method(method, requestBody).build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> preflight(String origin) throws Exception {
            var request = HttpRequest.newBuilder(uri(LOGIN)).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type,x-csrf-token").build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        Browser copy() {
            var browser = new Browser();
            cookies.getCookieStore().getCookies().forEach(cookie -> browser.cookies.getCookieStore().add(uri("/"), cookie));
            return browser;
        }

        String cookie() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> SessionStoreConfig.SESSION_COOKIE.equals(cookie.getName())).findFirst().orElseThrow().getValue();
        }

        String decode(String encoded) {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        }

        String sessionId() {
            return decode(cookie());
        }

        URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + path);
        }
    }

    @RestController
    static class ProtectedEndpoint {
        @GetMapping(PROTECTED)
        long check(@AuthenticationPrincipal MemberPrincipal principal) {
            return principal.memberId();
        }
    }
}
