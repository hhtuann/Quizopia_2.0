# Quizopia 2.0 — DESIGN.md

> **Status:** UI/UX source of truth for Quizopia 2.0  
> **Design direction:** Corporate Trust — Enterprise Template fidelity with Quizopia product adaptations  
> **Primary stack target:** Next.js + React + Tailwind CSS 4  
> **Theme baseline:** Light mode only for the current baseline

---

## 1. Purpose

This document defines the visual language, interaction rules, accessibility expectations, and reusable UI conventions for Quizopia 2.0.

It is the source of truth for frontend visual and interaction design.

Priority order when making UI decisions:

1. accessibility and usability
2. product correctness
3. consistency with this `DESIGN.md`
4. reuse of established components and patterns
5. visual novelty

Do not create a new visual pattern when an established pattern already solves the same problem.

If a feature genuinely requires a new reusable pattern:

- design it deliberately;
- make it reusable;
- document it here if it becomes part of the system.

Feature-specific exceptions must not silently redefine the global design system.

---

# 2. Design Style — Corporate Trust

## 2.1 Design Philosophy

Quizopia uses a **modern enterprise SaaS aesthetic**: professional yet approachable, sophisticated yet friendly.

The experience should feel trustworthy enough for education and assessment workflows while remaining warm, modern, and inviting.

### Core Principles

- **Trustworthy Yet Vibrant**  
  Clean structure and strong readability establish credibility, while signature Indigo-to-Violet gradients, colored shadows, and lively accents keep the UI energetic.

- **Refined Elegance**  
  Surfaces, typography, spacing, interactions, and transitions should feel polished without becoming decorative noise.

- **Dimensional Depth**  
  Soft Indigo/Violet-tinted shadows, raised surfaces, and deliberate dimensional composition create depth. Decorative 3D/isometric treatments are reserved for suitable showcase visuals and must not impair usability.

- **Purposeful Gradients**  
  Indigo-to-Violet gradients are a defining brand treatment for primary CTAs, selected headline emphasis, and high-value accent moments. Use them consistently rather than reverting primary actions to generic solid buttons.

- **Professional Polish**  
  Generous spacing, strong hierarchy, consistent alignment, and predictable interaction patterns are more important than visual novelty.

### Keywords

Trustworthy, Vibrant, Polished, Dimensional, Modern, Approachable, Enterprise-Ready, Elegant.

---

# 3. Design Contexts

Quizopia has two visual contexts. They share the same tokens and typography but use different levels of decoration.

## 3.1 Marketing / Public Pages

Examples:

- landing page
- public quiz discovery
- about/product pages
- selected promotional sections

These pages should visibly embody the full Corporate Trust visual language:

- gradient headlines
- atmospheric blobs
- isometric illustrations
- elevated cards
- decorative motion
- stronger brand gradients

## 3.2 Product / Application UI

Examples:

- authenticated dashboard
- classrooms
- quiz library
- quiz editor
- assessments
- gradebook
- community management
- settings
- administration
- authentication forms

Application UI must prioritize:

- clarity
- task completion
- information density
- stability
- keyboard accessibility
- predictable states

For product UI:

- use the canonical Indigo-to-Violet gradient on high-emphasis primary actions (including Save/Publish where primary by context), with hierarchy preventing competing CTAs;
- allow gradient emphasis in prominent page headings, while keeping routine labels, forms, and data plain for readability;
- retain tinted shadows, friendly rounding, and expressive brand accents on interactive surfaces;
- keep atmospheric blobs subtle and outside dense editing/reading areas;
- do not apply 3D transforms to functional controls or content requiring precise interaction;
- apply restrained hover lift only to interactive cards; do not move information-only panels;
- prioritize stable layouts, keyboard access, contrast, and information clarity.

---

# 4. Design Token System

All reusable design decisions should be represented through centralized tokens or reusable component variants.

Avoid repeatedly hard-coding design values in feature components.

## 4.1 Core Colors — Light Mode

### Surfaces

- **Background:** `#F8FAFC` — Slate 50
- **Surface:** `#FFFFFF` — White
- **Surface Muted:** `#F1F5F9` — Slate 100
- **Surface Strong:** `#E2E8F0` — Slate 200

### Brand

- **Primary:** `#4F46E5` — Indigo 600
- **Primary Hover:** `#4338CA` — Indigo 700
- **Secondary:** `#7C3AED` — Violet 600
- **Secondary Hover:** `#6D28D9` — Violet 700

### Text

- **Text Primary:** `#0F172A` — Slate 900
- **Text Secondary:** `#475569` — Slate 600
- **Text Muted:** `#64748B` — Slate 500
- **Text Disabled:** `#94A3B8` — Slate 400
- **Text Inverse:** `#FFFFFF`

### Borders & Focus

- **Border:** `#E2E8F0` — Slate 200
- **Border Strong:** `#CBD5E1` — Slate 300
- **Focus:** `#6366F1` — Indigo 500

## 4.2 Semantic Colors

