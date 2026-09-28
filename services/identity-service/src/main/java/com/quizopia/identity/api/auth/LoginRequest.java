package com.quizopia.identity.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 320) @Schema(description = "Exact username or exact verified email") String identifier,
        @NotBlank @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password) {
    @Override
    public String toString() {
        return "LoginRequest{identifierPresent=true, password=[REDACTED]}";
    }
}
