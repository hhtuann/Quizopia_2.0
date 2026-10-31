package com.quizopia.gateway;

import static com.quizopia.gateway.W4RealtimeAcceptanceTopologyTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.testcontainers.containers.PostgreSQLContainer;

/** Owned synthetic private demo. Explicit opt-in, also executed by dedicated CI; no public deploy. */
@EnabledIfSystemProperty(named = "w4f.demo", matches = "true")
class AcademicDemoTopologyTest {
    static final PostgreSQLContainer<?> QUIZ_DB = new PostgreSQLContainer<>("postgres:17-alpine");
    static String quiz;
    static String oldBrowser;

    @BeforeAll
    static void start() throws Exception {
        oldBrowser = System.getProperty("w4.browser");
        System.setProperty("w4.browser", "true");
        for (org.testcontainers.containers.GenericContainer<?> container :
                List.of(IDENTITY_DB, CLASSROOM_DB, ASSESSMENT_DB, REDIS, BROKER, QUIZ_DB)) {
            container.withCreateContainerCmdModifier(command -> {
                var bindings = new com.github.dockerjava.api.model.Ports();
                for (Integer port : container.getExposedPorts())
                    bindings.bind(
                            com.github.dockerjava.api.model.ExposedPort.tcp(port),
                            com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1", 0));
                command.getHostConfig().withPortBindings(bindings);
            });
        }
        IDENTITY_DB.start();
        CLASSROOM_DB.start();
        ASSESSMENT_DB.start();
        REDIS.start();
        BROKER.start();
        QUIZ_DB.start();
        startOwnedTopology();
        buildArtifact("quiz");
        quiz = "http://127.0.0.1:" + freePort();
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        try (var c = db(IDENTITY_DB)) {
            UUID client = UUID.randomUUID();
            execute(
                    c,
                    "INSERT INTO oauth2_service_client(id,client_id,client_secret_hash,enabled,created_at,updated_at) VALUES(?,'quiz-service',?,true,now(),now())",
                    client,
                    encoder.encode(SECRET));
            execute(c, "INSERT INTO oauth2_service_client_scope VALUES(?,'identity.user-admission')", client);
            execute(
                    c,
                    "INSERT INTO oauth2_service_client_scope(service_client_id,scope) SELECT id,'quiz.version-snapshot.read' FROM oauth2_service_client WHERE client_id='assessment-service'");
        }
        stopService("identity");
        awaitTrue(() -> httpDead(identity), "Identity port released");
        var identityArgs = new ArrayList<>(processArgumentsOf("identity"));
        identityArgs.removeIf(a -> a.startsWith("--quizopia.identity.user-admission.allowed-clients="));
        identityArgs.add(
                "--quizopia.identity.user-admission.allowed-clients=gateway,classroom-service,assessment-service,quiz-service");
        launch("identity", identityArgs);
        awaitHttp(identity + "/oauth2/jwks");
        var quizArgs = new ArrayList<String>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m",
                "-XX:ActiveProcessorCount=2",
                "-jar",
                repository
                        .resolve("services/quiz-service/target/w4-acceptance-build/quizopia-quiz-0.1.0-SNAPSHOT.jar")
                        .toString(),
                "--server.address=127.0.0.1",
                "--server.port=" + URI.create(quiz).getPort(),
                "--spring.profiles.active=local",
                "--spring.datasource.url=" + QUIZ_DB.getJdbcUrl(),
                "--spring.datasource.username=" + QUIZ_DB.getUsername(),
                "--spring.datasource.password=" + QUIZ_DB.getPassword(),
                "--spring.flyway.user=" + QUIZ_DB.getUsername(),
                "--spring.flyway.password=" + QUIZ_DB.getPassword(),
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + identity,
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + identity + "/oauth2/jwks",
                "--quizopia.admission.enabled=true",
                "--quizopia.admission.allow-insecure-loopback=true",
                "--quizopia.admission.identity-base-url=" + identity,
                "--quizopia.admission.client-id=quiz-service",
                "--quizopia.admission.client-secret=" + SECRET,
                "--quizopia.internal-authority.enabled=true",
                "--quizopia.internal-authority.require-tls=false",
                "--quizopia.internal-authority.assessment-client-id=assessment-service"));
        launch("quiz", quizArgs);
        awaitHttp(quiz + "/actuator/health");
        try (var c = db(ASSESSMENT_DB)) {
            for (UUID actor : List.of(STUDENT, TEACHER, OTHER_STUDENT))
                execute(c, "INSERT INTO assessment_demo_subjects VALUES(?,'FICTIONAL_ACADEMIC_DEMO_V1')", actor);
            execute(c, "CREATE ROLE quizopia_demo_custodian LOGIN PASSWORD 'fictional-demo-only' NOINHERIT");
            execute(c, "GRANT quizopia_assessment_custodian TO quizopia_demo_custodian");
            execute(c, "ALTER ROLE quizopia_demo_custodian SET role TO quizopia_assessment_custodian");
            execute(c, "GRANT USAGE ON SCHEMA public TO quizopia_assessment_custodian,assessment");
        }
        stopService("assessment");
        awaitTrue(() -> httpDead(assessment), "Assessment port released");
        var assessmentArgs = new ArrayList<>(processArgumentsOf("assessment"));
        assessmentArgs.removeIf(a -> a.startsWith("--spring.profiles.active=")
                || a.startsWith("--quizopia.publication.sources.quiz-base-url=")
                || a.startsWith("--spring.datasource.username=")
                || a.startsWith("--spring.datasource.password="));
        byte[] disposableKey = new byte[32];
        RANDOM.nextBytes(disposableKey);
        SECRETS_OBSERVED.add(Base64.getEncoder().encodeToString(disposableKey));
        SECRETS_OBSERVED.add(SECRET);
        SECRETS_OBSERVED.add(PASSWORD);
        assessmentArgs.addAll(List.of(
                "--spring.profiles.active=academic-demo",
                "--spring.datasource.username=assessment",
                "--spring.datasource.password=assessment_local_only",
                "--quizopia.assessment.demo.synthetic-only=true",
                "--quizopia.assessment.demo.current-key=fixture",
                "--quizopia.assessment.demo.keys.fixture=" + Base64.getEncoder().encodeToString(disposableKey),
                "--quizopia.assessment.demo.custodian-user=quizopia_demo_custodian",
                "--quizopia.assessment.demo.custodian-password=fictional-demo-only",
                "--quizopia.publication.sources.quiz-base-url=" + quiz,
                "--quizopia.assessment.closure.cursor-keys.v1="
                        + Base64.getEncoder().encodeToString(new byte[32])));
        launch("assessment", assessmentArgs);
        awaitHttp(assessment + "/actuator/health");
        edgeContext.close();
        edgeContext = gatewayContext(edgeAPort, false, quiz);
    }

    @AfterAll
    static void stop() throws Exception {
        stopOwnedTopology();
        QUIZ_DB.stop();
        BROKER.stop();
        REDIS.stop();
        ASSESSMENT_DB.stop();
        CLASSROOM_DB.stop();
        IDENTITY_DB.stop();
        if (oldBrowser == null) System.clearProperty("w4.browser");
        else System.setProperty("w4.browser", oldBrowser);
    }

    @Test
    void syntheticBrowserTeacherAndStudentWorkflowUsesActualServices() throws Exception {
        String frontend = "http://127.0.0.1:" + frontendPort;
        awaitHttpBrowser(frontend, frontendProcess);
        List<String> command = isWindows()
                ? List.of(
                        "cmd.exe", "/d", "/s", "/c", "pnpm exec playwright test --config playwright.w4f-demo.config.ts")
                : List.of("bash", "-lc", "pnpm exec playwright test --config playwright.w4f-demo.config.ts");
        var builder = new ProcessBuilder(command)
                .directory(repository.resolve("frontend").toFile())
                .redirectErrorStream(true)
                .redirectOutput(evidence.resolve("academic-demo-browser.log").toFile());
        var env = builder.environment();
        env.put("W4F_DEMO_FRONTEND", frontend);
        env.put("W4F_DEMO_GATEWAY", edgeA);
        env.put("W4F_DEMO_TEACHER", email(TEACHER));
        env.put("W4F_DEMO_STUDENT", email(STUDENT));
        env.put("W4F_DEMO_PASSWORD", PASSWORD);
        env.put("W4F_DEMO_CLASSROOM", CLASSROOM.toString());
        var process = builder.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.MINUTES), "Private demo browser timeout");
            assertEquals(0, process.exitValue(), "Private demo browser failed; see academic-demo-browser.log");
            verifyFencedReads();
            for (String name : List.of("identity", "classroom", "assessment", "quiz", "frontend")) {
                String log = Files.readString(evidence.resolve(name + ".log"));
                for (String secret : SECRETS_OBSERVED)
                    assertFalse(log.contains(secret), "Sensitive fixture material in " + name + " log");
                assertFalse(
                        java.util.regex.Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
                                .matcher(log)
                                .find(),
                        "JWT in " + name + " log");
                assertFalse(
                        log.contains("fromOptionLabels")
                                || log.contains("correctAnswer")
                                || log.contains("BEGIN PRIVATE KEY"),
                        "Protected payload in " + name + " log");
            }
        } finally {
            if (process.isAlive()) destroyTree(process);
        }
    }

    static void verifyFencedReads() throws Exception {
        UUID attempt, publication, quizId;
        try (var c = db(ASSESSMENT_DB);
                var s = c.prepareStatement(
                        "SELECT a.id,a.publication_id,p.quiz_id FROM attempts a JOIN publications p ON p.id=a.publication_id WHERE p.owner_id=? AND p.configuration->>'title' LIKE 'Fictional % demo' AND a.state='SUBMITTED' ORDER BY p.created_at LIMIT 1")) {
            s.setObject(1, TEACHER);
            try (var rows = s.executeQuery()) {
                assertTrue(rows.next());
                attempt = rows.getObject(1, UUID.class);
                publication = rows.getObject(2, UUID.class);
                quizId = rows.getObject(3, UUID.class);
            }
            execute(
                    c,
                    "INSERT INTO quiz_revocation_fences(quiz_id,operation_id,revoked_at,source,ack_at) VALUES(?,?,assessment_time(),'COMMAND',assessment_time())",
                    quizId,
                    UUID.randomUUID());
        }
        String base = "/api/assessments/publications/" + publication + "/attempts/" + attempt;
        for (String suffix : List.of("teacher-review", "answer-audit")) {
            var denied = send("GET", edgeA + base + "/" + suffix, TOKENS.get(TEACHER.toString()), null, Map.of());
            assertEquals(404, denied.statusCode());
            assertFalse(denied.body().contains("correctAnswer") || denied.body().contains("transition"));
        }
    }
}
