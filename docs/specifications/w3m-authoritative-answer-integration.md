# W3-M authoritative answer persistence integration

Status: **DEFAULT-DISABLED ENGINEERING INCREMENT — IMPLEMENTATION/VERIFICATION
IN PROGRESS; PRODUCTION NO-GO.** This contract supplies the previously missing
Assessment-to-Proctoring metadata adapter. It does not change authoritative answer,
finalization, grading, retention, camera-refusal or release policy. Follow the
[integration checkpoint](../architecture/w3m-integration-implementation.md) for
actual evidence, dependencies and remaining gates.

## Ownership and exact fact meaning

Assessment alone owns answers, revisions, Attempt lifecycle and grades. Proctoring
owns monitoring evidence and receives a minimal durable fact:
**`ASSESSMENT_ANSWER_PERSISTED`**, event version **1**. This means Assessment persisted
an answer row or changed its JSONB answer, not that a Student performed a particular
browser action. Inserts/changed-answer updates during SAVE, complete SUBMIT and
server normalization/finalization can produce the fact. Same-JSONB updates do not
produce a new answer-change fact merely because a revision advanced.

Producer `occurredAt` is the actual database clock at capture. It can be later than
the Attempt deadline when authoritative finalization normalizes/persists answers.
Do not backdate it, assert a timely Student action, infer misconduct or extend
monitoring/Attempt eligibility. Proctoring may attach the historical fact after
local session END; this appends metadata, not browser activity or media permission.
Student ownership binding is not proof the owner personally initiated the write.

Browser `ANSWER_SELECTED/CHANGED/CLEARED` remains untrusted `CLIENT_OBSERVATION`.
The new timeline type is server-only **`ANSWER_PERSISTED`**, source
**`ASSESSMENT_AUTHORITY`**. Browser ingestion cannot claim either this provenance or
an Assessment revision. No answer content, answer digest, Quiz text/key, option,
locator, JWT, SERVICE credential or arbitrary metadata is copied.

## Producer-owned schema and compatibility

The single schema is
[assessment-answer-persisted.v1.schema.json](../../shared/event-contracts/assessment-answer-persisted.v1.schema.json),
with exactly twelve required fields, no extras:

| Field            | Meaning / validation                                                                                                |
| ---------------- | ------------------------------------------------------------------------------------------------------------------- |
| eventId          | Canonical lowercase UUID, immutable producer fact identity.                                                         |
| eventType        | Constant `ASSESSMENT_ANSWER_PERSISTED`.                                                                             |
| eventVersion     | Integer constant 1.                                                                                                 |
| occurredAt       | Actual capture instant; microsecond precision, not client time.                                                     |
| attemptId        | Authoritative Attempt UUID.                                                                                         |
| publicationId    | Authoritative CLASS Publication UUID.                                                                               |
| classroomId      | Exactly one authoritative Classroom UUID.                                                                           |
| studentId        | Attempt owner UUID, not an assertion of a browser action actor.                                                     |
| attemptStartedAt | Original authoritative start instant.                                                                               |
| deadlineAt       | Original bounded Attempt deadline, later than start and at most four hours later.                                   |
| questionNumber   | Positive signed-32-bit local snapshot question number.                                                              |
| revision         | Positive signed-64-bit persistence revision represented as canonical decimal string, maximum `9223372036854775807`. |

Payload decoding rejects duplicate/unknown/missing fields, trailing documents, type
coercion, noncanonical UUID/revision and unsupported type/version. Timestamps must
be supported date-time values at microsecond precision; occurredAt cannot precede
start or be more than thirty seconds ahead of consumer time. An occurredAt after
deadline is permitted for historical normalization facts, not runtime entitlement.
Schema validation is necessary but not publisher authentication or proof of commit.

Proctoring `Evidence` gains nullable string `assessmentRevision`; it is populated
only for this provenance and remains null for legacy browser/server evidence. Hashing
of pre-existing rows remains byte-compatible. New timeline hashes bind the session,
event identity/type/version, observed and receipt times, question, source, owner and
revision. The inbox separately hashes the full canonical twelve-field payload;
session attachment verifies Publication, Classroom, Student and deadline bindings. Existing
M1 V1 and M2 V2 migrations remain immutable; additive Proctoring V3 adds inbox,
revision/source constraints, unique `(session,question,revision)` and rejection metadata.

## Assessment capture and outbox

All six pre-existing Assessment migrations remain unchanged. Additive V6 installs
an AFTER INSERT/changed-answer UPDATE trigger on Assessment-owned `attempt_answers`
and an immutable metadata outbox. No answer repository/finalizer or public API is
rewritten. Capture occurs inside the same authoritative database transaction as
the answer change; rollback removes both. A local outbox storage failure causes a
coherent Assessment transaction failure, never a committed answer with fabricated
success evidence. Broker/Proctoring availability is not consulted on this path.

Capture is default OFF: the trigger returns before extra lookups/construction unless
connection GUC `quizopia.monitoring_capture` is true. A typed explicit capture setting
initializes a dedicated unstarted Hikari pool with that fixed connection setting;
reserved/conflicting initialization SQL is rejected. It does not change dynamically
per HTTP request. Only ACTIVE bounded Attempts in monitored CLASS Publications are
eligible for capture; PUBLIC/unlimited/DRAFT/unmonitored work is excluded.

