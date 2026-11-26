package com.asbg.backend.domain.member.dto.request;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
@Constraint(validatedBy = BcryptPassword.Validator.class)
public @interface BcryptPassword {
    String message() default "비밀번호는 UTF-8 기준 최대 72바이트입니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<BcryptPassword, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || (value.length() <= 72
                    && StandardCharsets.UTF_8.newEncoder().canEncode(value)
                    && value.getBytes(StandardCharsets.UTF_8).length <= 72);
        }
    }
}
