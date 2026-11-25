package com.asbg.backend.global.exception;

import com.asbg.backend.global.presentation.BaseResponse;
import com.asbg.backend.global.presentation.FieldErrorResponse;
import com.asbg.backend.global.presentation.FieldErrorResponse.FieldError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<BaseResponse<Void>> handleBusinessException(BusinessException exception) {
        BaseErrorCode errorCode = exception.errorCode();
        return ResponseEntity.status(errorCode.status()).body(BaseResponse.failure(errorCode, null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<BaseResponse<FieldErrorResponse>> handleInvalidArgument(MethodArgumentNotValidException exception) {
        List<FieldError> errors = exception.getFieldErrors().stream()
                .map(error -> fieldError(error.getField(), error.getCode()))
                .toList();
        return validationFailure(errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<? extends BaseResponse<?>> handleMethodValidation(HandlerMethodValidationException exception) {
        if (exception.isForReturnValue()) {
            return handleException(exception);
        }
        List<FieldError> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> {
                    if (result instanceof ParameterErrors beanErrors) {
                        return beanErrors.getFieldErrors().stream()
                                .map(error -> fieldError(error.getField(), error.getCode()));
                    }
                    String name = Objects.requireNonNullElse(
                            result.getMethodParameter().getParameterName(), "arg" + result.getMethodParameter().getParameterIndex());
                    return result.getResolvableErrors().stream()
                            .map(error -> fieldError(name, null));
                })
                .toList();
        return validationFailure(errors);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<BaseResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return failure(ErrorCode.INVALID_INPUT_VALUE, HttpHeaders.EMPTY);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<BaseResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return failure(ErrorCode.INVALID_REQUEST_BODY, HttpHeaders.EMPTY);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<Void>> handleException(Exception exception) {
        if (exception instanceof ErrorResponse response) {
            HttpStatusCode status = response.getStatusCode();
            if (!status.isError()) {
                return ResponseEntity.status(status).headers(response.getHeaders()).build();
            }
            if (status.is5xxServerError()) {
                logServerError(exception);
            }
            BaseErrorCode errorCode = Arrays.stream(ErrorCode.values())
                    .filter(candidate -> candidate.status().value() == status.value())
                    .findFirst()
                    .<BaseErrorCode>map(candidate -> candidate)
                    .orElseGet(() -> new FrameworkErrorCode(status));
            return failure(errorCode, response.getHeaders());
        }
        logServerError(exception);
        return failure(ErrorCode.INTERNAL_SERVER_ERROR, HttpHeaders.EMPTY);
    }

    private ResponseEntity<BaseResponse<FieldErrorResponse>> validationFailure(List<FieldError> errors) {
        ErrorCode code = ErrorCode.INVALID_INPUT_VALUE;
        return ResponseEntity.status(code.status()).body(BaseResponse.failure(code, new FieldErrorResponse(errors)));
    }

    private FieldError fieldError(String field, String validationCode) {
        String message = "Email".equals(validationCode)
                ? "올바른 이메일 형식이 아닙니다." : ErrorCode.INVALID_INPUT_VALUE.message();
        return new FieldError(field, message);
    }

    private ResponseEntity<BaseResponse<Void>> failure(BaseErrorCode code, HttpHeaders headers) {
        return ResponseEntity.status(code.status()).headers(headers).body(BaseResponse.failure(code, null));
    }

    private void logServerError(Exception exception) {
        Throwable cause = Objects.requireNonNullElse(exception.getCause(), exception);
        log.error("API 처리 오류 유형 {} (원인 {}) 발생 위치 {}",
                exception.getClass().getName(), cause.getClass().getName(), Arrays.toString(cause.getStackTrace()));
    }

    private record FrameworkErrorCode(HttpStatusCode status) implements BaseErrorCode {
        @Override
        public String code() {
            return "COMMON_" + status.value();
        }

        @Override
        public String message() {
            return status.is5xxServerError() ? ErrorCode.INTERNAL_SERVER_ERROR.message() : "요청을 처리할 수 없습니다.";
        }
    }
}