Semantic colors communicate meaning and must not be replaced by arbitrary brand colors.

- **Success:** `#10B981` — Emerald 500
- **Warning:** `#F59E0B` — Amber 500
- **Danger:** `#DC2626` — Red 600
- **Info:** `#2563EB` — Blue 600

Rules:

- do not use Primary/Secondary to represent success, warning, or error;
- never rely on color alone;
- pair semantic color with text and/or icon where practical;
- error states must remain understandable for users with color-vision deficiencies.

## 4.3 Recommended Semantic Token Names

Use semantic naming in the styling layer rather than scattering raw colors:

```text
--color-bg
--color-surface
--color-surface-muted
--color-text
--color-text-secondary
--color-text-muted
--color-border
--color-border-strong
--color-primary
--color-primary-hover
--color-secondary
--color-success
--color-warning
--color-danger
--color-info
--color-focus
```

---

# 5. Typography

## 5.1 Font roles

Quizopia uses a Corporate Trust UI font plus two intentional product-specific roles, all managed via `next/font`:

- **Plus Jakarta Sans — primary Corporate Trust font:** page headings, section headings, cards, paragraphs, navigation, forms, buttons, and normal application copy. Use 700–800 for display emphasis and 400–600 for UI, mirroring the source template;
- **Calistoga — Quizopia brand exception:** existing wordmark and intentionally branded display lockups only; do not extend it into routine page and pane headings;
- **JetBrains Mono — source and technical labels:** the editable Quiz Markdown
  source text, code, line numbers, question-type chips, badges, compact status
  tokens, and technical identifiers where fixed-width clarity adds value.

`Quiz Markdown source` and `Live preview` are pane headings, so they use
Plus Jakarta Sans with matching 700 weights. The source characters edited beneath the heading use JetBrains Mono.

Reserve Calistoga for the established wordmark and rare approved brand lockups. Use
Plus Jakarta Sans consistently for visual hierarchy, cards, forms, and controls.
Use JetBrains Mono selectively; ordinary prose and controls use Plus Jakarta Sans.

Fonts are loaded through `next/font` with appropriate fallback stacks. Do not
add a second web-font loader or runtime stylesheet request.

## 5.2 Weights

- Plus Jakarta Sans display/hero headings: ExtraBold `800`
- Plus Jakarta Sans section/page headings: Bold `700`
- Calistoga wordmark: Regular `400`
- Subheadings/Card Titles: SemiBold `600`
- Navigation/Labels: Medium `500`
- Body: Regular `400`

## 5.3 Line Heights

- Display/Hero: `1.05–1.15`
- Headings: `1.15–1.3`
- Body: `1.5–1.7`
- Dense metadata/table text: `1.4–1.5`

## 5.4 Letter Spacing

Large display text may use approximately `-0.02em`.

Do not aggressively tighten body or form text.

## 5.5 Product Typography Scale

Use a predictable scale rather than arbitrary feature-level sizes.

- **Display:** `48–60px` — marketing hero only
- **H1:** `36–40px` marketing, normally `28–32px` inside application screens
- **H2:** `28–32px`
- **H3:** `24px`
- **H4:** `20px`
- **Body Large:** `18px`
- **Body:** `16px`
- **Body Small:** `14px`
- **Caption/Metadata:** `12px`

Application dashboards and editors must not use marketing-scale typography for routine page headings.

## 5.6 Quizopia brand signature

The reusable Quizopia logo consists of:

1. a clean geometric lightning bolt inside an Indigo-to-Violet rounded block;
2. a two-line wordmark to its right;
3. `Quizopia` on line one, with `Quiz` in Text Primary and `opia` using the
   restrained Primary-to-Secondary gradient;
4. understated Plus Jakarta Sans text `version 2.0` on line two, tucked closely beneath the
   product name so both lines read as one compact lockup.

`Quizopia` uses Calistoga as an explicit brand exception. The version line uses Plus Jakarta Sans. One canonical bolt path
defines the silhouette for navigation, authentication, focused authoring
chrome, and favicon/app-icon assets. The favicon may use its own favicon-safe
rounded block, but must not redraw or substitute the bolt geometry. Do not
reintroduce the legacy letter `Q` badge or an unrelated blue favicon. Reuse the
centralized brand components rather than duplicating SVG paths or wordmark
markup.

### Consistency across application workspaces

The focused Quiz Editor header is the reference for compact application chrome:
use the shared Quizopia mark (`size-9`), consistent wordmark and `version 2.0`
placement, vertical alignment, and restrained `py-2` header spacing. Keep
responsive wordmark visibility consistent between the main app and editor.
Prefer shared brand components and tokens over page-specific logo sizes.

Application navigation and focused editor headers share one chrome geometry:
horizontal header padding, minimum row height, flexible gaps, and centered
brand alignment. Match the rendered brand left/top position and desktop header
height across `/app`, `/app/quizzes`, and the Quiz Editor, while allowing
additional header rows on narrow screens when controls require them. Keep the
wordmark typography and version subtitle identical at the same breakpoint.
Reuse the shared header geometry rules without changing content-container
padding. Playwright regression tests must compare rendered header and brand
rectangles across routes at desktop and mobile viewport widths, including
horizontal overflow and touch-control visibility.

