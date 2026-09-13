package com.quizopia.classroom;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

@AnalyzeClasses(packages = "com.quizopia.classroom", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final com.tngtech.archunit.lang.ArchRule serviceOwnership = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.quizopia.identity..",
                    "com.quizopia.quiz..",
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
                    "..api..");

    @ArchTest
    static final com.tngtech.archunit.lang.ArchRule applicationUsesPorts = noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..persistence..",
                    "jakarta.persistence..",
                    "..api..",
                    "org.springframework.web..",
                    "org.springframework.security..",
                    "org.springframework.amqp..");

    @ArchTest
    static final com.tngtech.archunit.lang.ArchRule apiDoesNotAccessPersistence = noClasses()
            .that()
            .resideInAPackage("..api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..persistence..");

    @ArchTest
    static final com.tngtech.archunit.lang.ArchRule entitiesStayInPersistence =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
                    .that()
                    .areAnnotatedWith(jakarta.persistence.Entity.class)
                    .should()
                    .resideInAPackage("com.quizopia.classroom.persistence..");

    @ArchTest
    static final com.tngtech.archunit.lang.ArchRule noLegacyBusinessTypesInScaffold = noClasses()
            .should()
            .haveSimpleName("User")
            .orShould()
            .haveSimpleName("Quiz")
            .orShould()
            .haveSimpleName("Attempt");
}
