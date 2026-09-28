package com.quizopia.identity.api.auth;

import com.quizopia.identity.application.currentuser.CurrentUserProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public record CurrentUserResponse(
        UUID id,
        String username,
        String email,
        @Schema(allowableValues = {"STUDENT", "TEACHER", "ADMIN"}) List<String> roles) {
    static CurrentUserResponse from(CurrentUserProfile profile) {
        return new CurrentUserResponse(profile.id(), profile.username(), profile.email(), profile.roles());
    }
}
