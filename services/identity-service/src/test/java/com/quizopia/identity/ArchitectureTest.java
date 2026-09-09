package com.quizopia.identity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;

@AnalyzeClasses(packages = "com.quizopia.identity")
class ArchitectureTest {
    @ArchTest
    static final ArchRule noLegacyBusinessTypesInScaffold = noClasses()
            .should()
            .haveSimpleName("User")
            .orShould()
            .haveSimpleName("Quiz")
            .orShould()
            .haveSimpleName("Attempt");

    @ArchTest
    static final ArchRule jpaEntitiesRemainIdentityLocal = classes()
            .that()
            .areAnnotatedWith(Entity.class)
            .should()
            .resideInAnyPackage("com.quizopia.identity.persistence.entity..");

    @ArchTest
    static final ArchRule revocationAdaptersDoNotExposeJpaEntities = noClasses()
            .that()
            .resideInAnyPackage(
                    "com.quizopia.identity.application.revocation..",
                    "com.quizopia.identity.infrastructure.revocation..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.quizopia.identity.persistence.entity..");

    @ArchTest
    static final ArchRule googleLinkApplicationDoesNotExposeJpaEntities = noClasses()
            .that()
            .resideInAnyPackage("com.quizopia.identity.application.googlelink..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.quizopia.identity.persistence.entity..");

    @ArchTest
    static final ArchRule accountApplicationBoundariesDoNotExposeJpaEntities = noClasses()
            .that()
            .resideInAnyPackage(
                    "com.quizopia.identity.application.registration..",
                    "com.quizopia.identity.application.activation..",
                    "com.quizopia.identity.application.emailverification..")
            .and()
            .haveSimpleNameNotEndingWith("Transaction")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.quizopia.identity.persistence.entity..");

    @ArchTest
    static final ArchRule noSharedOrCrossServiceDependencies = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.quizopia.shared..",
                    "com.quizopia.quiz..",
                    "com.quizopia.classroom..",
                    "com.quizopia.assessment..",
                    "com.quizopia.community..",
                    "com.quizopia.proctoring..",
                    "com.quizopia.ai..");
}
