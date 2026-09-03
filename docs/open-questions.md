# Open Questions

Status: **Active**

These items are intentionally not treated as accepted requirements yet.

## Identity

1. Does "Gmail required" mean strictly `@gmail.com`, or also Google Workspace/custom-domain Google accounts?
2. OTP expiry, max attempts, resend cooldown?
3. Exact access-token architecture for microservices:
   - gateway validation;
   - service validation;
   - signing algorithm/JWKS;
   - revocation strategy?
4. Exact account-link conflict UX?

## Classroom

5. Does class-code/invite-link join become active immediately or require teacher approval?
6. Can students leave a class themselves?
7. Exact classroom subject/grade taxonomy?
8. Late assignment policy?

## Quiz Markdown

9. Final explicit question-type syntax?
10. Final NUMERIC_FILL Markdown answer syntax?
11. Final allowed four-character NUMERIC_FILL character/format rules?
12. Markdown support for:
    - LaTeX;
    - code blocks;
    - images;
    - explanations?
13. Does canonical Markdown preserve teacher formatting exactly or normalize on save?

## Import/export

14. Exact Excel import template?
15. Exact DOCX import template?
16. Exact offline DOCX exam format?
17. Exact shuffled paper/answer-key requirements?

## Publication/assessment

18. Exact publication persisted statuses?
19. Exact score visibility enum names/behavior?
20. Exact review visibility enum names/behavior?
21. Password storage/rate-limit policy?
22. Guest nickname required or optional?
23. Anonymous attempt cookie/session design?
24. Exact public URL/slug format?
25. Exact policy for changing availability start after attempts have begun?

## Practice

26. Does practice persist attempts like assessment or a separate learning-progress model?
27. Spaced repetition in scope?
28. Exact AI tutor policy?

## Community

29. Exact visibility names: `PRIVATE`, `UNLISTED`, `PUBLIC`?
30. Can students create community posts, or teachers only in initial scope?
31. Rating scale (for example 1-5)?
32. Comment editing/deletion policy?
33. Moderation/report workflow?

## Proctoring

34. Evidence retention default: 7 days, 30 days, or another platform value?
35. Snapshot frequency/trigger?
36. Which AI suspicious-event detectors are in MVP vs later?
37. Is microphone ever required?
38. Is strict screen-sharing mode in roadmap?
39. Exact teacher violation-review workflow?
40. Exact LiveKit deployment choice for production: self-hosted or managed?

## AI

41. Model/provider?
42. Exact document types and limits?
43. RAG/vector-store final choice?
44. Source citation/provenance UX?
45. Usage quotas/cost controls?

## Infrastructure

46. API Gateway implementation?
47. Event broker: RabbitMQ or another technology?
48. Transactional outbox implementation?
49. Monorepo tool/build orchestration?
50. Deployment target and production observability stack?
