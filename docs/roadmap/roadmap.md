# AeTeX Roadmap

This roadmap orders product capabilities by dependency and user value. It does not assign calendar dates or implementation durations. Product boundaries come from [AeTeX Architecture 000](../architecture/000-vision.md), and the current baseline is described in [AeTeX Architecture 001](../architecture/001-project-model.md).

The milestones are maturity stages, not the version currently declared in `build.gradle.kts`. That file reports `1.0.0`, but the repository implements Milestone 0 and does not yet meet the v1.0 readiness criteria below. Versioning and package metadata should be aligned before a public release process is established.

## Milestone 0 — Project foundation

**Status:** Complete

**Outcome:** AeTeX is a usable local multi-document text-editing foundation built around an actual project directory.

The Git history separates an early UI prototype from the functional baseline:

- commits through `3d078bf` established the Gradle and Compose Desktop application, a three-panel layout, a folder chooser, and placeholder editor and preview panels;
- commit `62d7f68` replaced the folder-only prototype with the hierarchical project scanner, real document access, observable application state, editing, safe save behavior, close safeguards, and non-visual tests.

The implemented baseline includes:

- a Kotlin/JVM 21 application built with Gradle and Compose Desktop;
- a desktop window with project, editor, and placeholder preview areas;
- opening a directory as a project;
- a sorted hierarchical snapshot of files and directories;
- multiple open documents and an active tab;
- editing of `tex`, `bib`, `sty`, `cls`, and `txt` files;
- UTF-8 reads and temporary-file replacement saves;
- modified-state detection;
- save/discard/cancel confirmation when closing a modified tab, replacing the project, or exiting the application;
- explicit scanner and document error results;
- tests for scanning, document access, and non-visual application-state behavior.

**Exit evidence:** The behavior above exists in the repository and its logic tests pass. PDF preview is not part of this milestone; the visible preview panel is only a label.

## Milestone 1 — Project configuration

**Objective:** Persist the minimum project intent needed to identify a build target and prepare compilation without relying on hidden heuristics.

Required capabilities:

- a project-owned, versioned configuration file;
- explicit main-document path;
- a clear state when no configuration exists;
- assisted detection of likely `main.tex` or other root candidates;
- manual root-document selection;
- preferred engine and compilation strategy;
- output-directory policy;
- defaults that keep simple projects usable with minimal setup;
- validation and user-facing errors for stale or invalid paths.

The normative [project configuration architecture](../architecture/002-project-configuration-system.md) defines the file location, TOML schema, defaults, discovery rules, and compatibility behavior that implementation must follow.

**Exit criteria:** A project can be reopened with a validated root document and build-relevant settings; an unconfigured or invalid project has an explicit recoverable state; tests cover configuration loading, defaults, validation, and schema compatibility.

## Milestone 2 — Compilation

**Objective:** Compile the configured project through observable local tools without blocking the UI.

Candidate capabilities:

- detection and reporting of available TeX tools;
- asynchronous build execution;
- cancellation and process cleanup;
- captured standard output, standard error, command, exit status, and duration;
- a stable build-result model;
- complete logs plus initial extraction and navigation of source diagnostics;
- `latexmk` as the first integrated strategy;
- a controlled fallback or user-configurable command where appropriate;
- clear behavior for missing executables, invalid configuration, timeouts, and failed cancellation.

**Exit criteria:** A configured project can be built, cancelled, and rebuilt while the editor remains responsive; success and failure are represented explicitly; users can inspect the executed command and full log; automated tests cover process results and failure modes.

## Milestone 3 — PDF preview

**Objective:** Display the generated PDF as part of the project workflow and update it safely after compilation.

Candidate capabilities:

- loading the configured build output;
- safe reload when the PDF is replaced or temporarily unavailable;
- preservation of page, zoom, and useful viewport state;
- visible not-built, building, current, stale, and failed states;
- handling for locked, partially written, removed, or regenerated files;
- PDF resource cleanup that does not prevent later builds.

**Exit criteria:** A successfully built PDF can be viewed and repeatedly regenerated without manual reopening; preview state remains understandable during builds and failures; reload does not destabilize the editor or lock the output unexpectedly.

## Milestone 4 — SyncTeX

**Objective:** Connect source locations and PDF positions in both directions.

Candidate capabilities:

- forward search from editor to PDF;
- inverse search from PDF to editor;
- mapping of absolute and relative source paths;
- navigation across included files;
- explicit association with the configuration and compilation result that produced the SyncTeX data;
- useful failure states for missing, stale, or incompatible synchronization data.

**Exit criteria:** Forward and inverse navigation work for a tested multi-file project after compilation, reject stale or unrelated data predictably, and explain when SyncTeX is unavailable.

## Milestone 5 — Structural LaTeX analysis

**Objective:** Build a tolerant, useful view of project structure and cross-file relationships without attempting to be a perfect TeX parser.

Candidate capabilities:

- sections and document outline;
- labels and references;
- citation keys and uses;
- file inclusions;
- bibliography declarations and entries;
- linked resources;
- basic navigation and unresolved-item reporting;
- partial results in the presence of incomplete or invalid input.

TeX is programmable and context-sensitive. This milestone should optimize for robust editor assistance on common project structures, preserve uncertainty, and degrade gracefully rather than claim complete interpretation.

**Exit criteria:** Common multi-file projects produce a navigable structure and cross-reference index; incomplete input yields bounded partial results; fixtures define supported syntax and known limits.

## Milestone 6 — Intelligent editing

**Objective:** Use project and structural context to make source editing faster and less error-prone.

Candidate capabilities:

- LaTeX-aware syntax highlighting;
- snippets and templates for recurring source constructs;
- contextual command and environment completion;
- completion and navigation for labels, references, citations, files, and resources;
- useful diagnostics during editing;
- structural search across the project.

**Exit criteria:** Assistance is responsive, derives from the active project, remains usable while analysis is incomplete, and does not rewrite source unexpectedly. Each assistance type has focused accuracy and latency criteria before implementation.

## Milestone 7 — Large-project experience

**Objective:** Keep project navigation, editing, and recovery reliable as the number and size of files grow.

Candidate capabilities:

- filesystem watching and incremental tree refresh;
- global text and file search;
- incremental and cancellable indexing;
- configured handling of generated files and build directories;
- measured performance budgets for scan, search, and analysis;
- persistent sessions and open-document restoration;
- crash recovery for unsaved content;
- explicit external-change and conflict handling.

**Exit criteria:** Representative large-project fixtures remain responsive under editing and filesystem churn; indexing can be cancelled and resumed or rebuilt; session and recovery behavior is tested across clean and abnormal shutdown.

## Milestone 8 — v1.0 readiness

**Objective:** Turn the accumulated capabilities into a stable, supportable desktop release.

Candidate capabilities:

- a defined support matrix for operating systems, JDK/runtime, TeX distributions, and PDF behavior;
- native packaging and installation for supported platforms;
- stable project configuration and migrations;
- user documentation for setup, project configuration, builds, preview, diagnostics, and recovery;
- basic keyboard and screen-reader accessibility;
- TeX-installation diagnostics and actionable remediation;
- end-to-end tests for critical workflows;
- release/versioning policy and upgrade verification;
- reliability work based on known failure modes rather than feature count.

**Exit criteria:** Supported platforms pass documented end-to-end scenarios from installation through editing, build, preview, restart, and recovery; configuration migrations and failure behavior are tested; package metadata accurately represents release maturity.

## Post-v1.0 exploration

The following are non-committed directions. Each requires evidence of user value and stable extension points:

- deeper Git status, diff, and history integration;
- project and document templates;
- custom project tasks;
- carefully scoped extensibility;
- multiple root documents or build targets;
- focused tools for tables, figures, bibliographies, and resources;
- import or adaptation of external editor and build configurations.

Exploration does not imply inclusion in the core or compatibility promises.

## Roadmap rules

- Every milestone must leave a usable version, even if later milestones add depth.
- Progress is measured by reliable user workflows and acceptance evidence, not feature count alone.
- Concrete acceptance criteria and failure behavior must be defined before implementation begins.
- A capability may move between milestones when implementation reveals a real dependency.
- A milestone should not introduce several immature subsystems at once.
- Architecture documents govern decisions but may be revised through an explicit, recorded decision.
- Tool availability and partial failure must be part of each feature's design, not deferred to final polish.
- Work that compromises data safety or UI responsiveness does not satisfy a milestone merely because its happy path works.
