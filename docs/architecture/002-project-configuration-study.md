# AeTeX Architecture Study 002: Project Configuration

| Field | Value |
| --- | --- |
| Identifier | AeTeX Architecture Study 002 |
| Status | Study, not accepted |
| Date | 2026-07-29 |
| Scope | Project-owned configuration, schema evolution, root document intent, and build-relevant settings |

This document studies the architecture options for AeTeX project configuration. It is intentionally not an accepted architecture decision and does not specify an implementation contract.

The purpose is to answer:

> How should AeTeX design project configuration so it can support future growth without introducing unnecessary complexity?

The recommendation at the end identifies the direction that should be turned into a future accepted architecture document after review.

## Repository Baseline

AeTeX is currently a local desktop IDE for LaTeX projects. The accepted product direction is defined by [Architecture 000](000-vision.md), and the implemented project/document baseline is described by [Architecture 001](001-project-model.md).

The current implementation has these relevant properties:

- A project is a readable real directory selected by the user.
- `TeXProject` contains `rootDirectory`, a hierarchical `entries` snapshot, and `mainDocument: Path?`.
- `mainDocument` is always `null` in normal application use because the scanner does not detect, load, or persist it.
- Project scanning is synchronous and produces a static tree.
- The scanner excludes `.git`, `.gradle`, `build`, and `out` by fixed directory name.
- `DocumentService` confines document access to the active project root and uses `Path` plus real-path validation.
- Editable text files are restricted to `tex`, `bib`, `sty`, `cls`, and `txt`.
- There is no persistent per-project configuration.
- There is no toolchain discovery, compilation, PDF loading, SyncTeX, semantic index, or file watcher.
- The Gradle build currently has no configuration parser dependency beyond Kotlin, Compose Desktop, and test dependencies.

The [roadmap](../roadmap/roadmap.md) places project configuration in Milestone 1. It explicitly leaves the file name, serialization format, precedence rules, discovery rules, and migration behavior undecided. Milestone 2 compilation, Milestone 3 preview, and Milestone 4 SyncTeX depend on configuration being clear enough to identify what to build and where outputs belong.

The decision log also establishes constraints that matter here:

- `Path` is the central path representation.
- A project directory is the unit of work.
- Scanning, document access, state coordination, and UI are separate responsibilities.
- Data safety and explicit user intent matter more than hidden behavior.
- PDF preview is deferred until configuration and compilation can define the output.

## 1. Objectives

Project configuration should solve the minimum persistent project intent needed for the next milestones.

It should:

- identify that a directory is configured for AeTeX;
- identify the main document, when the project has one clear root;
- record the preferred TeX engine;
- record the compilation strategy at a level useful before process execution exists;
- record the output directory policy or path;
- persist settings that belong to the project rather than to one user's machine;
- provide a stable base for future build, preview, SyncTeX, diagnostics, and large-project behavior;
- let simple projects remain usable when configuration is absent or incomplete;
- produce explicit recoverable states for missing, invalid, stale, or unsupported settings;
- support manual editing by users who prefer ordinary files and version control.

This fits Architecture 000's principles: AeTeX should preserve ordinary files, integrate with established tools, remain local-first, and let the user inspect and control the toolchain.

## 2. Non-Objectives

Project configuration should not become a dumping ground for every persisted setting.

It should not solve:

- global user preferences;
- general UI preferences such as theme, panel layout, editor font, or default zoom;
- machine-specific executable paths such as a local TeX Live installation directory;
- secrets, credentials, tokens, or account data;
- network service settings required for optional integrations;
- temporary runtime state such as current build progress;
- open editor tabs or unsaved recovery buffers;
- dependency indexes or caches;
- full build-system replacement for `latexmk`, TeX engines, or future external tools.

Some of those items may need persistence later, but they have different ownership, privacy, portability, and compatibility requirements. Mixing them into project configuration would make shared Git history noisy and could leak local paths or private data.

## 3. Shared Configuration vs Local Configuration

The core distinction is ownership.

Shared configuration belongs to the project and should normally be committed. Local configuration belongs to a user or machine and should normally stay outside shared history.

### Model A: One Shared Project File Only

Example shared values:

- main document;
- engine;
- compilation strategy;
- targets;
- output directory.

Advantages:

- Simple mental model for Milestone 1.
- One file to discover, validate, display, and test.
- Works well with Git because the settings describe project intent.
- Avoids premature precedence rules between shared and local values.
- Matches the current project-root model and `TeXProject.mainDocument` extension point.

Disadvantages:

- Cannot store local executable paths or personal UI state.
- Users may be tempted to put machine-specific values into the shared file.
- Future local overrides will need an explicit addition and migration story.

Fit with AeTeX:

This is the smallest model that satisfies Milestone 1. It supports configuration, compilation preparation, and preview identity without expanding scope into preferences.

### Model B: Shared File Plus Local File From the Start

Example:

- shared: `.aetex/project.toml`;
- local: `.aetex/local.toml`.

Advantages:

- Clean conceptual separation from day one.
- Can support local TeX paths, personal build command overrides, zoom, or theme without polluting Git.
- Makes future preference storage more predictable.

Disadvantages:

- Requires precedence rules immediately.
- Requires UI and diagnostics to explain whether a value came from shared or local config.
- Requires ignore-file guidance and probably Git integration behavior.
- Creates more implementation and test surface before AeTeX has compilation or preview.
- Local UI preferences may belong in platform application settings rather than inside the project folder.

Fit with AeTeX:

The distinction is architecturally sound, but implementing both files in Milestone 1 would be more complexity than the current roadmap requires.

### Model C: Shared File Plus Platform User Preferences

Example:

- shared: project-owned file inside the project;
- local: application settings stored in the OS/user profile.

Advantages:

- Keeps the project directory clean.
- Avoids committing local state by accident.
- Fits UI preferences such as theme, font, and zoom.
- Lets one local TeX installation setting apply across many projects.

Disadvantages:

- Does not work well for local values that are project-specific.
- Adds platform persistence behavior that is not otherwise needed yet.
- Makes project portability less inspectable because local overrides live elsewhere.

Fit with AeTeX:

This is likely the right eventual home for many user preferences, but it does not replace a shared project file.

### Model D: Single File With Local Sections

Example:

```toml
[project]
main = "main.tex"

[local]
texlive = "C:/texlive/2026/bin/windows"
```

Advantages:

- One parser and one file.
- Easy to inspect.

Disadvantages:

- Encourages committing local paths.
- Requires conventions for redacting or ignoring only part of a file, which Git does not handle naturally.
- Blurs ownership and makes review harder.
- Creates avoidable conflict between team-shared and personal values.

Fit with AeTeX:

This conflicts with the local-first, ordinary-file, Git-friendly direction. It should be avoided.

### Recommendation for Shared vs Local

AeTeX should separate shared project configuration from local/user configuration as an architecture rule, but Milestone 1 should implement only the shared project configuration file.

Local configuration should be explicitly reserved for a future design. When introduced, it should not use the same shared file. The future design can decide between a project-local ignored file and platform user settings based on the kind of values being persisted.

## 4. Configuration File Location

The file location has to balance visibility, Git behavior, tooling, and future growth.

### Alternative A: `.aetex`

This could be a single file at the project root.

Advantages:

- Short name.
- Clearly hidden on Unix-like systems.
- Similar to some tool marker files.

Disadvantages:

- No extension, so editors and tools cannot infer a format.
- Cannot naturally grow into multiple files without changing from file to directory.
- Ambiguous when describing it in UI or documentation.
- Hidden by default on some systems.

Assessment:

Too constrained for a configuration format that should remain manually editable and may grow.

### Alternative B: `aetex.toml`

Advantages:

- Visible at the project root.
- Format is obvious from the extension.
- Easy to edit, search, and review.
- Similar to root-level project files used by many tools.

Disadvantages:

- Adds another top-level file to LaTeX projects.
- Less room for future AeTeX-owned files unless more root files are added.
- Project roots with many files may make it visually noisy.

Assessment:

Strong for simplicity and discoverability. Weaker if AeTeX later needs caches, local ignored state, generated metadata, or multiple configuration files.

### Alternative C: `.aetex/config.toml`

Advantages:

- Groups AeTeX-owned files under one directory.
- Extension remains clear.
- Directory can later contain local ignored files, cache pointers, migrations, or separate task files.
- Keeps the project root less noisy.

Disadvantages:

- `config.toml` is generic and less clear in editor tabs or search results.
- Nested hidden directory is slightly less discoverable.
- Requires creating a directory for one file.

Assessment:

