package com.quizopia.quiz;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

@AnalyzeClasses(packages = "com.quizopia.quiz", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final com.tngtech.archunit.lang.ArchRule serviceOwnership = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.quizopia.identity..",
                    "com.quizopia.classroom..",
                    "com.quizopia.assessment..",
                    "com.quizopia.community..",
                    "com.quizopia.proctoring..",
                    "com.quizopia.ai..",
                    "com.quizopia.shared..");

    @ArchTest
    static final com.tngtech.archunit.lang.ArchRule domainIndependence = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "..application..",
                    "..persistence..",
                    "..configuration..",
                    "..api..",
                    "..security..");

    @ArchTest
    static final com.tngtech.archunit.lang.ArchRule applicationUsesPorts =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
                    .that()
                    .resideInAPackage("..application..")
                    .should()
                    .onlyDependOnClassesThat()
                    .resideInAnyPackage(
                            "java..",
                            "com.quizopia.quiz.application..",
                            "com.quizopia.quiz.domain..",
                            "org.springframework.stereotype..",
                            "org.springframework.transaction.annotation..");

    @ArchTest
    static final com.tngtech.archunit.lang.ArchRule apiDoesNotAccessPersistence = noClasses()
            .that()
            .resideInAPackage("..api..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..persistence..", "jakarta.persistence..")
            .allowEmptyShould(true);

    @ArchTest
    static final com.tngtech.archunit.lang.ArchRule entitiesStayInPersistence =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
                    .that()
                    .areAnnotatedWith(jakarta.persistence.Entity.class)
                    .should()
                    .resideInAPackage("com.quizopia.quiz.persistence..");
}
