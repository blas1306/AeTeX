# AeTeX Decision Log

This log records concise, durable decisions evidenced by the repository and its Git history. It is retrospective: unless an entry says otherwise, “alternatives considered” identifies the relevant architectural alternative, not a claim that a formal comparison document existed at the time.

Dates are the first supporting commit date in the available history. `Accepted` means the decision remains the intended direction; `Accepted (implemented)` means the current code also enforces it.

## ADR-0001 — Use Kotlin and Compose Desktop

- **Date:** 2026-05-21
- **Status:** Accepted (implemented)

### Context

The first repository commit establishes a Gradle Kotlin/JVM application with the JetBrains Compose plugin and a Compose Desktop entry point. The current build uses Kotlin 2.3.21, Compose 1.10.0, and a JVM 21 toolchain.

### Decision

Implement AeTeX as a Kotlin desktop application with Compose Desktop as its UI toolkit and Gradle as its build system.

### Reasons

This is the platform foundation consistently present throughout the available history. It supports a native desktop process, JVM filesystem and process APIs, and a declarative UI state model in one codebase.

### Alternatives considered

No evaluation of other languages or UI toolkits is preserved in the repository. Reconsidering this decision would therefore require a separate proposal with migration costs and concrete product benefits.

### Consequences

Application code can use `java.nio.file` and other JVM APIs directly. Compose runtime types are currently part of the application-state implementation. Native distribution support and behavior still need to be defined and verified per target platform; the current build configures only a Debian package format.

### References

- [`build.gradle.kts`](../../build.gradle.kts)
- [`Main.kt`](../../src/main/kotlin/dev/aetex/Main.kt)
- [`AeTeXApp.kt`](../../src/main/kotlin/dev/aetex/app/AeTeXApp.kt)
- Git commit `2ac57af`

## ADR-0002 — Use `Path` as the central path representation

- **Date:** 2026-07-29
- **Status:** Accepted (implemented)

### Context

The project-opening prototype initially exposed `java.io.File`. The functional project and document milestone replaced it with `java.nio.file.Path` across models, services, state, and UI callbacks.

### Decision

Use `Path` as the internal representation of filesystem locations. Convert to or from legacy APIs only at integration boundaries, such as `JFileChooser`.

### Reasons

`Path` integrates with `Files`, supports explicit normalization and real-path resolution, exposes link options, and makes containment checks and platform-specific filesystem behavior visible.

### Alternatives considered

`java.io.File` was present in the earlier prototype and was replaced. String paths would lose filesystem semantics and require repeated parsing.

### Consequences

Path identity, normalization, relative resolution, and real-path resolution must be handled deliberately. Code must not assume that lexical equality, normalized equality, and filesystem identity are interchangeable.

### References

- [`TeXProject.kt`](../../src/main/kotlin/dev/aetex/project/TeXProject.kt)
- [`ProjectLoader.kt`](../../src/main/kotlin/dev/aetex/project/ProjectLoader.kt)
- [`DocumentService.kt`](../../src/main/kotlin/dev/aetex/editor/DocumentService.kt)
- Git commit `62d7f68`

## ADR-0003 — Treat a project directory as the unit of work

- **Date:** 2026-07-29
- **Status:** Accepted (implemented)

### Context

LaTeX work commonly spans several source files and resources. The current application requires a project to be open before it opens a document and binds document access to the active project root.

### Decision

Make `TeXProject`, rooted at a selected directory, the working context for open documents and future configuration, compilation, navigation, and generated artifacts.

### Reasons

The project boundary supplies context and a filesystem confinement boundary. It also creates a stable place for later root-document, toolchain, output, and dependency decisions.

### Alternatives considered

Opening unrelated files without a project would simplify a basic editor but would not provide the relationships or boundary needed by the intended LaTeX workflow. The repository contains no standalone-file mode.

### Consequences