Full-page workspaces such as Quiz Editor must offer an explicit, keyboard-
accessible way back to the parent application (`Back to app`); the logo alone
is not sufficient. When an editor has unsaved changes, confirm before leaving
and keep editing state intact if the user cancels. Never silently save or
discard the draft.

Editable metadata, including Quiz title, must have a visible associated label,
an input border and distinct surface at rest, usable padding, and an obvious
keyboard focus ring. Do not disguise an editable input as static heading text.

Dialogs should have one dismiss action when multiple controls do the same
thing. An icon-only X must have a meaningful accessible name, a touch target
of at least 44px, visible keyboard focus, Escape dismissal and focus restoration.

Keep navigation and editor header controls available on narrow/mobile screens
using wrapping or stacking rather than forcing horizontal scrolling. Preserve
the visual hierarchy and compact brand proportions at supported viewports.

---

# 6. Radius & Borders

## 6.1 Radius

- Cards / panels: `12px` (`rounded-xl`), optionally `16px` for large elevated showcases
- Inputs / selects: `8px` (`rounded-lg`)
- Primary/secondary buttons: `rounded-full` for prominent CTAs and `rounded-xl` (12px) for compact application toolbars; both are first-class, shared variants
- Compact icon-only controls: `rounded-lg` or circular as appropriate
- Avatars/status dots: circular

### Default Rule

**Choose generously rounded, visually soft controls, as in the Enterprise template.** Avoid a square/boxy button appearance and do not use `rounded-lg` plus excessive vertical padding as the universal product-button recipe. Shared button sizes must be content-appropriate: compact 32–36px where appropriate, standard 36–40px, and 44px or greater when touch targets require it. If a visually compact control has a hit area smaller than 44px, enlarge its interactive target without distorting visual proportions. Keep consistent heights within a toolbar.

## 6.2 Borders

- Default border: 1px `Border`
- Strong separation: 1px `Border Strong`
- Avoid heavy outlines unless semantic state requires it

---

# 7. Shadows, Depth & Effects

## 7.1 Default Shadows

- **Card:** `0 4px 20px -2px rgba(79, 70, 229, 0.10)`
- **Card Hover:** `0 10px 25px -5px rgba(79, 70, 229, 0.15), 0 8px 10px -6px rgba(79, 70, 229, 0.10)`
- **Primary Button:** `0 4px 14px 0 rgba(79, 70, 229, 0.30)`

Use elevation selectively in application UI.

Flat or lightly bordered surfaces are preferred for:

- tables
- editors
- nested panels
- dense dashboards

## 7.2 Gradients

### Primary Gradient

Indigo 600 → Violet 600.

### Required / Recommended Uses

- default high-emphasis primary CTA and page-level primary action (including Create quiz and Publish);
- prominent heading emphasis: split a headline between Slate 900 and gradient text where meaningful;
- selected navigation/active-brand treatments, key visual accents, and hero illustrations;
- atmospheric illustrations and promotional sections as appropriate.

### Discipline, Not Suppression

- gradients communicate priority; do not make every button primary;
- data tables, ordinary field labels, captions, body copy, and rich-text answers stay solid/readable;
- accessible contrast must be verified across the entire gradient, including disabled/hover states;
- gradient decoration must not obscure editor text or interfere with functional content;
- secondary and tertiary actions remain visually subordinate. A heading emphasis and primary gradient CTA can coexist when hierarchy is clear.

## 7.3 Decorative 3D / Isometric Treatments

Allowed only for decorative or promotional visuals.

Never apply perspective or rotation to:

- forms
- tables
- navigation
- dialogs
- editors
- assessment controls
- quiz answer controls
- content that users must accurately read or manipulate

---

# 8. Spacing & Layout

## 8.1 Container

Marketing:

- `max-w-7xl` / approximately 1280px

Application:

- use width based on task needs;
- dense editors and tables may use wider content regions;
- reading-heavy pages should constrain line length.

## 8.2 Horizontal Gutters

Recommended:

- mobile: `px-4`
- small/tablet: `sm:px-6`
- large desktop: `lg:px-8`

## 8.3 Vertical Rhythm

Marketing:

- mobile: `py-16`
- tablet: `py-20`
- desktop: `py-24`

Application:

- page sections usually use tighter rhythm;
- prefer approximately 16–32px between related UI blocks;
- use 40–64px only for major page-level separation.

## 8.4 Text Width

Long-form paragraphs should normally remain around 60–75 characters per line.

Use `max-w-xl`, `max-w-2xl`, or equivalent where appropriate.

---

# 9. Application Shell

Authenticated application pages should share a consistent shell.

## 9.1 Desktop

May include:

- persistent or collapsible primary navigation
- top utility area if needed
- page content region
- optional contextual secondary navigation

