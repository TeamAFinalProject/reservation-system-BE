package com.asbg.backend.domain.member.controller;

import com.asbg.backend.domain.member.domain.MemberPrincipal;
import com.asbg.backend.domain.member.dto.request.CredentialsRequest;
import com.asbg.backend.domain.member.dto.request.WithdrawalRequest;
import com.asbg.backend.domain.member.dto.response.MemberResponse;
import com.asbg.backend.domain.member.service.MemberService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members")
@Profile(SessionStoreConfig.ENABLED_PROFILE)
public class MemberController {
    private final MemberService members;
    private final MemberSessionService sessions;

    public MemberController(MemberService members, MemberSessionService sessions) {
        this.members = members;
        this.sessions = sessions;
    }

    @PostMapping
    @Operation(summary = "회원가입", description = "CSRF 헤더가 필요하며 가입 후 로그인은 별도로 요청합니다.")
    @ApiResponse(responseCode = "201", description = "회원가입 성공", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidInput")
    @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF 토큰")
    @ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden")
    @ApiResponse(responseCode = "409", description = "MEMBER_409_1 아이디 중복")
    public ResponseEntity<BaseResponse<MemberResponse>> register(@Valid @RequestBody CredentialsRequest credentials) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BaseResponse.success(members.register(credentials.username(), credentials.password())));
    }

    @DeleteMapping("/me")
    @Operation(summary = "회원탈퇴", description = "현재 비밀번호와 CSRF 헤더가 필요합니다.")
    @SecurityRequirement(name = "sessionCookie")
    @ApiResponse(responseCode = "204", description = "탈퇴 성공")
    @ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidInput")
    @ApiResponse(responseCode = "401", description = "COMMON_401 미인증 또는 MEMBER_401_2 비밀번호 불일치")
    @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF 토큰")
    @ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden")
    @ApiResponse(responseCode = "409", description = "MEMBER_409_3 삭제를 제한하는 연결 데이터")
    @ApiResponse(responseCode = "503", description = "COMMON_503 인증 저장소 이용 불가")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal MemberPrincipal principal,
                                         @Valid @RequestBody WithdrawalRequest withdrawal,
                                         HttpServletRequest request, HttpServletResponse response) {
        sessions.withdraw(principal, withdrawal.password(), request, response);
        return ResponseEntity.noContent().build();
    }
}