Documents outside the active root are rejected. Replacing the project closes its document set after unsaved-change handling. Future subsystems should be scoped to an explicit project rather than global mutable state.

### References

- [AeTeX Architecture 000](../architecture/000-vision.md)
- [AeTeX Architecture 001](../architecture/001-project-model.md)
- [`AeTeXState.kt`](../../src/main/kotlin/dev/aetex/app/AeTeXState.kt)
- [`DocumentServiceTest.kt`](../../src/test/kotlin/dev/aetex/editor/DocumentServiceTest.kt)

## ADR-0004 — Model the project tree hierarchically

- **Date:** 2026-07-29
- **Status:** Accepted (implemented)

### Context

The folder-opening prototype stored only a root. The functional milestone introduced `ProjectEntry`, `ProjectDirectory`, and `ProjectFile`, with directory children represented recursively.

### Decision

Represent the scanned project as a hierarchy whose shape follows the filesystem. Keep directories before files and apply deterministic name sorting within each level.

### Reasons

The hierarchy preserves navigation context and directory ownership without reconstructing it in the UI. It is a natural base for later refresh and generated-directory policies.

### Alternatives considered

A flat list of paths is the direct structural alternative, but no flat implementation appears in the history. A flat view may still be derived for search; it is not the central project model.

### Consequences

Scanning eagerly materializes the full visible tree and the UI flattens expanded branches for rendering. Large-project scalability and incremental updates are not solved by the current snapshot model.

### References

- [`TeXProject.kt`](../../src/main/kotlin/dev/aetex/project/TeXProject.kt)
- [`ProjectScanner.kt`](../../src/main/kotlin/dev/aetex/project/ProjectScanner.kt)
- [`ProjectPanel.kt`](../../src/main/kotlin/dev/aetex/ui/panels/ProjectPanel.kt)
- [`ProjectScannerTest.kt`](../../src/test/kotlin/dev/aetex/project/ProjectScannerTest.kt)

## ADR-0005 — Separate scanning, document access, state coordination, and UI

- **Date:** 2026-07-29
- **Status:** Accepted (implemented)

### Context

Project traversal, document I/O, application workflows, and rendering fail for different reasons and require different tests. The functional milestone introduced independent types for each responsibility.

### Decision

Assign filesystem-tree construction to `ProjectScanner`, validated document reads and writes to `DocumentService`, open-document semantics to `OpenDocument`, workflow and observable state coordination to `AeTeXState`, and user interaction/rendering to Compose UI code.

### Reasons

The boundaries keep filesystem behavior testable without rendering UI and prevent composables from owning persistence rules. Constructor injection in `AeTeXState` provides a seam for state tests.

### Alternatives considered

Embedding traversal and file writes directly in composables or concentrating them all in `AeTeXState` would reduce the number of types but mix unrelated lifecycles and errors. No formal architectural pattern beyond the implemented responsibility split is asserted.

### Consequences

Cross-cutting workflows are coordinated by state while mechanisms remain independently testable. Future compilation, watching, analysis, and preview services should follow the same proportional separation, without adding layers before their responsibilities exist.

### References

- [`ProjectScanner.kt`](../../src/main/kotlin/dev/aetex/project/ProjectScanner.kt)
- [`DocumentService.kt`](../../src/main/kotlin/dev/aetex/editor/DocumentService.kt)
- [`OpenDocument.kt`](../../src/main/kotlin/dev/aetex/editor/OpenDocument.kt)
- [`AeTeXState.kt`](../../src/main/kotlin/dev/aetex/app/AeTeXState.kt)
- [`AeTeXStateTest.kt`](../../src/test/kotlin/dev/aetex/app/AeTeXStateTest.kt)

## ADR-0006 — Save through a sibling temporary file and replacement

- **Date:** 2026-07-29
- **Status:** Accepted (implemented)

### Context

Overwriting the target while writing can leave it truncated or partially updated if the operation fails. Document saves must also preserve the modified in-memory state when persistence fails.

### Decision

