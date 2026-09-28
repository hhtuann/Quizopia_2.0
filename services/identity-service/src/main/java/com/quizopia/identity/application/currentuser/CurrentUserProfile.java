package com.quizopia.identity.application.currentuser;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CurrentUserProfile(UUID id, String username, String email, List<String> roles) {
    public CurrentUserProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
        roles = List.copyOf(Objects.requireNonNull(roles, "roles"));
    }
}
