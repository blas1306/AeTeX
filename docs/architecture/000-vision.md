# AeTeX Architecture 000: Project Vision

| Field | Value |
| --- | --- |
| Identifier | AeTeX Architecture 000 |
| Status | Accepted |
| Date | 2026-07-29 |
| Scope | Product vision, boundaries, and decision principles |

This document is the foundation for product and architecture decisions in AeTeX. It may evolve when new evidence appears, but changes to its direction or boundaries should be made explicitly and recorded in the [decision log](../decisions/decision-log.md).

## Summary

AeTeX is a local desktop IDE for LaTeX projects. It aims to provide a modern, integrated working environment without replacing or hiding the existing TeX ecosystem.

AeTeX treats ordinary project files and established tools as the source of truth. It is not a TeX compiler, a web platform, a generic editor, a replacement language for LaTeX, or a WYSIWYG document system.

The current implementation is an initial functional foundation: it opens a folder as a project, presents a static hierarchical file tree, edits multiple supported text documents, saves them safely, and protects unsaved changes. Compilation, a functional PDF preview, SyncTeX, and semantic features remain future work. See the [project model](001-project-model.md) for the implemented architecture and the [roadmap](../roadmap/roadmap.md) for the intended sequence.

## Problem

A productive local LaTeX workflow often spans an editor, a terminal, one or more TeX tools, a PDF viewer, and manual inspection of logs. Each component may be capable, but the seams between them create recurring friction:

- compilation commands and environment details must be configured manually;
- feedback is delayed when editing, compilation, and preview are separate;
- TeX diagnostics can be verbose and difficult to connect to their source;
- navigation across included files, labels, references, citations, bibliographies, and resources is cumbersome without project awareness;
- medium and large projects make it harder to identify the root document and understand dependencies;
- tool availability, file locking, process behavior, and installation conventions vary by operating system;
- existing tools differ widely in integration and usability, and some workflows retain dated or fragmented interactions.

AeTeX does not assume that existing editors, TeX distributions, command-line tools, or PDF viewers are inadequate. Many are mature and powerful. The opportunity is to integrate their strengths around the lifecycle of a local LaTeX project and make failures, state, and configuration easier to understand.

## Target users

### Primary users

- Students, instructors, and researchers producing LaTeX documents locally.
- Technical users who regularly work with LaTeX projects.
- People who prefer to retain control over their files, TeX distribution, commands, and build environment.

### Secondary users

- Authors of long or multi-file documents.
- Teams that version LaTeX projects with Git.
- Advanced users with custom engines, commands, directory layouts, or generated resources.
- People moving from basic text editors or manually coordinated editor-terminal-viewer workflows.

The initial product should remain approachable without reducing the visibility and control expected by experienced LaTeX users.

## Value proposition

AeTeX should reduce the distance between an edit and an understandable result. Its intended value is a complete local workflow in which:

- the project, rather than an isolated buffer, is the main unit of work;
- editing, controlled compilation, PDF preview, and diagnostics share context;
- source and output navigation are synchronized;
- references, citations, document structure, and resources are discoverable;
- the user can inspect and configure the underlying toolchain;
- the same conceptual workflow is available across supported desktop platforms;
- existing LaTeX files, TeX distributions, `latexmk`, engines, SyncTeX data, and Git workflows remain usable outside AeTeX.

Only the project and editing foundation is implemented today. Toolchain integration, compilation, PDF rendering, synchronization, diagnostics, and semantic navigation describe the intended product, not the current feature set.

## Design principles

### Local-first

Essential project, editing, build, preview, navigation, and diagnostic workflows should work without an Internet connection. Online services may become optional integrations, but they must not be a prerequisite for the core workflow.

### LaTeX remains LaTeX

AeTeX operates on established files, tools, and conventions. It must not require an incompatible intermediate language or proprietary project representation. A project edited with AeTeX should remain usable with other editors and command-line tools.

### The project is the primary unit

AeTeX should understand documents in the context of their root, included files, resources, bibliographies, configuration, and generated artifacts. Features that operate on a single file must still preserve project identity and boundaries.

### The user retains control

Users should be able to determine which tools and commands run, inspect their output, and override reasonable defaults. AeTeX must not suppress important failures or perform destructive actions silently.

