package com.quizopia.identity.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EmailVerificationConfirmRequest(
        @NotBlank @Size(max = 255) @Schema(example = "learner01") String username,
        @NotBlank @Pattern(regexp = "[0-9]{6}") @Schema(example = "123456", pattern = "^[0-9]{6}$", accessMode = Schema.AccessMode.WRITE_ONLY)
                String otp) {
    @Override
    public String toString() {
        return "EmailVerificationConfirmRequest{usernamePresent=true, otp=[REDACTED]}";
    }
}
