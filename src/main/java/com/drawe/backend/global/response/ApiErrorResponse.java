package com.drawe.backend.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Map;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiErrorResponse {

    private final boolean success = false;
    private final ErrorBody error;

    public ApiErrorResponse(String code, String message) {
        this.error = new ErrorBody(code, message, null, LocalDateTime.now());
    }

    public ApiErrorResponse(String code, String message, Map<String, Object> details) {
        this.error = new ErrorBody(code, message, details, LocalDateTime.now());
    }

    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorBody {
        private final String code;
        private final String message;
        private final Map<String, Object> details;
        private final LocalDateTime timestamp;

        public ErrorBody(String code, String message, Map<String, Object> details, LocalDateTime timestamp) {
            this.code = code;
            this.message = message;
            this.details = details;
            this.timestamp = timestamp;
        }
    }
}
