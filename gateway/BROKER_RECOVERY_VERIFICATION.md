# Gateway broker recovery investigation — 2026-10-29

## Cause and evidence

`W4RealtimeAcceptanceTopologyTest.e3_journey12_brokerOutageAndRecovery`
waited for Assessment's outbox to drain after `rabbitmqctl start_app`, then
published one fresh invalidation probe. Outbox drain proves publisher recovery,
not Gateway consumer recovery. Gateway independently retries after five seconds
and declares a fresh exclusive, auto-delete queue on reconnection.

The original two-journey focused run (journeys 2 and 12) passed. Running the
whole W4 class with diagnostic-only instrumentation reproduced the failure:
15 tests, 1 failure, 0 errors, 1 existing opt-in browser skip. The diagnostic
recorded these exact values:

```text
Gateway consumer connected when Assessment outbox drained: false
Gateway consumer connected after frame wait: true
```

The fresh probe could be published before Gateway's replacement queue was
bound. Reconnection later cannot replay a hint that had no queue to receive it.
This is a test synchronization race, not evidence that the product failed to
reconnect or that another test corrupted the fixture. ADR-016 explicitly permits
lost hints; REST reconciliation remains authoritative.

## Correction

The test now requires Gateway connected before the fault, verifies both broker
commands exit successfully, observes Gateway disconnect during the outage, and
waits for its real consumer to reconnect before publishing the fresh probe.
`RealtimeInvalidationConsumer` publishes its connection reference only after
queue declaration, both bindings and `basicConsume` complete. The test does not
restart Gateway or invoke the consumer's connection method.

All original assertions remain: business REST succeeds during outage, durable
outbox work stays pending, no hint arrives during outage, pending work drains
after recovery, and the fresh frame is `RESOURCE_INVALIDATED` for the correct
attempt within the original 60-second frame timeout. Product recovery code is
unchanged. Sanitized readiness evidence is written to
`target/w4-acceptance-topology/broker-recovery.txt`.

## Executed verification

Toolchain: Microsoft Java 21.0.10 at
`C:\Users\GIGABYTE\.jdks\ms-21.0.10`, Maven 3.9.11, Docker Desktop Linux engine.
From `gateway/`, with `JAVA_HOME` set above and
`MAVEN_OPTS=-Xmx256m -XX:ActiveProcessorCount=2`:

```powershell
& 'C:\Users\GIGABYTE\.m2\wrapper\dists\apache-maven-3.9.11\bin\mvn.cmd' -B '-Dtest=W4RealtimeAcceptanceTopologyTest' test '-DargLine=-Xmx384m -XX:ActiveProcessorCount=2'
& 'C:\Users\GIGABYTE\.m2\wrapper\dists\apache-maven-3.9.11\bin\mvn.cmd' -B verify '-DargLine=-Xmx384m -XX:ActiveProcessorCount=2'
```

- Corrected focused W4 class: **15 tests, 0 failures, 0 errors, 1 skip**.
- Fresh completed full verify at **2026-10-29 22:22:28 +07:00**:
  **181 tests, 0 failures, 0 errors, 1 skip; BUILD SUCCESS**, 14:42 minutes.
- Full-run W4: 15 tests, 0 failures/errors, 1 skip, 185.4 seconds.
- Full-run Proctoring topology: 10 tests, 0 failures/errors/skips, 169.6 seconds.
- Full verify includes ArchUnit, packaging/repackage and Spotless check.
- Skip is the existing Chromium journey, requiring `-Dw4.browser=true`.
- Reports: `target/surefire-reports/`.

Earlier full attempts are not relabeled as successful: one encountered a separate
Proctoring 404-vs-503 denial failure with downstream read timeouts; another was
interrupted. On resumption Docker Desktop was stopped (101 tests, 8 startup
errors); it was started and engine readiness verified before the successful run.

The specifically authorized Assessment contract correction adds required UUID
`quizId` to `proctoring-authority.openapi.yaml`, matching the existing 11-field
response and strict Gateway topology assertions. `swagger-cli validate` passed
for that file. No other Assessment file was changed by this investigation.
