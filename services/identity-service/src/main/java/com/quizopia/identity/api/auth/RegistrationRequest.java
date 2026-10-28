package com.quizopia.identity.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegistrationRequest(
        @NotBlank @Size(max = 255) @Schema(example = "learner01") String username,
        @NotBlank @Size(max = 320) @Schema(example = "learner01@gmail.com") String email,
        @NotBlank @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password,
        @Schema(
                        description =
                                "Optional self-provided display name (never a verified legal name); absent/blank stores none",
                        example = "Nguyễn Văn An")
                String displayName) {
    @Override
    public String toString() {
        return "RegistrationRequest{usernamePresent=true, emailPresent=true, password=[REDACTED]"
                + ", displayNamePresent=" + (displayName != null) + "}";
    }
}
