package com.asbg.backend.domain.member.controller;

import com.asbg.backend.domain.member.domain.MemberPrincipal;
import com.asbg.backend.domain.member.dto.request.CredentialsRequest;
import com.asbg.backend.domain.member.dto.response.MemberResponse;
import com.asbg.backend.domain.member.service.MemberSessionService;
import com.asbg.backend.global.config.SessionStoreConfig;
import com.asbg.backend.global.presentation.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Profile(SessionStoreConfig.ENABLED_PROFILE)
public class MemberAuthController {
    private final MemberSessionService sessions;

    public MemberAuthController(MemberSessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/login")
    @Operation(summary = "세션 로그인", description = "CSRF 헤더가 필요합니다. 로그인 후 CSRF 토큰을 새로 받아야 합니다.")
    @ApiResponse(responseCode = "200", description = "로그인 성공, 세션 쿠키 발급", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidInput")
    @ApiResponse(responseCode = "401", description = "MEMBER_401_1 아이디 또는 비밀번호 불일치")
    @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF 토큰")
    @ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden")
    @ApiResponse(responseCode = "409", description = "MEMBER_409_2 활성 로그인 충돌")
    @ApiResponse(responseCode = "503", description = "COMMON_503 인증 저장소 이용 불가")
    public BaseResponse<MemberResponse> login(@Valid @RequestBody CredentialsRequest credentials,
                                              HttpServletRequest request, HttpServletResponse response) {
        return BaseResponse.success(sessions.login(credentials, request, response));
    }

    @PostMapping("/logout")
    @Operation(summary = "로그아웃", description = "현재 세션과 CSRF 헤더가 필요합니다.")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponse(responseCode = "204", description = "로그아웃 성공")
    @ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthorized")
    @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF 토큰")
    @ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden")
    @ApiResponse(responseCode = "503", description = "COMMON_503 인증 저장소 이용 불가")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal MemberPrincipal principal,
                                       HttpServletRequest request, HttpServletResponse response) {
        sessions.logout(principal, request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/csrf")
    @Operation(summary = "CSRF 토큰 조회", description = "같은 브라우저 세션으로 요청하고 응답의 headerName에 token을 보내세요.")
    @ApiResponse(responseCode = "200", description = "CSRF 토큰 조회 성공", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "503", description = "COMMON_503 인증 저장소 이용 불가")
    public ResponseEntity<BaseResponse<CsrfResponse>> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(BaseResponse.success(new CsrfResponse(token.getHeaderName(), token.getToken())));
    }

    public record CsrfResponse(String headerName, String token) {
    }
}
