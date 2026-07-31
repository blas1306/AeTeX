# AeTeX Architecture 002: Project Configuration System

## 1. Status

| Field | Value |
| --- | --- |
| Identifier | AeTeX Architecture 002 |
| Title | Project Configuration System |
| Status | Accepted |
| Date | 2026-07-29 |
| Scope | Shared, persistent project configuration; main-document resolution; build defaults; validation; and schema compatibility |

This document is the normative architecture for AeTeX project configuration. It
promotes the accepted direction from
[Architecture Study 002](002-project-configuration-study.md) into an
implementation contract. The study remains as historical analysis, but this
document takes precedence wherever the two differ.

The schema, canonical loader and validator, effective configuration, canonical
new-file writer, and IDE project-provisioning workflows are implemented. The
directory, scanner, and document foundation remains described by
[Architecture 001](001-project-model.md).

## 2. Motivation

An AeTeX project is more than a folder of editable files. It has one active root
document, build intent, generated output, and eventually a compilation result
that identifies the PDF and SyncTeX data belonging to that document. The current
folder snapshot cannot preserve that intent between application sessions:
`TeXProject.mainDocument` exists as an extension point, but the scanner always
leaves it unset.

Persistent configuration gives compilation a stable input instead of requiring
the user or AeTeX to rediscover important choices on every open. PDF preview
must know which build output to display, and SyncTeX must be associated with the
same main document and output that produced its synchronization data.

Detection remains useful for ordinary LaTeX folders, but heuristics can be
ambiguous or stale. Configuration records confirmed project intent while still
allowing AeTeX to assist projects that have not been configured yet.

## 3. Objectives

The configuration system must:

- identify the single active main document;
- define portable project defaults;
- provide the stable input model for compilation and its outputs;
- remain easy to commit, diff, and review in version control;
- remain readable and editable without AeTeX;
- evolve through explicit schema compatibility rules without breaking older
  projects;
- preserve ordinary folder-based projects as valid AeTeX projects when no
  configuration file exists.

This architecture does not implement compilation, preview, SyncTeX, local
preferences, or configuration I/O. It defines the contract those subsystems will
consume.

## 4. Principles

### Minimal valid configuration

Persist only intent that AeTeX cannot infer reasonably or that the user wants to
make explicit. When exactly one consistent choice exists, AeTeX must detect it
before requiring the user to enter the same value manually. Detection may
produce an effective runtime value, but AeTeX must not silently write that value
to the project file.

### Shared project configuration

The file contains only information shared by all collaborators. It must not
contain UI preferences, open tabs, executable locations, operating-system
paths, credentials, caches, or other user- or machine-specific state.

### Relative paths

Every stored project path is relative to the project root. Absolute paths,
drive-qualified paths, UNC paths, home-directory shortcuts, and paths that
escape the root are invalid.

Configuration uses `/` as its portable path separator. On loading, AeTeX maps
those segments to the host filesystem and applies the normalization, real-path
containment, and symbolic-link safety principles established by
[Architecture 001](001-project-model.md).

### Explicit schema

Every persisted configuration has a top-level integer schema identifier.
Compatibility and migrations depend on this identifier, not on the AeTeX
application version.

### Human editable

The project file is ordinary UTF-8 text. A user can inspect and edit it with any
text editor, use TOML comments, and review changes with normal version-control
tools.

### Forward compatibility

Within a supported schema, AeTeX ignores unknown fields when it can still
interpret all required behavior. It reports them as warnings rather than
rejecting the project. A writer must not discard unknown data silently.

## 5. Location

Shared project configuration has exactly one location relative to the selected
project root:

```text
.aetex/project.toml
```

The `.aetex` directory groups AeTeX-owned project data without adding several
top-level files. The specific `project.toml` name makes shared ownership clear
and leaves room for separately governed future data.

The reserved shape is:

```text
.aetex/
    project.toml
    workspace.toml (future)
    cache/          (future)
```

Only `project.toml` belongs to the current configuration system.
`workspace.toml` and `cache/` are reservations, not current files or contracts.
Project creation and initialization create `project.toml`; the current code
does not create or read the reserved entries.