## 9.2 Mobile

- simplify navigation without hiding critical actions;
- ensure no horizontal overflow;
- authentication and primary task actions must remain discoverable;
- use drawers/sheets/compact navigation only if consistent with the established component strategy.

## 9.3 Workspace Identity

Quizopia supports UI personas/workspaces:

- `LEARNING`
- `TEACHING`

These are frontend workspace concepts.

Switching workspace:

- must not imply backend role mutation;
- must not require token mutation merely to change UI persona;
- may change navigation and default landing context.

## 9.4 Authorization Boundary

Role-aware navigation is UX guidance only.

**Hiding a navigation item is never an authorization boundary.**

Backend/Gateway/service authorization remains authoritative.

## 9.5 Authenticated user menu

Normal authenticated application pages use one user control in the navbar that
shows a deterministic avatar fallback, username, and current Learning/Teaching
workspace. Its accessible menu owns account-settings intent, permitted
workspace switching, and sign out. Do not add a second standalone workspace
strip or a separate navbar sign-out button.

Unavailable profile/avatar editing or teacher self-enablement must be presented
truthfully until Identity exposes accepted APIs. Frontend workspace switching
must never mutate roles or tokens.

## 9.6 Focused authoring surfaces

Quiz editor routes are an intentional shell exception. They use compact,
viewport-filling editor chrome with a library return path, inline editable
title, save state, Save, and Publish. The Markdown editor and live preview share
the remaining desktop width and height and scroll independently. Narrow screens
use an accessible Editor/Preview switch without viewport-level horizontal
overflow.

Description belongs in the pre-publication interaction rather than occupying
the permanent editor workspace. QuizVersion publication must not be presented
as Assessment timing, audience, or classroom configuration.

The editor and preview panes use one shared header hierarchy: matching
Plus Jakarta Sans bold titles plus matching Plus Jakarta Sans helper size, color, line height, and
spacing. Pane headers should align visually even when helper copy lengths
differ.

The source pane behaves like a focused code editor while preserving exact
authoring text. Tab accepts a visible completion; otherwise it inserts a literal
tab or indents selected lines. Shift+Tab removes one leading tab only. Explicit
preview navigation may suppress autocomplete for that navigation event so
moving a correctness marker does not imply that the teacher started typing a
new option. Normal typed completion resumes immediately after user input.

Exact source offsets are the editor's authoritative caret coordinates.
Conversions to textarea offsets, one-based line/column locations, and visual
columns must preserve LF/CRLF source and expand literal tabs only for display
geometry. Line numbers, active-line highlighting, syntax highlighting,
diagnostic navigation, Preview navigation, and autocomplete positioning share
that coordinate model. The syntax-highlight mirror and editable textarea must
also use identical font family, size, weight, line height, letter spacing, and
tab metrics so visible text remains pixel-aligned with the native caret. Syntax
color may differ, but metric-changing emphasis must not shift the mirror.
Programmatic navigation must synchronize those visual layers from the
textarea's actual scroll offsets after the browser applies its scroll bounds;
an unclamped requested scroll position must never drive the mirror, gutter, or
active-line position.

Preview correctness changes first apply the minimal `*` edit, then locate the
updated option in the post-edit source and place the caret at its content start.
Programmatic navigation suppresses autocomplete only for that focus state;
manual caret movement and typing restore normal assistance without timers.

Autocomplete progressively matches the complete canonical question, option,
`Đáp án:`, or `Lời giải:` marker without removing Vietnamese diacritics.
Matching is case-insensitive, while accepted insertion remains canonical and
the backend grammar remains authoritative. A manual caret within an existing
question or option marker may replace only that exact marker range. Completion
stays disabled in prose, indented structural lookalikes, fenced code, and
invalid parser states. Completion also stays disabled while the textarea has a
non-collapsed text selection; suggestions resume only after the selection
collapses back to a single caret position. The listbox follows the visual caret, accounts for tabs
and editor scrolling, clamps horizontally, and flips above near the visible
bottom while preserving its keyboard and screen-reader semantics.

---

# 10. Buttons

## 10.1 Primary Product Button

Default high-emphasis primary product button:

- `linear-gradient(90deg, #4F46E5, #7C3AED)` (Indigo 600 → Violet 600), using centralized design tokens;
- white text (verify contrast across both stops);
- `rounded-full` for prominent CTAs, or shared `rounded-xl` compact toolbar variant;
- appropriately compact height/padding, not oversized square controls;
- soft brand shadow `0 4px 14px 0 rgba(79, 70, 229, 0.30)`;
- visible focus ring without obscuring the gradient.

Hover:

- gentle `-translate-y-0.5`, stronger tinted shadow, and subtle gradient evolution when appropriate;
- no movement when `prefers-reduced-motion` is active.

Active:

- remove or reduce lift
- visually acknowledge press

## 10.2 Marketing Primary CTA

Uses the same canonical Indigo → Violet gradient as product primary CTAs, with room for stronger shadow, pill silhouette, and refined lift. Marketing is not the only context permitted to use the signature gradient.

