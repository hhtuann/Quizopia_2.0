package com.quizopia.proctoring;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

@AnalyzeClasses(packages = "com.quizopia.proctoring")
class ArchitectureTest {
    @ArchTest
    static final com.tngtech.archunit.lang.ArchRule noLegacyBusinessTypesInScaffold = noClasses()
            .should()
            .haveSimpleName("User")
            .orShould()
            .haveSimpleName("Quiz")
            .orShould()
            .haveSimpleName("Attempt");
}