Publisher is a separate default-off worker, with no Rabbit I/O inside answer/local
claim transactions. It fairly claims up to five pending rows with `SKIP LOCKED`, a
sixty-second owner lease and bounded lock/statement budgets. Persistent mandatory
publishes require broker confirmation and must not have been returned as unroutable.
Only then mark the producer row published. Crashes, lost confirms or uncertain local
acknowledgment preserve retryable state; delivery is at least once, not a fictitious
exactly-once transport. Immutable event identity/payload supports durable consumer dedupe.

Runtime code performs no DDL/topology declaration; reviewed migrations and operator
provisioning are separate. Trigger/outbox owner/runtime privileges must preserve
payload identity and published history. No cleanup/retention duration is invented.

## Broker topology, provenance and transport

| Resource            | Exact declaration / allowed caller                                                  |
| ------------------- | ----------------------------------------------------------------------------------- |
| Exchange            | `quizopia.assessment.monitoring.v1`, durable direct.                                |
| Routing key         | `answer.persisted.v1`.                                                              |
| Queue               | `quizopia.proctoring.assessment-monitoring.v1`, durable quorum, bound to that key.  |
| Assessment identity | Dedicated private-vhost publisher; write permission on the exact exchange only.     |
| Proctoring identity | Separate dedicated private-vhost consumer; read permission on the exact queue only. |

Operators provision topology/ACLs in an approved dedicated vhost, never the default
`/`. Runtime identities have no topology configure permission, broker administrator
or impersonator tag. RabbitMQ verifies message `userId` against its authenticated
publisher; consumer also requires the exact configured publisher identity and
`appId=assessment-service`, matching type/messageId, JSON content type and persistent
`deliveryMode=2`. Application metadata alone is not trustworthy without actual
broker user-ID enforcement and least-privilege ACLs. Compromised publisher/admin
credentials remain a trust-boundary risk, not something a JSON hash can eliminate.

Broker clients use system-trusted TLS and hostname verification, bounded connection/
handshake/RPC/confirmation and body limits, no automatic topology recovery and no
credential logging. Trust-all `useSslProtocol()` defaults are not used. Plaintext
is allowed only through explicit literal-loopback synthetic settings, never as
deployed TLS evidence. One-node quorum fixture tests do not establish broker HA.

## Consumer inbox, acknowledgment and poison recovery

Application envelope maximum is 4 KiB; transport maximum is 64 KiB. Consume manually
without ACK until validated metadata and dedupe identity are durably committed to
Proctoring's inbox. Malformed/provenance-invalid payloads within transport bounds
are fingerprinted into durable rejection metadata before ACK; raw malformed body
and confidential data are not retained. Database failure NACKs with requeue; no
in-memory acceptance or successful ACK substitutes for durable state.

A payload exceeding the transport cap can fail before a full fingerprint is
available. It remains a blocking broker poison condition requiring approved audited
operator quarantine and recovery; no automatic discard, silent ACK or unreviewed
DLQ/redrive bypass. Retain delivery/incident provenance without copying protected
content into public logs.

Inbox duplicate eventId with identical canonical payload is safe. Conflicting eventId,
revision or bindings is quarantined/fingerprinted rather than replacing evidence.
Facts received before a monitoring session wait durably; the consumer neither creates
nor resurrects a session. Draining joins an existing session by Attempt and verifies
Publication/Classroom/Student/deadline bindings. Historical facts may append after
END under the distinct source/revision guards and cannot reopen monitoring or media.

Late/out-of-order facts are allowed; server receipt ordinal still governs timeline
pagination, while producer occurredAt/revision identify the historical fact. Neither
arrival order nor revision permits rewriting older entries. Consumer/publisher
restart and duplicate delivery must converge without duplicate evidence or terminal
state changes. Missing sessions and quarantined work require restricted operational
visibility and auditable recovery, not broad resource discovery or answer access.

## Configuration, privacy and release boundary

Actual Assessment defaults: `ASSESSMENT_MONITORING_CAPTURE_ENABLED=false`,
`ASSESSMENT_MONITORING_PUBLISHER_ENABLED=false`, blank private broker settings,
port 5671 and insecure-loopback false. Proctoring defaults
`PROCTORING_ASSESSMENT_EVENTS_ENABLED=false`, TLS true, insecure-loopback false,
blank dedicated broker credentials/vhost and exact publisher identity. Production
capture, publication or consumption is unconditionally rejected pending policy/
security acceptance; local flags/references are not formal approval.

Approved retention/access/erasure and monitoring privacy remain PROCTOR-01/07/11/12
release gates. No real-learner indefinite retention, guessed TTL, deletion schedule,
refusal outcome or academic sanction is introduced. No media enforcement, Cloud
qualification or eleven-gate Assessment release acceptance follows from this adapter.
EVT-02/03 mechanics are specified narrowly for this workflow; global Rabbit/DLQ
conventions and EVT-01 gradebook result events remain separate decisions.

## Required verification

Prove fresh/upgrade checksum preservation, default-off no-capture and production
denial; changed-answer/normalization capture and rollback; capture pool settings;
broker mandatory-return/confirm/persistent/user-ID/ACL/TLS negative cases; restart/
lease recovery; strict schema boundaries; durable ACK/NACK and no raw invalid-body
storage; duplicate/conflicting/out-of-order/pre-session/post-END facts; hash/revision/
binding integrity and protected answer/result/receipt fingerprints. Record actual
commands/counts/limitations in the integration checkpoint after execution. A unit
mock, one-node broker or loopback topology is not deployed security/HA acceptance.