## 10.3 Secondary Button

- white/surface background;
- brand-tinted border and Indigo 600 text for brand-adjacent actions (such as Published versions beside Publish), with readable contrast;
- neutral Border and Slate 700 text remain valid for low-emphasis utility actions;
- rounded silhouette aligned to adjacent primary controls;
- hover to subtle Indigo 50/Slate 50 and stronger border; visible focus ring.

Use explicit `brand-outline` and `neutral-outline` shared variants; do not make all white buttons indistinguishable gray.

## 10.4 Destructive Button

Use Danger semantics.

Do not style destructive actions as Primary.

## 10.5 Required States

Every button variant must define:

- default
- hover
- active
- focus-visible
- disabled
- loading

Disabled buttons:

- must remain readable;
- must not show hover/lift behavior;
- should not rely on opacity alone.

Loading buttons:

- disable duplicate submission;
- retain width where practical;
- expose loading state accessibly.

---

# 11. Forms & Inputs

## 11.1 Input Base

- `bg-white`
- Border
- rounded-lg
- readable text
- placeholder uses Text Muted
- consistent control height

## 11.2 Focus

Use a clearly visible focus state:

```text
ring-2
focus token
ring offset where needed
```

Never remove focus indicators.

## 11.3 Labels

- `text-sm`
- medium or semibold
- Text Secondary / strong readable color

Labels remain visible.

**Placeholder text must not replace labels.**

## 11.4 Field Structure

A field may include:

1. label
2. optional helper text
3. control
4. validation message

## 11.5 Validation

- show errors near the relevant field;
- use Danger color + text/icon where helpful;
- do not rely on red border alone;
- preserve user-entered values after validation failure;
- keep required indicators consistent.

Non-field-specific submission errors should appear in a form-level alert.

## 11.6 Password Fields

May use show/hide controls where appropriate.

The toggle must:

- have accessible labeling;
- not change field content;
- remain keyboard accessible.

---

# 12. Cards & Panels

## 12.1 Base Product Card

- Surface/white background
- rounded-xl (12px)
- subtle Slate 100 border
- soft colored shadow (`0 4px 20px -2px rgba(79, 70, 229, 0.10)`), reducing elevation only when density or nested-panel clarity requires it

## 12.2 Hover

Interactive cards use `hover:-translate-y-1` with the canonical layered Indigo-tinted hover shadow and a smooth 200ms transition. Static informational panels do not lift. Preserve content positions and interaction accuracy.

## 12.3 Feature / Marketing Cards

May use:

- soft brand icon containers
- colored shadows
- stronger hover elevation
- selective decorative transforms

---

# 13. Data-Dense UI

Quizopia includes data-heavy workflows.

Examples:

- class roster
- quiz library
- attempt history
- gradebook
- assessment records
- admin screens

## 13.1 Tables

Tables should provide:

- strong column alignment
- readable headers
- optional Surface Muted header
- stable row height
- keyboard-accessible row actions
- clear selected/hover states where applicable

Do not replace naturally tabular data with cards on desktop merely for visual style.

On small screens:

- use responsive column prioritization;
- stacked rows/cards may be used when necessary;
- horizontal scrolling should be a deliberate last resort.

## 13.2 Tabs

- clear active indicator
- keyboard navigation where implementation supports it
- do not encode state only by color

## 13.3 Badges / Status

Use semantic status color only when the badge communicates status.

Neutral metadata badges should remain neutral.

## 13.4 Pagination

Provide:

- clear current page
- disabled states
- accessible labels
- stable layout

---

# 14. Dialogs, Menus & Overlays

## 14.1 Dialog

Use dialogs for focused actions that should interrupt the current workflow.

Must support:

- focus management
- keyboard dismissal where appropriate
- clear title
- clear primary/secondary actions
- destructive confirmation wording where necessary

## 14.2 Dropdown / Menu

- keyboard accessible
- obvious selected/active state
- adequate target size
- do not hide critical destructive actions without clear labeling

## 14.3 Tooltip

Use for short supplementary information only.

Do not place essential instructions exclusively in tooltips.

---

# 15. Application States

Every data-backed or asynchronous screen must deliberately handle:

- loading
- empty
- error
- partial/degraded
- populated state

## 15.1 Loading

Use skeletons when preserving layout improves perceived performance.

Use spinners for:

- compact actions
- short isolated operations

Avoid replacing the entire page with a spinner when structure is already known.

## 15.2 Empty State

A useful empty state should explain:

1. what is empty
2. why it matters
3. the primary next action, where applicable

## 15.3 Error State

Errors should:

- explain what failed in user-facing language;
- offer recovery when possible;
- preserve user work where feasible.

## 15.4 Success Feedback

Use success feedback selectively.

Routine state changes do not always need celebratory UI.

---

# 16. Navigation

Navigation should be stable and predictable.

Rules:

- active state must be visually clear;
- icons should support labels, not replace them where comprehension matters;
- mobile navigation must preserve critical destinations;
- do not hide login merely because the viewport is mobile;
- role/workspace-based visibility is UX only, never security.

---

# 17. Iconography

## 17.1 Library

Use `lucide-react` as the Corporate Trust icon language where available in the repository.

Do not add another icon library for isolated features without justification.

## 17.2 Style

- default stroke width: approximately 2px
- inline: 16px
- standard control: 20px
- featured: 24px

## 17.3 Icon Containers

- small: 40–48px
- featured: 48–56px
- avatars/status: circular where semantically appropriate

## 17.4 Accessibility

Decorative icons:

- hide from screen readers when paired with visible text

Meaningful icon-only controls:

- require accessible name / label

---

# 18. Motion & Transitions

## 18.1 Philosophy

**Refined Motion**

Motion should:

- clarify interaction;
- reinforce hierarchy;
- feel calm and deliberate;
- never delay task completion.

## 18.2 Duration

- common interaction: `150–200ms`
- complex reveal/image transition: up to approximately `400–500ms`

## 18.3 Transition Properties

The original template uses `transition-all duration-200`. In production, prefer targeted transitions for predictability while matching the same 200ms perceived polish:

```text
transition-colors
transition-shadow
transition-transform
transition-opacity
```

Avoid `transition-all` as a general default.

Animate transform and opacity where practical.

## 18.4 Hover Motion

Allowed:

- buttons: subtle lift in appropriate contexts
- interactive cards: slight lift
- directional icons: small translate
- marketing images: controlled zoom

Avoid motion in dense workflows when it causes layout instability.

## 18.5 Reduced Motion

All non-essential animations **MUST** respect `prefers-reduced-motion`.

Decorative pulse/floating effects should be removed or minimized for reduced-motion users.

---

# 19. Atmospheric Backgrounds

Large blurred brand orbs may be used on marketing/public pages.

Guidelines:

- low opacity
- non-interactive
- behind content
- must not reduce text contrast
- avoid in dense application workspaces
- avoid unnecessary GPU-heavy animation

---

# 20. Responsive Strategy

## 20.1 Mobile First

Begin with approximately 375px-wide mobile layouts and progressively enhance.

Recommended breakpoints follow the established Tailwind configuration, typically:

- `sm`: 640px
- `md`: 768px
- `lg`: 1024px
- `xl`: 1280px

Do not duplicate breakpoint definitions if the project configuration differs.

## 20.2 Touch Targets

Interactive targets should be approximately **44×44px minimum** where practical.

## 20.3 Layout Adaptation

- multi-column layouts stack when necessary;
- dense tables prioritize or transform columns deliberately;
- navigation simplifies without hiding essential actions;
- forms remain single-column on narrow screens unless a paired layout is clearly usable.

## 20.4 Horizontal Overflow

Routine application content should not require viewport-level horizontal scrolling.

Component-level horizontal scrolling is acceptable only when genuinely necessary, such as wide data tables.

---

# 21. Accessibility

Accessibility is mandatory, not optional.

## 21.1 Contrast

Text and controls must meet WCAG AA contrast requirements.

Do not assume a palette token is compliant in every combination; verify actual foreground/background usage.

## 21.2 Focus

All interactive elements require visible `focus-visible` treatment.

Never remove outlines without an equally visible replacement.

## 21.3 Semantic HTML

Prefer native semantics:

- headings in logical order
- `<button>` for actions
- `<a>` for navigation
- `<nav>` for navigation regions
- `<main>` for primary page content
- `<footer>` for footer content
- native form controls where possible

Use ARIA only when native semantics are insufficient.

## 21.4 Screen Readers

- form controls need labels;
- icon-only buttons need names;
- live validation/status messages should be announced appropriately;
- decorative content should not pollute the accessibility tree.

## 21.5 Motion

Respect `prefers-reduced-motion`.

## 21.6 Keyboard

Critical flows must remain usable without a mouse.

---

# 22. Theme Policy

Current baseline:

**LIGHT MODE ONLY**

Rules:

- do not independently invent dark-mode values or dark-mode component variants;
- design tokens should remain semantic so a future dark theme can be introduced centrally;
- future dark mode requires an explicit design-system update.

---

# 23. Tailwind CSS 4 Implementation Rules

Quizopia uses Tailwind CSS 4.

Rules:

- centralize tokens through the project’s established theme/global token mechanism;
- prefer semantic reusable component variants over repeated long utility strings;
- keep feature-level CSS minimal and purposeful;
- do not introduce a second styling system without an explicit architecture decision;
- match existing repository conventions before adding abstractions.

Avoid scattering raw hex values throughout components when an established semantic token exists.

---

# 24. Component Library Policy

Do not introduce a new component library solely because an AI agent prefers it.

Before adding:

- shadcn/ui
- Radix
- Headless UI
- Material UI
- Chakra
- another UI kit

first verify whether the repository has already adopted a component strategy.

Any new UI dependency requires explicit justification based on:

- accessibility
- maintainability
- bundle impact
- consistency
- real product need

Do not duplicate a primitive that already exists in the project.

---

# 25. Page-Level Patterns

## 25.1 Authentication Pages

Authentication pages should:

- be calm and focused;
- keep one dominant primary action;
- avoid excessive decorative motion;
- preserve strong form hierarchy;
- show server/form errors clearly;
- remain usable on mobile and keyboard-only workflows.

Brand decoration may appear in:

- side panel
- background accent
- logo region

but must not interfere with the form.

## 25.2 Dashboard

Dashboard should prioritize:

- current tasks
- recent activity
- important metrics
- clear navigation

Do not fill dashboards with decorative cards that have no actionable value.

## 25.3 Classroom

Classroom screens should emphasize:

- class identity
- membership
- invitations
- teacher/student context
- status clarity

Dense roster content should favor stable tables/lists over decorative card grids.

## 25.4 Quiz Editor

The editor uses the full available application width instead of forcing the marketing `max-w-7xl` container. Preserve equal-weight source/preview panes and their synchronized behavioral contracts. Its header, Save/Publish toolbar, focus treatments, surface details, and typography **must still look like Corporate Trust**; product density is not an excuse for generic gray rectangular controls.

Editor UI prioritizes:

- authoring speed
- stable layout
- content readability
- deterministic controls
- minimal motion
- exact source preservation, including explicitly authored tabs
- balanced shared pane-header hierarchy
- intent-aware preview-to-source navigation without unsolicited completion
- LF/CRLF-safe source, textarea, line/column, and visual-caret mapping
- progressive full-marker completion and exact existing-marker replacement
- caret-anchored completion that remains inside the editor viewport

Decorative blobs and 3D transforms must not appear behind text editing or within answer controls. The prominent Publish CTA, selected pane/header brand accents, and surrounding chrome may use the canonical Indigo-to-Violet gradient.

## 25.5 Assessment

Assessment UI prioritizes:

- answer clarity
- timing/state clarity
- low distraction
- predictable navigation
- accessibility

Do not use decorative hover transforms or unnecessary animation on answer controls.

---

# 26. Visual DNA Summary

Quizopia Corporate Trust identity is expressed through:

1. Indigo primary brand
2. Violet secondary accent
3. geometric lightning-bolt mark and two-line Quizopia wordmark
4. Plus Jakarta Sans headings/UI/body, Calistoga brand wordmark only, and selective JetBrains Mono source/labels
5. clean cool-neutral surfaces
6. subtle colored shadows
7. signature Indigo-to-Violet gradient on primary actions and selected headline emphasis
8. generously rounded, premium button/card geometry
9. strong accessibility/focus treatment
10. calm refined motion
11. selective decorative depth on public/marketing surfaces only

The product should feel branded without sacrificing clarity.

---

# 27. Anti-Patterns

Do not:

- apply gradients indiscriminately to all controls, content, or data;
- use `text-6xl` application page headings;
- place blur blobs behind dense forms/tables/editors;
- apply 3D transforms to functional UI;
- make every card lift on hover;
- use placeholder as label;
- hide login just because the viewport is mobile;
- remove focus indicators;
- rely only on color for status/error;
- use `transition-all` as a blanket default;
- introduce dark mode ad hoc;
- add a UI library without justification;
- create one-off component styling when a reusable pattern exists;
- treat frontend role visibility as security;
- trade task clarity for visual spectacle.

---

# 28. Design Governance

This file is the source of truth for Quizopia visual and interaction design.

When implementation and this document differ:

1. verify whether the implementation represents an intentionally approved newer pattern;
2. otherwise prefer this document;
3. update this document when a reusable design decision is intentionally changed.

For AI-assisted development:

- AI agents must inspect existing components and global styles before creating new patterns;
- AI agents must not silently invent a competing design system;
- AI agents should favor existing reusable primitives;
- visual consistency and accessibility outweigh novelty.

---

# 29. Current Baseline Decisions

For the current Quizopia 2.0 frontend baseline:

- Design direction: Corporate Trust
- Theme: Light only
- Primary: Indigo 600
- Secondary: Violet 600
- Typography: Plus Jakarta Sans for headings and general UI; Calistoga only for the existing Quizopia brand wordmark; JetBrains Mono for source/code and compact technical labels
- Primary CTA/product page action: Indigo-to-Violet gradient, premium rounded silhouette, and colored shadow
- Secondary brand-adjacent CTA: white with Indigo text and brand-tinted border; neutral outline for low-emphasis controls
- Button sizes: compact where appropriate, with 44px touch targets where needed
- Product cards: restrained elevation
- Application shell: role-aware UX, not authorization
- Workspace personas: `LEARNING` / `TEACHING`
- Motion: refined and reduced-motion aware
- Styling: Tailwind CSS 4
- Brand icon: the reusable Quizopia lightning-bolt mark; do not reintroduce the legacy `Q` badge or unrelated blue favicon treatment
- General UI icons: use the existing project strategy; `lucide-react` only if already accepted
- New UI libraries: explicit justification required

