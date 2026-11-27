package com.asbg.backend.global.config;

import com.asbg.backend.global.exception.ErrorCode;
import com.asbg.backend.global.presentation.BaseResponse;
import com.asbg.backend.global.presentation.FieldErrorResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {
    @Bean
    OpenAPI reservationOpenApi(@Value("${spring.application.name}") String applicationName, JsonMapper jsonMapper) {
        Components components = new Components();
        ModelConverters converters = ModelConverters.getInstance(true);
        converters.readAll(BaseResponse.class).forEach(components::addSchemas);
        converters.readAll(FieldErrorResponse.class).forEach(components::addSchemas);

        var fields = new FieldErrorResponse(List.of(
                new FieldErrorResponse.FieldError("username", ErrorCode.INVALID_INPUT_VALUE.message())));
        Schema<?> responseSchema = new Schema<>().$ref("#/components/schemas/BaseResponse");
        Schema<?> validationSchema = new ComposedSchema()
                .addAllOfItem(responseSchema)
                .addAllOfItem(new ObjectSchema().addProperty(
                        "data", new Schema<>().$ref("#/components/schemas/FieldErrorResponse")));
        components.addResponses("InvalidInput",
                errorResponse(ErrorCode.INVALID_INPUT_VALUE, fields, validationSchema, jsonMapper));
        components.addResponses("Unauthorized",
                errorResponse(ErrorCode.UNAUTHORIZED, null, responseSchema, jsonMapper));
        components.addResponses("Forbidden",
                errorResponse(ErrorCode.FORBIDDEN, null, responseSchema, jsonMapper));

        return new OpenAPI()
                .info(new Info().title(applicationName + " API").version("1.0.0")
                        .description("실시간 대기열 기반 예약 시스템"))
                .components(components);
    }

    private ApiResponse errorResponse(ErrorCode code, Object data, Schema<?> schema, JsonMapper jsonMapper) {
        return new ApiResponse()
                .description(code.status().value() + " " + code.message())
                .content(new Content().addMediaType(APPLICATION_JSON_VALUE,
                        new MediaType()
                                .schema(schema)
                                .example(jsonMapper.valueToTree(BaseResponse.failure(code, data)))));
    }
}