Good growth path, but the generic file name is less self-descriptive than a project-specific name.

### Alternative D: `.aetex/project.toml`

Advantages:

- Clearly identifies shared project configuration.
- Keeps AeTeX files grouped.
- Leaves room for future `.aetex/local.toml`, `.aetex/tasks.toml`, cache metadata, or migrations if needed.
- Reduces root clutter while preserving an explicit extension.
- Avoids the file-to-directory migration problem of a single `.aetex` file.

Disadvantages:

- Less visible than a root-level file.
- Some users may not notice hidden directories unless the UI exposes the configuration.
- Requires slightly more complex discovery than checking one root file.

Assessment:

Best balance for AeTeX if future growth is expected but Milestone 1 should remain small.

### Alternative E: `.aetex/aetex.toml`

Advantages:

- Both directory and file names identify AeTeX.
- Extension is clear.

Disadvantages:

- Repeats the product name.
- Less semantically clear than `project.toml`.
- Does not communicate shared project ownership as well.

Assessment:

Acceptable but less clear than `.aetex/project.toml`.

### Recommendation for Location

The best candidate is `.aetex/project.toml` for shared project configuration.

It supports future growth better than `aetex.toml`, is clearer than `.aetex/config.toml`, and avoids the migration trap of using `.aetex` as a file. The cost is discoverability, which AeTeX can offset in the UI by showing configuration state explicitly.

## 5. Format

The format should be readable, manually editable, comment-friendly, and practical in Kotlin.

### TOML

Advantages:

- Designed for configuration rather than data interchange.
- Supports comments.
- Good readability for scalar fields, arrays, and tables.
- Stable enough for manual versioned project files.
- Maps naturally to sections such as project metadata, build settings, and future targets.
- Kotlin/JVM parser options exist, though AeTeX would need to choose a dependency deliberately.

Disadvantages:

- Less expressive than YAML for complex nested structures.
- Parser dependency would be new for the project.
- Some users know JSON or YAML better.
- The standard has details that must be respected rather than parsed with ad hoc string handling.

Assessment:

TOML is a strong fit for shared project configuration if AeTeX keeps the schema modest and table-oriented.

### YAML

Advantages:

- Familiar in many toolchains.
- Supports comments.
- Supports nested structures naturally.
- Good for larger documents and lists.

Disadvantages:

- Complex specification with many edge cases.
- Indentation and implicit typing can surprise users.
- Kotlin/JVM parser dependencies are heavier than the configuration need warrants.
- Human-readable does not always mean predictable.

Assessment:

YAML is more flexible than AeTeX currently needs. Its ambiguity and parser complexity are not justified for Milestone 1.

### JSON

Advantages:

- Universally known and easy to parse.
- Tooling support is excellent.
- Strict syntax and data model reduce ambiguity.

Disadvantages:

- No standard comments.
- Poor manual editing experience for configuration.
- Trailing commas are invalid in standard JSON.
- Less friendly for path-heavy and explanation-heavy project settings.

Assessment:

JSON is better as an interchange format than as a user-edited project configuration file.

### HOCON

Advantages:

- Configuration-oriented and expressive.
- Supports comments and includes.
- Can be pleasant for layered configuration.

Disadvantages:

- Less common for desktop project files outside certain JVM ecosystems.
- More features than AeTeX needs now.
- Includes and substitutions create additional security, portability, and explanation concerns.

Assessment:

HOCON is powerful but would introduce unnecessary semantics before AeTeX has enough configuration surface to justify them.

### INI

Advantages:

- Simple and familiar.
- Supports comments in common implementations.
- Easy for simple key/value sections.

Disadvantages:

- No single strict standard.
- Weak representation of arrays, nested structures, and typed values.
- Future targets or build settings would require conventions.
- Parser behavior varies.

Assessment:

INI would be small initially but would likely age poorly as targets, bibliography, SyncTeX, hooks, or tasks appear.

### Custom LaTeX Comment Directives

Example:

```text
% !TEX root = main.tex
% !TEX program = lualatex
```

Advantages:

- Existing convention in some editors.
- Travels with source files.
- Useful as an import or detection signal.

Disadvantages:

- Not a project-level configuration model.
- Does not naturally represent output directories, targets, or future build policy.
- Multiple files can disagree.
- Editing comments in source files to configure the IDE is indirect.

Assessment:

