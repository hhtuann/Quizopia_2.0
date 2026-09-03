# Business Rules

Status: **Baseline v0.1**

## Identity

1. Every verified registered account receives `STUDENT`.
2. A verified user may additionally enable `TEACHER`.
3. `ACADEMIC_ADMIN` does not exist in Quizopia 2.0 baseline.
4. Local login and Google login may represent the same internal user.
5. Local registration requires Gmail verification by OTP before activation.
6. A user's learning/teaching workspace is UI context, not a destructive role switch.

## Classroom

7. Teacher may create owned classrooms.
8. Students may join via code/invitation URL according to the final join policy.
9. Teacher may manually add a student by required full name + Gmail.
10. If the Gmail does not belong to an existing account, keep a pending invitation rather than creating a fake user.
11. A later account that verifies the matching Gmail can claim the pending classroom membership.

## Quiz authoring

12. Teacher can organize quizzes in folders.
13. Quiz supports `SINGLE_CHOICE`, `MULTIPLE_CHOICE`, `TRUE_FALSE_MATRIX`, `NUMERIC_FILL`.
14. NUMERIC_FILL keeps the fixed four-character answer requirement.
15. Quiz Markdown must be validated before the teacher proceeds to publication configuration.
16. Published quiz versions are immutable.
17. Editing after publication creates later draft/version content; old published versions remain unchanged.

## Publication

18. Quiz content (`QuizVersion`) and delivery configuration (`Publication`) are separate.
19. Publication modes include `ASSESSMENT` and `PRACTICE`.
20. Assessment may be `PUBLIC` or `CLASS`.
21. Eligible public assessment may allow guest participation.
22. Class publication requires authenticated classroom membership.
23. Practice does not use assessment timing configuration.

## Assessment time mutation

24. Before any attempt starts, editable timing configuration follows normal publication rules.
25. After the first attempt starts, availability end must not move earlier.
26. After the first attempt starts, attempt duration must not be reduced.
27. Time may be extended according to authorization/business rules.

## Attempt correctness

28. Server time is authoritative.
29. Attempt question/option order is stable after attempt creation.
30. Autosave must prevent older delayed writes from overwriting newer answers.
31. Submit must be idempotent.
32. Submit + grading + authoritative result persistence must be transactionally coherent.
33. Active attempts must not depend on mutable Quiz Service content.

## Results/review

34. Score visibility and answer-review visibility are publication policies.
35. Policy design must avoid contradictory boolean combinations.
36. Hidden answer keys must not leak through learner APIs/realtime payloads.

## Proctoring

37. Proctoring is allowed only for one-class, authenticated, bounded-time assessments.
38. Proctoring starts with attempt start.
39. Proctoring ends with submit or server deadline.
40. Standard browser monitoring may record visibility/focus/fullscreen/camera/network/attempt events.
41. Standard browser code must not claim it can inspect unrelated external tabs/URLs.
42. Proctoring AI flags suspicious events for human review.
43. AI flags do not automatically fail a learner or declare cheating as fact.
44. Proctoring evidence has short-lived retention; exact default is TBD.
45. Full video recording is deferred from MVP.

## Community

46. Teachers may publish contribution posts referencing eligible public quiz content.
47. Posts support reactions and comments.
48. Ratings attach conceptually to the public quiz rather than an individual post.
49. Eligible public quizzes may be copyable/forkable.
50. Forks create a new owned quiz and preserve source provenance.
51. Copy/fork respects source owner permission.

## Architecture

52. Services do not access each other's databases.
53. Cross-service integration uses APIs/events.
54. PostgreSQL is the baseline transactional source of truth.
55. REST handles authoritative business mutations.
56. WebSocket handles application realtime events.
57. WebRTC/LiveKit handles realtime media.
