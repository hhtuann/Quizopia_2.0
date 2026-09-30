package com.quizopia.quiz.configuration;

import com.quizopia.quiz.domain.markdown.QuizMarkdownParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class QuizMarkdownConfiguration {
    @Bean
    QuizMarkdownParser quizMarkdownParser() {
        return new QuizMarkdownParser();
    }
}