These directives may be useful as heuristics for main-document detection or import, but not as the primary AeTeX project configuration.

### Recommendation for Format

TOML is the strongest candidate for the shared project file, not because it is popular, but because it matches AeTeX's specific needs: manual editing, comments, typed scalar values, table sections, Git reviewability, and moderate future growth.

The recommendation depends on using a real TOML parser when implementation begins. AeTeX should not implement a partial parser with ad hoc string handling.

## 6. Schema Versioning

Configuration versioning must let AeTeX distinguish unsupported future files, validate old files, and plan migrations.

### Alternative A: Integer Schema

Example:

```toml
schema = 1
```

Advantages:

- Simple to read and compare.
- Good fit for file format migrations.
- Avoids implying product release compatibility.
- Easy to explain in errors.
- Works well when changes are discrete and migration-oriented.

Disadvantages:

- Does not communicate minor compatible additions by itself.
- Requires written compatibility rules for unknown fields.

Assessment:

Best fit for early AeTeX configuration.

### Alternative B: Semantic Version

Example:

```toml
schema = "1.0.0"
```

Advantages:

- Familiar version notation.
- Can encode major/minor/patch compatibility intent.

Disadvantages:

- Overly precise for a file schema that will likely change by migration steps.
- May be confused with application version.
- Requires policy for each segment before there is evidence that the distinction matters.

Assessment:

More ceremony than useful at Milestone 1.

### Alternative C: Application Version

Example:

```toml
createdBy = "AeTeX 1.0.0"
```

Advantages:

- Useful as metadata.

Disadvantages:

- Application version and schema version are different concepts.
- The roadmap already notes that build metadata does not represent milestone maturity.
- Makes compatibility harder to reason about.

Assessment:

Should not be the schema mechanism.

### Alternative D: UUID or Opaque Identifier

Advantages:

- Can identify exact schema definitions.

Disadvantages:

- Unfriendly to users.
- Hard to compare.
- Solves a problem AeTeX does not have.

Assessment:

Not appropriate for a manually edited project file.

### Recommendation for Versioning

Use an integer schema field at the top level:

```toml
schema = 1
```

AeTeX should treat missing schema explicitly. The implementation RFC should decide whether a missing schema means "legacy unversioned AeTeX config" or "invalid config"; since no config exists today, requiring `schema = 1` for the first accepted format is reasonable.

## 7. Main Document

The main document is the most important Milestone 1 setting because compilation and preview need a root.

### Manual Configuration Only

Advantages:

- Deterministic.
- Avoids guessing incorrectly.
- Clear source of truth.
- Easy to validate against the project root.

Disadvantages:

- Empty or simple projects need setup before they feel useful.
- Users may not know which file is the root.
- Adds friction for projects with conventional `main.tex`.

Assessment:

Good as the authority once set, but too rigid as the only experience.

### Automatic Detection Only

Possible heuristics:

- prefer configured-looking names such as `main.tex`;
- find files containing `\documentclass`;
- consider `% !TEX root` directives;
- ignore generated or excluded directories;
- avoid symbolic link traversal consistent with the scanner.

Advantages:

- Low friction.
- Useful for simple projects.
- Helps users discover likely roots.

Disadvantages:

- Heuristics can be wrong.
- Multi-root projects are common enough to matter.
- Generated examples, included chapters, or old drafts can confuse detection.
- Hidden behavior would conflict with the roadmap's goal of not relying on hidden heuristics.

Assessment:

Useful as assistance, not as the persistent source of truth.

### Assisted Detection With Manual Confirmation

Advantages:

- Keeps simple projects approachable.
- Makes uncertainty visible.
- Allows AeTeX to propose likely roots without silently committing to one.
- Fits explicit recoverable state requirements for unconfigured projects.

Disadvantages:

- Requires UI state for candidates and invalid configuration.
- Requires ranking and diagnostics to be testable.
- Still needs fallback behavior when there are no candidates or many candidates.

Assessment:

Best fit for AeTeX's principles.

### Multiple Main Documents

Advantages:

- Matches real projects with article, thesis, slides, appendix, or poster roots.
- Aligns with possible future targets.

Disadvantages:

- Expands Milestone 1 from "what is the root" to target modeling.
- Requires build-output identity per target.
- Adds complexity before compilation exists.

Assessment:

Important future capability, but not necessarily required in the first schema.

### Recommendation for Main Document