Write complete UTF-8 content to a temporary file in the target directory, copy POSIX permissions when supported, and replace the target with an atomic move when available. Fall back to a replacing move when the filesystem does not support atomic movement.

### Reasons

A sibling temporary file makes same-filesystem replacement likely and minimizes the period in which the target can be incomplete. Atomic movement provides the strongest available replacement behavior without making it a portability requirement.

### Alternatives considered

Direct in-place overwrite was not selected. Requiring atomic moves with no fallback would make saving fail on filesystems that otherwise support safe-enough replacement. No broader durability protocol, backup history, or `fsync` policy has been adopted.

### Consequences

The fallback is not guaranteed to be atomic. Temporary cleanup is best-effort. A successful result marks the document clean; a failure retains the edited content and saved baseline. External modification conflicts remain undetected.

### References

- [`DocumentService.kt`](../../src/main/kotlin/dev/aetex/editor/DocumentService.kt)
- [`OpenDocument.kt`](../../src/main/kotlin/dev/aetex/editor/OpenDocument.kt)
- [`DocumentServiceTest.kt`](../../src/test/kotlin/dev/aetex/editor/DocumentServiceTest.kt)

## ADR-0007 — Do not recursively follow symbolic links

- **Date:** 2026-07-29
- **Status:** Accepted (implemented)

### Context

Symbolic links can introduce cycles, duplicate subtrees, unexpected project expansion, and paths outside the selected root.

### Decision

Inspect entries without following their final symbolic link, retain symbolic links as marked tree entries, and do not recurse into linked directories. Reject final-component symbolic links as editable documents and validate real-path confinement for accepted files.

### Reasons

This bounds traversal, reduces cycle and escape risk, and makes link presence visible rather than silently treating an external tree as project content.

### Alternatives considered

Following links recursively would expose linked content but would require explicit policies for external roots, identity, cycles, writes, and user trust. Omitting link entries entirely would hide useful filesystem information.

### Consequences

Linked directories are visible but have no children, and linked files cannot be edited through the current service. Projects that intentionally organize sources through symlinks need a future explicit policy rather than implicit traversal.

### References

- [`ProjectScanner.kt`](../../src/main/kotlin/dev/aetex/project/ProjectScanner.kt)
- [`DocumentService.kt`](../../src/main/kotlin/dev/aetex/editor/DocumentService.kt)
- [`ProjectScannerTest.kt`](../../src/test/kotlin/dev/aetex/project/ProjectScannerTest.kt)

## ADR-0008 — Centralize the editable extension allowlist

- **Date:** 2026-07-29
- **Status:** Accepted (implemented)

### Context

The project tree contains arbitrary resources, but the initial editor should only open known text-oriented project files. The same policy must not be repeated across UI components.

### Decision

Keep the case-insensitive editable extension set in `EditableFileTypes`. The current set is `tex`, `bib`, `sty`, `cls`, and `txt`. Enforce it in `AeTeXState` before document access.

### Reasons

A single policy prevents inconsistent UI checks and gives future configuration or file-type work one explicit boundary to revisit.

### Alternatives considered

Opening every scanned file as UTF-8 could corrupt the user experience for binary resources. Distributing extension checks across tree and editor components would make behavior inconsistent.

### Consequences

Non-editable files remain visible in the tree and produce an explanatory error when selected. File content is not sniffed, extensionless text is not supported, and the allowlist is not currently configurable.

### References

- [`OpenDocument.kt`](../../src/main/kotlin/dev/aetex/editor/OpenDocument.kt)
- [`AeTeXState.kt`](../../src/main/kotlin/dev/aetex/app/AeTeXState.kt)
- [`DocumentServiceTest.kt`](../../src/test/kotlin/dev/aetex/editor/DocumentServiceTest.kt)

## ADR-0009 — Require explicit intent before discarding edits

- **Date:** 2026-07-29
- **Status:** Accepted (implemented)

### Context