### The interface must remain responsive

Filesystem scanning, compilation, indexing, PDF loading, and other expensive operations should run outside the main UI thread. Operations should expose progress, cancellation, and a meaningful result when their duration or impact warrants it. The current synchronous scanner and document I/O are baseline limitations, not a pattern to extend to heavier work.

### Compatibility before reinvention

Prefer integration with mature tools such as `latexmk`, TeX engines, bibliography processors, PDF libraries, and SyncTeX over reimplementing their core responsibilities. A new implementation is justified only when integration cannot provide a reliable user-facing result.

### Controlled degradation

Missing or incompatible optional tools must produce an explicit capability state, not an opaque failure. The application should continue to offer unaffected workflows and explain what is unavailable and how it can be restored.

### Data safety

AeTeX must never lose edits silently. Writes should minimize corruption risk; unsaved changes and external conflicts should be made explicit; destructive operations require clear intent. A failed save must leave the in-memory edit recoverable.

### Proportional architecture

Separate responsibilities that have different reasons to change, while avoiding layers and abstractions that do not yet solve a real problem. Architecture should be able to grow incrementally from the current scanner, document service, state, and UI boundaries.

### User value before internal sophistication

Technical novelty is not a product goal. Work should be prioritized when it measurably improves creating, organizing, editing, building, navigating, previewing, or debugging a LaTeX project, or when it is necessary to keep those workflows reliable.

## Intended scope

The following capabilities belong to the long-term AeTeX vision. Their inclusion here does not mean they are implemented or committed to a particular milestone:

- opening, organizing, and maintaining project workspaces;
- editing multiple related documents;
- discovering and integrating local LaTeX toolchains;
- controlled, observable, and cancellable compilation;
- safe PDF preview and reload;
- forward and inverse SyncTeX navigation;
- useful compilation and editing diagnostics;
- navigation across document structure, labels, references, citations, inclusions, bibliographies, and resources;
- contextual completion, templates, and snippets;
- persistent per-project configuration;
- reasonable Git integration that complements existing Git tools;
- responsive behavior for large projects;
- carefully designed future extensibility after stable extension points exist.

## Out of scope

AeTeX is not intended to become:

- a cloud platform or a mandatory hosted service;
- a replacement for Overleaf or another collaborative publishing service;
- a simultaneous real-time collaboration system during its initial evolution;
- a TeX compiler or a TeX distribution;
- an alternative language that replaces LaTeX;
- a complete WYSIWYG editor;
- an office suite or a generic-purpose IDE;
- an account-based application as a requirement for local use;
- a telemetry platform;
- a premature plugin ecosystem built before stable internal contracts exist;
- an AI tool whose essential behavior depends on external services.

Some of these areas could be explored later as optional integrations. They are not current objectives and must not compromise the local, interoperable core.

## Feature acceptance test

A feature belongs in the AeTeX core when it concretely reduces the friction of creating, organizing, editing, compiling, navigating, previewing, or debugging a local LaTeX project without compromising user control.

Before accepting a feature, answer:

- Does it solve a recurring problem in a real LaTeX workflow?
- Is it necessary in the core now, or can it wait for a later milestone or optional integration?
- Does it duplicate a mature tool that AeTeX could integrate instead?
- How does it affect portability and supported operating systems?
- Does it introduce maintenance cost disproportionate to its user value?
- Can it be delivered and validated incrementally?
- What state does the user see when it is unavailable, cancelled, or fails?
- Does it preserve ordinary files and an inspectable toolchain?

## Project success

AeTeX fulfills this vision when:

- a user can work on a real local LaTeX project without constantly coordinating separate visible tools for routine operations;
- errors guide the user from a failed operation to a useful cause, relevant source, and complete log;
- the edit-compile-preview loop is responsive and predictable;
- supported operating systems provide a reliable, conceptually consistent workflow;
- advanced users retain access to commands, configuration, logs, and generated artifacts;
- large, multi-file documents remain navigable and responsive;
- essential functionality remains available without an Internet connection;
- edits and project files are preserved safely across ordinary failures and user actions.

These are product and technical outcomes, not commercial metrics. Individual milestones should translate them into concrete acceptance criteria before implementation.
