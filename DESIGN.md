# Quizopia 2.0 — DESIGN.md

> **Status:** UI/UX source of truth for Quizopia 2.0  
> **Design direction:** Corporate Trust  
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
  Clean structure and strong readability establish credibility, while restrained brand gradients and accents keep the UI energetic.

- **Refined Elegance**  
  Surfaces, typography, spacing, interactions, and transitions should feel polished without becoming decorative noise.

- **Dimensional Depth**  
  Soft colored shadows and selective elevation add depth. Decorative 3D/isometric treatments are allowed only where they do not reduce usability.

- **Purposeful Gradients**  
  Indigo-to-violet gradients are a brand signature, but they are accents rather than the default treatment for every control or surface.

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

These pages may use the full Corporate Trust visual language:
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
- use gradients sparingly;
- avoid decorative blobs behind dense content;
- do not use 3D transforms on functional controls;
- do not apply hover lift to every card;
- prefer visually stable layouts;
- use brand identity mainly through typography, primary color, active states, focus rings, icons, and selective accents.

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

## 5.1 Font Family

Primary font:

**Plus Jakarta Sans**

Use a robust system sans-serif fallback stack.

## 5.2 Weights

- Display/Hero: ExtraBold `800`
- Section Headings: Bold `700`
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

---

# 6. Radius & Borders

## 6.1 Radius

- Cards / panels: `12px` (`rounded-xl`)
- Inputs / selects / product buttons: `8px` (`rounded-lg`)
- Compact controls: `6–8px` where appropriate
- Avatars/status dots: circular
- Marketing CTA buttons may use `rounded-full`

### Default Rule

**Product UI buttons use `rounded-lg` by default.**

`rounded-full` is a deliberate exception, mainly for:
- marketing hero CTAs
- pill filters/tags
- circular icon/avatar controls

## 6.2 Borders

- Default border: 1px `Border`
- Strong separation: 1px `Border Strong`
- Avoid heavy outlines unless semantic state requires it

---

# 7. Shadows, Depth & Effects

## 7.1 Default Shadows

- **Card:** `0 4px 20px -2px rgba(79, 70, 229, 0.10)`
- **Card Hover:** `0 10px 25px -5px rgba(79, 70, 229, 0.15), 0 8px 10px -6px rgba(79, 70, 229, 0.10)`
- **Primary Button:** `0 4px 14px 0 rgba(79, 70, 229, 0.25)`

Use elevation selectively in application UI.

Flat or lightly bordered surfaces are preferred for:
- tables
- editors
- nested panels
- dense dashboards

## 7.2 Gradients

### Primary Gradient

Indigo 600 → Violet 600.

### Allowed Uses

- selected marketing CTAs
- selected high-emphasis brand moments
- marketing illustrations
- controlled active/brand accents
- occasional hero text emphasis

### Gradient Restraint

Gradients are brand accents, not default surface treatments.

For product screens:
- normally use at most one dominant gradient treatment per visual region;
- do not use gradient text for ordinary page headings, labels, table content, form text, or metadata;
- prefer solid Primary for routine application actions;
- do not stack gradient text + gradient buttons + gradient borders + gradient backgrounds in the same functional area.

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

---

# 10. Buttons

## 10.1 Primary Product Button

Default product button:
- solid Primary background
- white text
- rounded-lg
- subtle brand shadow
- clear focus-visible ring

Hover:
- Primary Hover
- optional subtle `-translate-y-0.5` only for non-dense contexts

Active:
- remove or reduce lift
- visually acknowledge press

## 10.2 Marketing Primary CTA

May use:
- Indigo → Violet gradient
- stronger shadow
- rounded-full or rounded-lg

## 10.3 Secondary Button

- white/surface background
- Border
- Text Primary or Text Secondary
- hover to Surface Muted / stronger border

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

- Surface
- rounded-xl
- subtle border
- subtle or no shadow depending on density

## 12.2 Hover

Only cards that are actually interactive should receive hover affordances.

Do not make every informational panel lift on hover.

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

Use `lucide-react` if already accepted by the repository.

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

Prefer explicit transitions:

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

Editor UI prioritizes:
- authoring speed
- stable layout
- content readability
- deterministic controls
- minimal motion

Decorative gradients/3D treatments should not appear inside the editing workspace.

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
3. Plus Jakarta Sans
4. clean cool-neutral surfaces
5. subtle colored shadows
6. restrained gradients
7. rounded but professional geometry
8. strong accessibility/focus treatment
9. calm refined motion
10. selective decorative depth on public/marketing surfaces only

The product should feel branded without sacrificing clarity.

---

# 27. Anti-Patterns

Do not:

- use gradients everywhere;
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
- Typography: Plus Jakarta Sans
- Product button default: solid Indigo, rounded-lg
- Marketing CTA: gradient allowed
- Product cards: restrained elevation
- Application shell: role-aware UX, not authorization
- Workspace personas: `LEARNING` / `TEACHING`
- Motion: refined and reduced-motion aware
- Styling: Tailwind CSS 4
- Icons: use existing project strategy; `lucide-react` only if already accepted
- New UI libraries: explicit justification required