Closing a tab, opening another project, and exiting the application can each remove the only in-memory copy of unsaved content.

### Decision

Block ordinary state operations that would discard a modified document. At the UI boundary, request save, discard, or cancel for tab close, project replacement, and application exit. Stop a save-all close flow on the first failed save.

### Reasons

The policy makes data loss an explicit user choice and keeps a failed save recoverable in memory.

### Alternatives considered

Silent discard violates the data-safety principle. Implicit auto-save would change files without an explicit command and could overwrite external changes. Both were rejected by the implemented interaction.

### Consequences

The UI owns pending confirmation state and calls dedicated discard operations only after confirmation. Non-UI callers must respect the same contract; the discard methods themselves intentionally do not prompt.

### References

- [`AeTeXApp.kt`](../../src/main/kotlin/dev/aetex/app/AeTeXApp.kt)
- [`MainWindow.kt`](../../src/main/kotlin/dev/aetex/ui/MainWindow.kt)
- [`AeTeXState.kt`](../../src/main/kotlin/dev/aetex/app/AeTeXState.kt)
- [`AeTeXStateTest.kt`](../../src/test/kotlin/dev/aetex/app/AeTeXStateTest.kt)

## ADR-0010 — Establish real project and document behavior before PDF preview

- **Date:** 2026-07-29
- **Status:** Accepted

### Context

A three-panel prototype, including a `PreviewPanel` that displays only “PDF Preview,” predates the functional milestone. Commit `62d7f68` adds the real project tree, editing, save behavior, and tests while leaving that panel unchanged.

### Decision

Treat project identity, filesystem safety, multi-document editing, and unsaved-change behavior as the first functional milestone. Defer compilation-backed PDF preview until project configuration and compilation results can define what should be displayed.

### Reasons

The available history establishes the project/document model as the prerequisite foundation. A functional preview needs a root document, build strategy, output identity, reload lifecycle, and failure states that the prototype does not yet possess.

### Alternatives considered

Implementing preview against the folder-only prototype was not selected. No separate historical design comparison is preserved.

### Consequences

The current preview area must not be documented as implemented PDF support. The roadmap places configuration and compilation before preview, and a future preview RFC must address resource lifecycle and regenerated or locked files.

### References

- [`PreviewPanel.kt`](../../src/main/kotlin/dev/aetex/ui/panels/PreviewPanel.kt)
- [Roadmap](../roadmap/roadmap.md)
- Git commits `3d078bf` and `62d7f68`

## ADR-0011 — Adopt a shared, versioned project configuration

- **Date:** 2026-07-29
- **Status:** Accepted (implemented)

### Context

Compilation, PDF preview, and SyncTeX need persistent project intent that the current directory snapshot and nullable `mainDocument` do not provide. Architecture Study 002 established the direction for a manually editable, Git-friendly shared configuration and identified the remaining contract decisions.

### Decision

Use `.aetex/project.toml` as the single shared project-configuration file. Use TOML with a required integer `schema`, project-relative portable paths, one confirmed active main document, and optional `engine`, `strategy`, and `output` project defaults. Schema 1 supports `pdflatex`, `xelatex`, and `lualatex`, supports `latexmk` as its compilation strategy, defaults output to `build`, and keeps local configuration outside the current system.

Projects without configuration and projects with invalid or unsupported configuration remain open for editing. Configuration-dependent workflows require a confirmed, valid effective configuration. Main-document detection and defaults must not rewrite the shared file without explicit user intent.

### Reasons

The contract preserves ordinary LaTeX folders while giving future build, preview, and synchronization systems stable and versionable input. Relative paths and shared-only values keep the file portable across collaborators. Explicit schema compatibility permits controlled evolution independently of AeTeX release versions.

### Consequences

Configuration loading, validation, and user-visible recovery states now precede
future compilation work. Unknown fields in supported schemas are warning-level
when they do not prevent interpretation; unsupported schemas and corrupt known
fields disable dependent workflows without rejecting the project directory.
Local overrides, multiple targets, hooks, scripts, and other execution-bearing
extensions require later decisions.

