package com.quizopia.classroom.api;

import jakarta.validation.constraints.NotBlank;

public record CreateClassroomRequest(
        @NotBlank String name, @NotBlank String subject, @NotBlank String grade, String description) {}