The shared configuration should support one explicit `main` path for the first accepted schema, stored relative to the project root. AeTeX should also provide assisted detection for unconfigured projects and for fixing stale paths, but detected values should not silently become project configuration without user confirmation.

The future architecture should preserve room for multiple targets without making every project use target syntax immediately.

## 8. Targets

A target is a named buildable unit inside a project, such as:

- `thesis`;
- `slides`;
- `poster`.

### No Targets in Initial Schema

Advantages:

- Very simple.
- Fits the current `TeXProject.mainDocument` shape.
- Sufficient for many projects.
- Reduces validation and UI complexity.

Disadvantages:

- Future migration may be needed for multi-root projects.
- Output paths and build settings become global even when they should differ per root.
- Users with multiple deliverables may outgrow the schema quickly.

Assessment:

Acceptable only if the format has a clear migration path.

### Targets From the Start

Example:

```toml
[targets.thesis]
main = "thesis.tex"
engine = "lualatex"
output = "build/thesis"

[targets.slides]
main = "slides.tex"
engine = "xelatex"
output = "build/slides"
```

Advantages:

- Models real multi-output projects cleanly.
- Naturally scopes engine, output, bibliography, and SyncTeX settings.
- Avoids later restructuring.

Disadvantages:

- Forces simple projects to understand target concepts early.
- Requires default-target selection.
- Increases UI and validation scope before compilation exists.
- Could delay Milestone 1 without improving the common path.

Assessment:

Architecturally attractive but probably premature as the required initial model.

### Hybrid: Single Project Defaults Plus Optional Targets Later

Example direction:

```toml
schema = 1
main = "main.tex"
engine = "lualatex"
strategy = "latexmk"
output = "build"
```

A future schema could add:

```toml
[targets.thesis]
main = "thesis.tex"
```

Advantages:

- Keeps Milestone 1 small.
- Does not block future multi-target support.
- Lets defaults later apply to targets.
- Gives simple projects a clean file.

Disadvantages:

- Requires careful schema evolution.
- Future target support must define precedence between project defaults and target overrides.

Assessment:

Best balance for the current roadmap.

### Recommendation for Targets

Do not require targets in the first schema. Design the top-level fields as project defaults that can later be migrated into or inherited by named targets.

The future accepted architecture should state that multi-target support is a reserved evolution path, not a Milestone 1 commitment.

## 9. Future Configuration

AeTeX should not fully design future fields now, but the first format should leave space for them.

Likely future areas include:

- TeX engine: `pdflatex`, `xelatex`, `lualatex`, or custom engine selection.
- Compilation strategy: `latexmk` first, later direct engine, custom command, or task runner.
- Bibliography processor: `bibtex`, `biber`, automatic, or delegated to `latexmk`.
- SyncTeX: enablement, output file identity, synchronization data location.
- Output policy: build directory, generated-file exclusion, PDF path.
- Diagnostics: log parsing mode or strictness.
- Hooks: before build, after build, after success, after failure.
- Scripts or tasks: named commands for project workflows.
- Variables: reusable values for paths or commands.
- Indexing: generated-resource policy and large-project exclusions.
- Preview: preferred output target, initial page behavior, reload policy.
- Import metadata: source of settings imported from editor directives or external build files.

These fields differ in risk. Engine, strategy, and output are core build intent. Hooks, scripts, variables, and custom commands can execute processes and therefore require a separate security and user-consent design.

## 10. Compatibility

Compatibility should be explicit from the first schema.

### Projects With No Configuration

AeTeX must continue opening ordinary LaTeX folders. No configuration should mean "unconfigured project", not "not a project".

Expected behavior:

- scan the tree as today;
- show a recoverable unconfigured state;
- offer assisted main-document detection;
- allow editing existing supported files;
- block compilation until required build intent is available.

### Unknown Files

AeTeX should ignore unrelated files and directories. The presence of `.latexmkrc`, `Makefile`, `texmf.cnf`, or editor-specific settings should not break project opening.

Those files may later inform import or detection, but they should not be treated as authoritative without a design.

### Unknown Fields

For the same schema version, AeTeX should preserve compatibility by warning about unknown fields rather than failing the whole project, unless the field is in a namespace that implies required semantics.

Failing on every unknown field makes forward-compatible edits impossible. Silently ignoring unknown fields makes mistakes hard to detect. A warning-level validation issue fits the existing pattern of recoverable project scan issues.