AeTeX discovers configuration only at this exact path. It does not search parent
directories, merge nested `.aetex` directories, or import another tool's
configuration as authoritative project configuration.

## 6. Format

The format is TOML, encoded as UTF-8.

TOML provides typed scalar values, comments, predictable tables for later
compatible growth, and a compact representation that remains readable in Git
diffs. Implementation must use a conforming TOML parser; partial parsing with
regular expressions or line splitting does not satisfy this contract.

Top-level keys are case-sensitive. Schema 1 uses only the top-level fields
defined in this document.

## 7. Schema

Every file starts with:

```toml
schema = 1
```

`schema` is a required positive integer identifying the structure and semantics
of the configuration file. Schema 1 is the first AeTeX project-configuration
schema.

The schema changes when a configuration cannot be interpreted safely under the
existing structural and semantic rules or requires an explicit migration.
Adding an optional field that old readers can ignore does not by itself require
a new schema.

The schema is not:

- the AeTeX application version;
- the version of the project or document;
- the TeX distribution or engine version;
- a feature flag or migration timestamp.

A missing, non-integer, or unsupported schema is not interpreted as schema 1.

## 8. Minimal Configuration

The smallest self-contained configuration, requiring no main-document
detection, is valid:

```toml
schema = 1

main = "main.tex"
```

`engine`, `strategy`, and `output` are optional and resolve through the defaults
in section 11. A file containing only `schema = 1` is structurally valid, but the
project becomes ready for configuration-dependent workflows only if the
main-document algorithm resolves and the user confirms one document.

This distinction keeps persisted configuration minimal without treating an
unresolved project as buildable.

## 9. Initially Supported Configuration

Schema 1 defines exactly these fields:

```toml
schema = 1

main = "main.tex"

engine = "xelatex"

strategy = "latexmk"

output = "build"
```

| Field | Type | Required in file | Meaning | Effective default |
| --- | --- | --- | --- | --- |
| `schema` | Integer | Yes | Configuration schema identifier | None; it must equal `1` |
| `main` | String path | No | Active root LaTeX document | No fixed path; resolve using section 10 |
| `engine` | String enum | No | TeX engine used by the build strategy | Infer as specified in section 11, then `pdflatex` |
| `strategy` | String enum | No | Coordinator used to compile the document | `latexmk` |
| `output` | String path | No | Directory for generated build artifacts | `build` |

### `main`

`main` is a non-empty project-relative path to a readable regular `.tex` file.
It uses `/` separators, must resolve inside the project root, and its final
component must not be a symbolic link. The file must satisfy the valid-candidate
rules in section 10.

An explicit valid `main` is authoritative. An explicit stale or invalid `main`
is a configuration error and is not silently replaced by a detected candidate.

### `engine`

Schema 1 accepts exactly `pdflatex`, `xelatex`, or `lualatex`, written in lower
case. The value names the engine, not an executable path and not an arbitrary
command. Tool discovery later maps the value to an available local executable.
An unsupported value is a configuration error.

### `strategy`

Schema 1 accepts exactly `latexmk`. The field records build orchestration, not a
shell command or script. Other strategies require a later compatible extension
or schema revision. An unsupported value is a configuration error.

### `output`

`output` is a non-empty project-relative directory path using `/` separators.
It may name a directory that does not exist yet. It must normalize and resolve
inside the project root; if it exists, it must be a directory and its real path
must remain inside the root. It must never be interpreted relative to the main
document or the process working directory.

The output directory must be a proper descendant of the project root. It must
not be `.aetex`, contain `.aetex/project.toml`, contain the effective `main`, or
otherwise overlap the main document path. Once defaults and valid configuration
are resolved, the effective output directory is an additional scanner exclusion
and AeTeX does not recurse into it. This extends rather than replaces the
scanner's fixed exclusions. The current fixed `build` exclusion already matches
the schema 1 default; a custom output requires configuration-aware scanning.

Schema 1 does not define the PDF filename separately. Compilation derives output
identity from the active main document and the build result.

## 10. Main Document Detection

AeTeX resolves the main document in this order:

1. **Explicit configuration.** If `main` exists, validate and use it. If it is
   invalid or stale, keep the project open, report the error, and require the
   user to repair or replace the explicit value.
2. **Single valid candidate.** If no `main` is configured and exactly one valid
   candidate exists, select it as the provisional main document.
3. **Heuristics.** If several valid candidates exist, apply the deterministic
   signals below to identify one candidate.
4. **User intervention.** If no candidate is valid or the signals remain
   ambiguous, require the user to select a valid main document.

A valid candidate is a file that:

- has a `.tex` extension, compared case-insensitively;
- is a readable regular file inside the project root;
- is not a final-component symbolic link and passes real-path containment;
- is not under `.aetex`, the effective output directory, or a directory excluded
  by the project scanner;
- contains an active `\documentclass` command outside a TeX comment.

For multiple candidates, AeTeX evaluates these signals in order:

1. valid `% !TEX root = ...` directives found in project `.tex` files all
   converge on one candidate;
2. exactly one candidate has the basename `main.tex`, compared
   case-insensitively;
3. exactly one candidate is directly in the project root;
4. exactly one candidate has a basename matching the project-directory name,
   compared case-insensitively.

Each signal may select a document only when its result is unique. AeTeX never
breaks a tie by traversal order, modification time, or platform-dependent path
ordering. Invalid directives are diagnostics, not candidates.

An automatically selected document remains provisional until the user confirms
it for the project. Confirmation may create or update `main` in
`.aetex/project.toml`; detection alone never modifies the file. A provisional
selection may support navigation and the configuration UI, but compilation,
preview, and SyncTeX require a confirmed effective configuration.

## 11. Defaults

Defaults produce the effective configuration without adding redundant fields to
the persisted file:

- **`main`:** There is no filename default. Resolve it through section 10.
- **`engine`:** An explicit value wins. Otherwise, AeTeX inspects the confirmed
  main document for a single consistent top-of-file
  `% !TEX program = pdflatex|xelatex|lualatex` directive. A recognized directive
  supplies the effective engine. If there is no recognized directive, use
  `pdflatex`. Conflicting or unsupported program directives produce a warning
  and do not override that fallback.
- **`strategy`:** Use `latexmk`.
- **`output`:** Use `build`, relative to the project root.

A top-of-file engine directive is one that appears among the initial blank or
comment-only lines before the first source line. Engine inference does not infer
from installed executables, because machine availability must not change shared
project meaning. It also does not guess from package usage in schema 1.

Defaults are runtime values. AeTeX must not write them into `project.toml`
merely because the project was opened. Explicit fields always take precedence
over defaults and inference.

## 12. Local Configuration

Local configuration is not part of this system. Schema 1 defines no override
file, merge order, machine-specific path, or personal preference section.

The path below is reserved as a possible future evolution:

```text
.aetex/workspace.toml
```

Its ownership, version-control policy, schema, and precedence require a separate
architecture decision. This document does not design them. AeTeX must not treat
`workspace.toml` as configuration until that decision exists.

## 13. Compatibility

### Projects without configuration

The absence of `.aetex/project.toml` means an unconfigured project, not an
invalid folder. AeTeX opens and scans it, permits normal editing, and runs the
main-document detection flow. Configuration-dependent workflows remain
unavailable until the user confirms an effective configuration.

### Older projects and schemas

Known older schemas are read through an explicit compatibility reader or
data-preserving in-memory migration. AeTeX reports when a persisted migration is
available and never rewrites a project file merely because the project was
opened. Schema 1 is the first schema, so there is no unversioned legacy AeTeX
configuration to assume.

### Unknown schema

When `schema` is newer than the maximum supported value, AeTeX does not
interpret known-looking fields under schema 1 rules. It opens the project for
editing, reports the unsupported schema, and disables configuration-dependent
workflows. An older schema for which the running version has no compatibility
reader receives the same recoverable treatment.

### Unknown fields

For a supported schema, unknown fields produce warnings and are otherwise
ignored when all required semantics remain resolvable. Known fields with an
invalid type or value are errors, not unknown fields. Any operation that writes
configuration must preserve unknown fields or obtain explicit user approval for
a rewrite that could remove them.