The schema 1 reader uses TomlJ `1.1.1`, a focused TOML 1.0 parser with typed
values and source-positioned syntax errors. The implementation resolves
effective defaults and main-document state without modifying project files.
Interactive main confirmation and configuration writing remain future UI work.

### References

- [AeTeX Architecture 002](../architecture/002-project-configuration-system.md)
- [Architecture Study 002](../architecture/002-project-configuration-study.md)
- [Roadmap](../roadmap/roadmap.md)
- [`TeXProject.kt`](../../src/main/kotlin/dev/aetex/project/TeXProject.kt)
- [`ProjectLoader.kt`](../../src/main/kotlin/dev/aetex/project/ProjectLoader.kt)
- [`ProjectConfigurationLoader.kt`](../../src/main/kotlin/dev/aetex/project/configuration/ProjectConfigurationLoader.kt)

## ADR-0012 — Adopt a deterministic, shell-free compilation system

- **Date:** 2026-07-29
- **Status:** Accepted

### Context

The implemented project-configuration system now provides the confirmed main
document, engine, strategy, and output required for compilation. Compilation
adds external process execution, cancellation, concurrent stream capture,
generated-file ownership, diagnostics, and future Preview and SyncTeX
association. Architecture Study 003 established the accepted direction for
those responsibilities.

### Decision

Compilation consumes only a ready `EffectiveProjectConfiguration` and converts
it into an immutable `BuildPlan`. A `CompilationManager` owns sessions,
cancellation, latest-request replacement, and one active compilation per output
space. Each `BuildSession` executes its plan through `ProcessBuilder` without a
shell and produces one typed `BuildResult`.

Keep `TeXEngine` and `CompilationStrategy` separate. Schema 1 uses `latexmk` as
its only strategy and never falls back to direct engine execution. Preserve
complete logs, derive typed diagnostics without replacing raw evidence,
revalidate shared output before execution, and never clean it recursively as
part of an ordinary build.

### Reasons

Immutable plans make in-flight work attributable and deterministic. Output
serialization prevents concurrent corruption while latest-request replacement
avoids stale queues. Shell-free structured execution reduces quoting and
injection risk, and complete evidence makes process, tool, and parsing failures
inspectable across supported platforms.

### Consequences

Compilation implementation must introduce explicit plan, session, process,
result, state, discovery, logging, and diagnostic boundaries. Missing tools,
unsupported typed values, process failures, cancellation, incomplete cleanup,
and invalid artifacts remain recoverable results rather than hidden fallback.
Preview and SyncTeX must consume session results instead of guessing output
identity.

Custom commands, direct-engine strategies, automatic builds, parallel targets,
cleaning, caching, and remote compilation require later architecture work.

### References

- [AeTeX Architecture 003](../architecture/003-compilation-system.md)
- [Architecture Study 003](../architecture/003-compilation-system-study.md)
- [AeTeX Architecture 002](../architecture/002-project-configuration-system.md)
- [Roadmap](../roadmap/roadmap.md)

## ADR-0013 — Use PDFBox behind a generation-based preview system

- **Date:** 2026-07-30
- **Status:** Accepted

### Context

Architecture 003 now gives Preview one authoritative input: a successful
`BuildResult` with an exact validated `PRIMARY_PDF`. Architecture Study 004
identified reload, file lifetime, rendering, caching, concurrency, Compose, and
future-extension boundaries. The standalone rendering benchmark then compared
PDFBox and PDFium through the same audited adapter contract and synthetic
corpus.

### Decision

Use Apache PDFBox 3.x as the initial production renderer behind an
engine-neutral `DocumentRenderer`. Every accepted successful compilation
creates an immutable preview-owned document generation from the result's exact
`PRIMARY_PDF`. Render visible pages lazily through a bounded, cancellable,
duplicate-coalescing scheduler; cache pages by generation, page, and effective
scale under a memory-weighted priority-aware LRU policy; and give Compose only
already-rendered engine-neutral images.