### Future Schema Versions

If `schema` is greater than the maximum supported schema, AeTeX should not try to interpret the file as if it knew the meaning. It should open the project for editing, report that the configuration is from a newer AeTeX schema, and disable configuration-dependent workflows such as compilation.

### Older Schema Versions

Older known schema versions should be loaded through explicit migrations or compatibility readers. Migrations should be data-preserving and should not rewrite files silently. A user-visible "can migrate" state is safer than automatic edits on project open.

### Stale Paths

Configuration paths should be lexical relative paths under the project root, then validated using the same real-path containment principles as document access. A stale `main` path should not prevent opening the project, but it should prevent compilation until repaired.

## 11. Risks

### Shared and Local Configuration Risks

- A shared-only first milestone may frustrate users who need machine-specific tool paths.
- A shared-plus-local first milestone may add precedence complexity before the product needs it.
- A single mixed file may leak personal paths into Git history.
- Platform user settings may hide important project behavior from team review.

### File Location Risks

- A hidden `.aetex` directory may be missed by users inspecting a project manually.
- A root-level `aetex.toml` may clutter existing LaTeX repositories.
- A single `.aetex` file cannot grow into a directory without migration.
- Multiple files under `.aetex` can become a private mini-ecosystem if not governed carefully.

### Format Risks

- TOML still requires a real parser dependency and schema validation.
- YAML allows surprising typing and heavier parsing behavior.
- JSON discourages comments and manual configuration.
- INI may require custom conventions that become accidental complexity.
- HOCON includes more semantics than AeTeX should accept casually.

### Schema Risks

- No schema field makes future migrations fragile.
- Semantic schema versions may imply compatibility guarantees that are not actually designed.
- Unknown fields can hide typos if warnings are not visible.
- Automatic migrations can change committed files without clear user intent.

### Main Document Risks

- Manual-only configuration adds setup friction.
- Detection-only behavior can compile the wrong file.
- Multi-root support too early can make the common case harder.
- Symlinks and paths outside the root must remain constrained consistently with current document access.

### Target Risks

- No target model may require migration for real multi-deliverable projects.
- Mandatory targets may overcomplicate the first configuration milestone.
- Per-target output directories interact with scanner exclusions, preview reload, and SyncTeX identity.

### Future Execution Risks

- Hooks, scripts, and custom commands create process-execution trust issues.
- Local tool paths and shared commands can be non-portable.
- Build output configuration can cause generated files to appear in the project tree unless scan and generated-file policies evolve together.

## 12. Recommendation

The recommended direction is:

- Create a shared project configuration file at `.aetex/project.toml`.
- Use TOML with a required top-level integer `schema = 1`.
- Keep Milestone 1 shared-only; do not implement local configuration yet.
- Store project-relative paths, not absolute machine-specific paths.
- Support one explicit top-level `main` document path in the first schema.
- Treat engine, compilation strategy, and output directory as project defaults.
- Reserve named targets for a future schema rather than requiring them now.
- Use assisted detection for unconfigured or stale projects, but require user confirmation before persisting detected intent.
- Open projects without configuration as valid but unconfigured.
- Treat invalid configuration as recoverable project state, not as a reason to reject the directory.
- Warn on unknown fields within supported schemas and reject interpretation of newer unsupported schemas for build-dependent workflows.
- Defer local settings, executable paths, UI preferences, hooks, scripts, variables, and custom command execution to later architecture work.

A minimal illustrative shape could be:

```toml
schema = 1
main = "main.tex"
engine = "lualatex"
strategy = "latexmk"
output = "build"
```

This is not a final schema. It only illustrates the recommended level of complexity.

## Decisions to Promote Into an Accepted Architecture 002

A future accepted Architecture 002 should decide and specify:

- exact discovery rules for `.aetex/project.toml`;
- exact TOML parser and validation approach;
- accepted values for engine and compilation strategy;
- path normalization and validation rules for project-relative settings;
- absent, invalid, stale, and unsupported configuration states;
- user-visible assisted detection behavior for the main document;
- unknown-field policy and diagnostics;
- schema migration policy;
- whether initial output directory values interact with scanner exclusions;
- tests required for loading, defaults, validation, schema compatibility, and user-facing errors.

The current study recommends the architecture direction, but it should not be treated as the final RFC or implementation specification.
