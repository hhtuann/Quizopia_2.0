package com.quizopia.quiz.security;

record QuizSecurityError(String code, String message, int status, String path) {}