Compilation results are the sole preview update authority. Preview does not
open arbitrary PDFs or watch the filesystem for replacement. A generation uses
a private immutable source snapshot so renderer lifetime cannot lock or observe
later changes to the compilation output. PDFium remains the primary benchmark
comparator and replacement candidate, not a production dependency.

### Reasons

The audited Linux run showed usable adapter-level page latency and expected
robustness behavior for PDFBox, while PDFium was faster in several important
text, page-count, and scale cases and PDFBox was faster for the image-heavy
fixture. The evidence therefore supports PDFBox sufficiency, not universal
performance superiority.

PDFBox integrates directly with the existing JVM application, uses the Apache
License 2.0, and avoids a production native binding, per-platform binaries,
ABI/loading issues, signing, and a separate native update supply chain. The
engine-neutral boundary preserves the ability to adopt PDFium if broader
cross-platform evidence later justifies those costs.

### Consequences

Preview implementation must add explicit generation, snapshot, scheduler,
cache, renderer-port, and image-lifetime contracts. PDFBox access to one
generation is serialized. Cache and concurrency limits are memory-aware rather
than page-count-based. Stale or cancelled work cannot publish, and compilation
and preview failures remain distinct.

The benchmark does not establish Windows/macOS RSS, full real-world fidelity,
Compose scrolling behavior, snapshot cost, or all optional-codec behavior.
Those remain release-validation and upgrade gates. Renderer changes require a
new accepted decision supported by a rerun of the benchmark and operational,
licensing, and packaging evidence.

### References

- [AeTeX Architecture 004](../architecture/004-pdf-preview-system.md)
- [Architecture Study 004](../architecture/004-pdf-preview-system-study.md)
- [Experimental rendering benchmark](../../tools/rendering-benchmark/README.md)
- [AeTeX Architecture 003](../architecture/003-compilation-system.md)
- [Roadmap](../roadmap/roadmap.md)

## ADR-0014 — Keep workspace layout explicit and user-level

- **Date:** 2026-07-31
- **Status:** Accepted (implemented)

### Context

The original Compose workspace assigned fixed 260 dp and 480 dp widths to the
Project and Preview panels. The editor alone received remaining width. There
was no preference store, panel visibility model, divider boundary, or explicit
window constraint.

### Decision

Represent the three-region workspace with one immutable `WorkspaceLayout`
model in dp. Keep it independent of project, compilation, and preview domain
ownership. Resolve available width deterministically with an editor-first
minimum policy, explicit side-panel minimums, fixed divider targets, and
an always-visible Project tool rail plus a compact closed-Preview edge
affordance. Preview view state uses explicit Fit Width, Fit Page, and Fixed
zoom identities; only normalized density-aware raster scales enter cache keys.

Persist only the user-level layout in versioned UTF-8 TOML under the operating
system's user configuration directory. Coalesce drag writes, publish by
temporary-file replacement, validate every loaded value, ignore unknown
schema-1 fields, and fall back to defaults on corruption or unsupported
schemas.

### Consequences

Dragging and hiding panels cannot request compilation, replace a project, or
retire a Preview generation. Compose may dispose hidden view resources while
the owning managers and current artifact remain intact. Project-tree expansion
and Preview zoom are held above conditional panel composition. Zoom remains
session-level because derived fit percentages are not canonical workspace
preferences.

The model does not generalize into docking. Arbitrary docking, floating
windows, user-customizable tool-rail ordering, tabbed tool windows, per-project
layouts, and window-position persistence remain outside this decision.

### References

- [AeTeX Architecture 005](../architecture/005-workspace-layout.md)
- [Workspace user guide](../user-guide/workspace.md)
- [Roadmap](../roadmap/roadmap.md)