---

# 30. Enterprise Template Fidelity & Product Adaptation Contract

Reference: https://www.designprompts.dev/enterprise — Corporate Trust design language (source reference as provided by the project owner).

## 30.1 Canonical visual signature

Corporate Trust is not merely an Indigo color scheme. Together, the following establish its recognizable identity across marketing **and** authenticated application screens:

1. Indigo 600 (`#4F46E5`) → Violet 600 (`#7C3AED`) gradient on the primary action in a given visual region.
2. Rounded-full/rounded-xl buttons with compact, deliberate padding rather than tall box-like controls.
3. White secondary actions with brand-colored text/border when related to a primary CTA; neutral outlines only for low-emphasis utility controls.
4. Plus Jakarta Sans hierarchy: heavyweight 800 display, 700 section headings, 600 card titles, readable 400–500 body/navigation.
5. Slate 900 text on Slate 50 backgrounds, white elevated cards, Violet/Indigo-tinted shadows.
6. Strategic split-color gradient headline emphasis on key headers, not blanket gradient body text.
7. Interactive cards with 200ms lift and stronger colored shadow; non-interactive reading surfaces remain stable.
8. Atmospheric soft orbs, subtle isometric illustrations, and decorative dimension in suitable marketing/onboarding/showcase regions, never on text entry, tables, assessment answers, or dialog controls.
9. Lucide-style icons, clear focus rings, reduced-motion alternatives, and verified WCAG AA contrast.
10. Shared variant-based implementation: gradient-primary, brand-outline, neutral-outline, ghost, destructive; compact/default/touch sizes; shared card/elevation tokens.

A user should recognize the Corporate Trust template from buttons, typography, depth, and accent behavior even when the page has no hero image.

## 30.2 Explicit Quizopia adaptations (must preserve)

- **Full-width application content:** Do not impose `max-w-7xl` on the quiz library, editor, data tables, or other task-oriented screens; use task-sensitive responsive gutters and actual available viewport width. Marketing pages may keep `max-w-7xl`.
- **Quiz Editor:** Desktop split Markdown source/live preview fills the available height/width; narrow viewports use the established accessible mode switch. Source text remains JetBrains Mono. Existing editor caret/scroll/autocomplete/exact-source behavior is out of scope for visual changes.
- **Brand:** Retain the canonical geometric lightning-bolt logo, two-line wordmark and `version 2.0` lockup. Calistoga is the approved wordmark exception, not the global heading font.
- **Learning/Teaching:** Workspace switch is presentation only, not a permission grant. Preserve backend/Gateway authority and authenticated-user menu logic.
- **Light mode:** Maintain current light-mode-only baseline unless separately approved.
- **Functional safety:** Preserve all current routes, actions, API contracts, validation, draft/publish behavior, accessibility, and keyboard semantics.

## 30.3 Visual acceptance matrix

| UI surface | Expected Corporate Trust treatment | Non-negotiable constraint |
| --- | --- | --- |
| Quiz library heading | Plus Jakarta Sans bold, optional strategic gradient emphasis | Keep real dynamic quiz data and full-width layout |
| Create quiz | Gradient primary, soft Indigo shadow, generously rounded | Do not change create flow |
| Quiz cards | White, 12px radius, tinted shadow, hover lift only if clickable | Keep metadata accurate and accessible |
| Quiz Editor header | Compact aligned shared chrome; refined brand outline secondary actions | Preserve return navigation, Save/Publish semantics |
| Publish | Canonical gradient primary, readable white label | Preserve publish validation and error states |
| Published versions | White brand-outline when paired with Publish | Keep version history behavior |
| Save | Explicit secondary/utility hierarchy unless it is the current page's single primary action | Preserve save/dirty/disabled/loading semantics |
| Forms/auth | Plus Jakarta Sans, Indigo focus, rounded inputs, gradient main CTA | Preserve accessible labels and auth/session behavior |
| Tables/assessment | Restrained surfaces, clear hierarchy, coherent brand accents | No isometric rotation or decorative distractions |

## 30.4 Implementation/verification rules

- Inspect existing components, Tailwind 4 tokens and current routing before introducing new variants; migrate shared components rather than patching each page with different ad hoc styles.
- Implement style through semantic tokens and shared button/card/heading variants; do not hard-code hex in feature components.
- Compare at 375px, 768px, 1280px, and wide desktop viewports. Validate no viewport-level horizontal overflow, functional keyboard controls, discernible focus, reduced-motion behavior, and touch targets.
- Verify contrast **at both ends and middle of gradients** and in hover/disabled states; do not assume white on Violet 600 always satisfies every text-size threshold.
- Use real app states in screenshots, including library, editor, auth, empty/error/loading, and any implemented Learning/Teaching screens. Prefer visual regression coverage for shared primitives and page chrome.
- Distinguish documented exceptions from accidental design drift. Any further deviation from this signature needs an explicit reason, documented here rather than silently normalized.

