package com.quizopia.identity.api.auth;

import io.swagger.v3.oas.annotations.media.Schema;

public record DisplayNameUpdateRequest(
        @Schema(
                        description = "Normalized self-provided display name; JSON null clears the stored name",
                        example = "Nguyễn Văn An",
                        nullable = true)
                String displayName) {
    @Override
    public String toString() {
        return "DisplayNameUpdateRequest{displayName=[REDACTED]}";
    }
}
