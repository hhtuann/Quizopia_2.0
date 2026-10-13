package com.quizopia.assessment;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

@AnalyzeClasses(packages = "com.quizopia.assessment")
class ArchitectureTest {
    @ArchTest
    static final com.tngtech.archunit.lang.ArchRule domainHasNoFrameworkOrAdapterDependencies = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "..api..",
                    "..application..",
                    "..persistence..",
                    "..infrastructure..");

    @ArchTest
    static final com.tngtech.archunit.lang.ArchRule applicationUsesPorts = noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..api..", "..persistence..", "..infrastructure..", "org.springframework.security..");

    @ArchTest
    static final com.tngtech.archunit.lang.ArchRule apiDoesNotAccessDatabase = noClasses()
            .that()
            .resideInAPackage("..api..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..persistence..", "org.springframework.jdbc..", "jakarta.persistence..");

    @ArchTest
    static final com.tngtech.archunit.lang.ArchRule noForeignBusinessTypes =
            noClasses().should().haveSimpleName("User").orShould().haveSimpleName("Quiz");
}