### Missing schema, invalid TOML, and corrupt files

A missing schema, invalid TOML, duplicate keys, invalid UTF-8, invalid known
field, or unsafe path makes the configuration invalid. AeTeX still opens the
project and permits editing, but it reports actionable diagnostics and disables
configuration-dependent workflows. It does not reinterpret the file using
defaults, replace it, repair it silently, or fall back to heuristics as though
the invalid file were absent.

When available from the TOML parser, syntax diagnostics include the source line
and column. The original bytes remain untouched until an explicit user edit or
confirmed migration.

## 14. Invariants

- A project ready for configuration-dependent workflows has exactly one
  confirmed active main document. An unresolved project has none; no project has
  more than one under schema 1.
- Every persisted configuration contains a supported integer `schema`.
- Every stored path is non-empty, relative to the project root, portable, and
  confined to that root after validation.
- The effective `main` is a valid root-document candidate.
- The effective output directory belongs to the project and is resolved from the
  project root. It is a generated subtree and never contains the main document
  or project configuration.
- Explicit valid configuration takes precedence over detection and defaults.
- Shared configuration never stores absolute paths, executable paths, secrets,
  personal preferences, or transient runtime state.
- Invalid or unsupported configuration never prevents the directory from
  opening as a project, but it prevents compilation, preview, and SyncTeX from
  consuming uncertain intent.
- Detection, defaulting, and migration never modify the project file without
  explicit user intent.

## 15. Evolution

The schema and `.aetex` directory leave room for later architecture work on:

- named targets;
- build profiles;
- scripts;
- hooks;
- variables;
- multiple compilations or multiple active buildable documents.

These are reserved capabilities only. Schema 1 defines no syntax, precedence,
execution model, or compatibility promise for them.

## 16. Complete Example

```toml
schema = 1

main = "main.tex"

engine = "xelatex"

strategy = "latexmk"

output = "build"
```

- `schema = 1` selects the first project-configuration contract.
- `main = "main.tex"` confirms the root document relative to the project root.
- `engine = "xelatex"` selects the shared TeX engine intent.
- `strategy = "latexmk"` selects the supported compilation coordinator.
- `output = "build"` places generated artifacts under the project's `build`
  directory.

## 17. IDE creation and initialization

The IDE uses this schema as its only persistent project identity. It does not
create a workspace marker, recent-project metadata inside the project, or a
hidden build-enablement flag.

`New Project...` accepts a project name and parent directory. The destination
may be absent or an existing empty ordinary directory. Existing files,
symbolic links, and non-empty directories are collisions. A successful
creation writes:

```text
<project>/
    .aetex/project.toml
    src/main.tex
```

The generated configuration is the minimal self-contained schema 1 file:

```toml
schema = 1
main = "src/main.tex"
```

Engine, strategy, and output retain the schema 1 effective rules in section 11.
The generated LaTeX document contains an active `\documentclass`, a document
environment, and valid text.

An ordinary opened directory is explicitly unconfigured and offers
`Initialize Project`. Before writing, the IDE lists every path it will create.
If main-document detection identifies one compatible document unambiguously,
initialization records that document without altering it. Otherwise it creates
`src/main.tex` only when that exact path is free. It never overwrites an
existing `project.toml`, source file, or unrelated entry.

Creation and initialization write the configuration last, validate the result,
and reopen it through the canonical `ProjectLoader`. Failure removes only files
and empty directories created by that operation. Configuration publication
uses a complete sibling temporary file followed by a non-replacing move;
temporary files are removed after success or failure.

An existing loaded schema 1 configuration is `AlreadyConfigured`.
An existing malformed, semantically invalid, or unsupported configuration is a
typed conflict and remains byte-for-byte untouched. Build is eligible only
when the loader produces a loaded configuration and a ready effective
configuration with a confirmed main document. The origin of the file—IDE, CLI,
script, or manual editing—has no effect on eligibility.

The blank lines are optional TOML formatting. The example is fully explicit;
the minimal example in section 8 has the same effective strategy and output and
uses engine inference or its `pdflatex` fallback.
