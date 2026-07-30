# AeTeX Architecture 003: Compilation System

## 1. Status

| Field | Value |
| --- | --- |
| Identifier | AeTeX Architecture 003 |
| Title | Compilation System |
| Status | Accepted |
| Date | 2026-07-29 |
| Scope | Build planning, local tool discovery, process execution, concurrency, cancellation, logs, diagnostics, results, output validation, security, and portability |

This document is the normative architecture for AeTeX compilation. It promotes
the accepted direction from
[Architecture Study 003](003-compilation-system-study.md) into an implementation
contract. The study remains intact as historical analysis, but this document
takes precedence wherever the two differ.

The contract starts at the implemented
[`EffectiveProjectConfiguration`](../../src/main/kotlin/dev/aetex/project/configuration/ProjectConfiguration.kt)
defined by [Architecture 002](002-project-configuration-system.md). It does not
change the project-configuration schema and does not implement compilation.

## 2. Motivation

Compilation is an execution subsystem, not a file transformation hidden behind
a UI action. It owns external tools, concurrent process streams, cancellation
races, generated files, long-lived evidence, and platform-specific failures.
Those responsibilities have a different lifecycle and risk profile from project
loading, editing, and rendering, so they require an explicit architecture.

[Project Configuration](002-project-configuration-system.md) defines what the
project intends to build: one confirmed main document, one engine, one strategy,
and one output directory. Compilation consumes that resolved intent. It never
parses `.aetex/project.toml`, repeats main-document detection, infers an engine
from installed tools, or supplies defaults for a configuration that is not
ready.

Future Preview will consume the immutable result and artifact identity of a
specific build session. It must not guess which PDF in an output directory is
current. Future SyncTeX support will likewise associate synchronization data
with the session, main document, and output that produced it. Future Diagnostics
will derive navigable messages from session evidence while retaining the
complete process and tool logs.

## 3. Objectives

The compilation system must:

- compile projects whose effective configuration is ready;
- consume `EffectiveProjectConfiguration` without changing its meaning;
- remain portable across Windows, Linux, and macOS;
- produce the same build plan from the same effective configuration, files,
  tools, and environment;
- execute outside the UI thread and support observable cancellation;
- produce complete logs and typed diagnostics;
- return a stable result that identifies terminal outcome and artifacts;
- permit new typed engines, strategies, discovery providers, and executors
  without turning compilation into an arbitrary command runner;
- remain independently testable from Compose UI state and rendering.

The initial system compiles files as they exist on disk. It does not compile
unsaved editor buffers.

## 4. Principles

### Immutable Snapshot

Every compilation consumes an immutable snapshot of a ready effective
configuration together with the owning project's resolved root.
`EffectiveProjectConfiguration` remains the authority for the confirmed main
document, engine, strategy, and output; the root comes from the same loaded
`TeXProject` because it is not a field of the implemented effective
configuration. `BuildPlanner` copies those values and the relevant environment
into a plan and retains no mutable `TeXProject` reference.

Changes made to the project or its configuration after planning never mutate an
existing `BuildPlan` or a started `BuildSession`. A later request must create a
new plan. Source files remain ordinary filesystem inputs and are read by the
toolchain at execution time. Planning never writes persisted configuration.

### Shell-free execution

AeTeX never executes compilation through a shell. Every process starts through
`ProcessBuilder` with a resolved executable and one argument per semantic
value.

AeTeX never invokes:

```text
cmd /c
bash -c
powershell -Command
```

Rendered command text exists only for display and evidence. It is never parsed
back into the executed argument vector.

### Deterministic Build

Given the same effective configuration, project files, available tools, and
process environment, AeTeX produces the same `BuildPlan`. Discovery precedence,
argument order, working directory, environment adjustments, and expected-file
ordering are deterministic.

Session identifiers and timestamps describe an execution and are therefore not
part of the plan.

### Engine / Strategy Separation

`TeXEngine` represents the TeX semantics selected by the project.
`CompilationStrategy` represents how the engine and supporting tools are
coordinated.

They never represent the same concept. The initial `latexmk` strategy drives
exactly the configured `pdflatex`, `xelatex`, or `lualatex` engine. Missing
`latexmk` or a missing configured engine is an error; AeTeX never changes engine
or silently falls back to direct execution.

### Evidence Preservation

AeTeX never replaces or discards complete logs because structured diagnostics
were extracted. Raw process bytes, decoded stream events, AeTeX lifecycle
events, tool logs that can be attributed to the session, and parsing failures
remain available with the result for their defined retention lifetime.

Diagnostics complement evidence. They do not replace it.

## 5. Architecture

### `CompilationManager`

`CompilationManager` owns runtime policy, not process construction. It:

- accepts immutable plans and cancellation requests;
- creates session identities;
- serializes sessions by output space;
- maintains the single latest queued replacement for an occupied output;
- owns active-session and output-lease lifecycles;
- publishes state transitions and terminal results;
- coordinates project close and application shutdown with cancellation;
- exposes UI-facing projections without depending on Compose.

It does not parse project configuration, discover tools, construct strategy
arguments, parse TeX logs, or expose raw JVM `Process` objects.

### `BuildPlanner`

`BuildPlanner` is a focused coordinator over typed planning services. It:

- requires `EffectiveProjectConfiguration.isReady`;
- accepts the owning resolved project root and snapshots it with the confirmed
  effective values without mutating either object;
- revalidates project, main, and output paths against current filesystem state;
- resolves the exact `TeXEngine` and `CompilationStrategy`;
- discovers required local tools under deterministic precedence;
- asks the selected strategy to produce a fixed invocation;
- captures the exact working directory and environment;
- derives the ordered expected-file set.

Planning either returns one immutable `BuildPlan` or a typed planning failure.
A planning failure creates no session and starts no process.

### `BuildPlan`

`BuildPlan` is the immutable description of one intended build. Its complete
contract is defined in section 7. It has no runtime lifecycle.

### `BuildSession`

`BuildSession` is one concrete attempt to execute exactly one `BuildPlan`. It
owns identity, state, timestamps, log storage, and eventually one terminal
`BuildResult`. While running, it owns its `BuildProcess`.

### `BuildProcess`

`BuildProcess` owns one top-level operating-system process and the process
resources associated with it. It:

- starts the plan through `ProcessBuilder`;
- closes stdin;
- drains stdout and stderr concurrently;
- reports exit and stream events to the session log;
- tracks the top-level process and descendants through the platform cleanup
  provider;
- performs bounded graceful and forced cancellation;
- closes pipes and process-related resources.

The schema 1 `latexmk` strategy produces one top-level process. Child engines
and supporting tools remain descendants of that process. `BuildProcess` does
not decide success, artifact validity, queuing, or UI state.

### `BuildResult`

`BuildResult` is the immutable terminal record of a session. It contains:

- session identity and a reference to its plan;
- terminal state and typed outcome reason;
- primary technical cause and exception detail when available;
- start, finish, and duration;
- process start evidence and exit code when available;
- cancellation origin, request time, escalation, and cleanup outcome;
- a handle to the complete retained logs;
- typed diagnostics;
- an inventory of expected and observed artifacts, including whether each was
  created, changed, reused unchanged, missing, or invalid;
- output-space identity and the quarantine-record identity/status snapshot, if
  one exists at publication.

A zero exit code alone does not make a result successful. `Succeeded` requires
strategy success, no accepted cancellation, completed process cleanup, and
validation of every required artifact.

## 6. Official Flow

```text
EffectiveProjectConfiguration

↓

BuildPlanner

↓

BuildPlan

↓

CompilationManager

↓

BuildSession

↓

BuildProcess

↓

BuildResult
```

The transitions are:

1. Project Configuration publishes a ready `EffectiveProjectConfiguration`.
2. `BuildPlanner` snapshots and validates it, discovers its required tools, and
   converts its typed intent into a deterministic `BuildPlan`.
3. `CompilationManager` accepts the plan, identifies its output space, and
   creates a queued `BuildSession`.
4. When the output lease is available, the manager moves the session to
   `Running` and gives its plan to a new `BuildProcess`.
5. `BuildProcess` executes and reports process evidence without interpreting
   build success.
6. The session combines process evidence, artifact validation, cleanup state,
   logs, and diagnostics into one `BuildResult`.
7. The manager publishes the terminal session and releases the output lease
   only when the cleanup provider proves that the top-level process and every
   tracked descendant have terminated and both process streams reached EOF. If
   that proof is unavailable, the manager quarantines the output space.

UI and application state observe this flow. They do not bypass it.

The four concepts in this flow are distinct. Compilation intent is the
transient request to build one owning project root with its ready effective
configuration; it contains no executable command. The effective configuration
is resolved project meaning. `BuildPlan` is the fully resolved executable
snapshot. `BuildSession` is one timestamped attempt to execute that plan.

## 7. Build Plan

A `BuildPlan` contains only the following five immutable components. Their
values are fully resolved; they are structured values, not command text:

- **what to execute:** a resolved invocation descriptor containing the exact
  canonical `latexmk` executable, the exact canonical configured-engine
  executable, their discovery provenance, the effective `TeXEngine` and
  `CompilationStrategy`, the `ConfigurationValueSource` for each effective
  value where Architecture 002 exposes one, the confirmed canonical main
  document, the normalized configured output directory, and the output-space
  identity;
- **arguments:** the ordered argument vector, with each semantic value in a
  separate element;
- **directory:** the absolute, normalized project root used as the working
  directory;
- **environment:** a deep immutable copy of the complete environment map after
  deterministic `PATH` sanitization, plus the named output charset captured for
  the process;
- **expected files:** the output-space descriptor and an ordered set of
  absolute, confined paths with artifact role and required/optional
  classification.

The plan contains no session identifier, mutable configuration object, process
handle, state, timestamp, logs, exit code, diagnostics, or result. It never
represents an execution and can be inspected without starting a process.
A stable plan fingerprint is derived from a canonical serialization of these
five components; it contains no random or temporal value and provides
correlation without adding mutable identity. Canonical serialization sorts
environment keys using the host platform's environment-key semantics while the
argument and expected-file orders remain significant.

For schema 1, the executable is the resolved `latexmk`; the arguments select the
configured engine, non-interactive execution, controlled initialization,
configured output directory, and confirmed main document. The main document is
passed as one safe path argument. The working directory is always the project
root, including when the main document is nested.

The required primary artifact is exactly the configured output directory
resolved with the main document basename, its final `.tex` extension removed
case-insensitively, and `.pdf` appended. Schema 1 supplies no job-name override.
TeX logs, SyncTeX data, and known auxiliary files with the same build basename
may be declared optional evidence.

`BuildProcess` receives only the plan and uses its executable, arguments,
directory, and copied environment verbatim. It never rereads
`EffectiveProjectConfiguration`, searches for tools, consults live preferences
or process environment, parses metadata out of arguments, or reconstructs the
argument vector. Sensitive environment values remain internal to the plan and
are redacted only in rendered evidence; redaction never changes the environment
used for execution.

## 8. Build Session

A `BuildSession` represents one attempt to execute one plan. It contains:

- a globally unique, opaque identifier;
- a reference to exactly one immutable `BuildPlan`;
- exactly one official state;
- creation, queue, start, cancellation-request, and finish timestamps when
  applicable;
- an append-only complete log;
- at most one active `BuildProcess`;
- no result before termination and exactly one immutable `BuildResult` after
  termination.

The session is the correlation boundary for processes, events, diagnostics, and
artifacts. A session never switches to a different plan and is never reused for
a rebuild. Its initial state is `Queued`, assigned atomically when
`CompilationManager` accepts the plan.

## 9. States

The official state model is:

| State | Meaning |
| --- | --- |
| `Idle` | Manager projection for an output space with no active or queued session. It is not a state of a concrete session. |
| `Queued` | The session was accepted but has not started a process. It is waiting for its output lease or immediate dispatch. |
| `Running` | Process startup has begun and the session owns the output lease. |
| `Cancelling` | Cancellation was accepted for a running session and process cleanup is in progress. |
| `Succeeded` | Terminal state: strategy, cleanup, and required-artifact validation succeeded. |
| `Failed` | Terminal state: planning was already complete, but startup, execution, timeout, cleanup, internal I/O, or artifact validation failed. |
| `Cancelled` | Terminal state: cancellation was accepted and bounded cleanup completed without a remaining writer. |

Valid session transitions are:

```text
Queued -> Running -> Succeeded
Queued -> Running -> Failed
Queued -> Running -> Cancelling -> Cancelled
Queued -> Running -> Cancelling -> Failed
Queued -> Cancelled
```

Cancellation accepted before terminal publication wins over a simultaneous
normal exit. Cancellation after terminal publication has no effect. A process
start failure occurs after transition to `Running` and terminates as `Failed`.

Planning and discovery failures occur before session creation and therefore
produce no session state. A pre-start revalidation or `ProcessBuilder.start`
failure occurs after dispatch to `Running` and terminates as `Failed`. The
terminal states are `Succeeded`, `Failed`, and `Cancelled`; every transition not
listed above is prohibited.

If cleanup cannot prove that every writer terminated, the session terminates as
`Failed` with incomplete cleanup and the output space enters quarantine. State
is never represented by dispersed booleans such as `isRunning`, `isFailed`, and
`isCancelled`.

`Idle` is only the manager's activity projection for an available output space
with no active or queued session. Output availability is a separate closed
model: `Available`, `Leased(sessionId)`, or `Quarantined(record)`. A quarantined
space is never reported as available merely because its session is terminal.

## 10. Discovery

Discovery runs once during `BuildPlanner`, after readiness and path validation
and before a `BuildPlan` exists. It consumes only the engine and strategy from a
ready effective configuration. It does not inspect source packages, choose
another engine, reinterpret configuration, or invoke a shell.

The initial discovery contract is:

1. Resolve `CompilationStrategy.LATEXMK` to `latexmk`.
2. Resolve the configured `TeXEngine` to exactly `pdflatex`, `xelatex`, or
   `lualatex`.
3. Deep-copy the inherited environment and split its `PATH` with the host
   platform path separator, preserving entry order. On Windows the environment
   key is matched case-insensitively; on Linux and macOS it is exactly `PATH`.
   A missing `PATH` is an empty search path.
4. Reject empty, relative, nonexistent, inaccessible, non-directory, and
   project-contained directory entries. Normalize each absolute entry, resolve
   it to real filesystem identity, and de-duplicate that identity; the first
   occurrence wins.
5. On Windows, test only the exact native name `<tool>.exe`. Do not consult
   `PATHEXT`, and never accept `.bat`, `.cmd`, `.com`, or an extensionless
   candidate. On Linux and macOS, test only the exact extensionless tool name.
6. Reject a missing path, directory, broken link, non-regular final target, or
   target whose real path is project-contained. A symbolic-link candidate or
   symbolic-link `PATH` directory is allowed only when its complete real target
   resolves to an accepted external regular file/directory.
7. On Linux and macOS require executable permission. On Windows, regular
   `.exe` identity is the direct-execution criterion because POSIX executable
   permission is not available.
8. The first accepted candidate in validated `PATH` order wins. Later matches
   are not ambiguity and never replace it.
9. Record the tool kind, canonical absolute final path, discovery source,
   selected `PATH` position, and rejected-candidate diagnostics. Put both the
   selected coordinator and selected engine records in the resolved invocation
   descriptor.

The effective plan environment removes unsafe `PATH` entries and places the
configured engine's directory first, followed by the coordinator directory and
then the remaining validated entries in original order. The coordinator is
invoked by absolute path, and this ordering makes `latexmk` resolve the engine
selected by AeTeX. Canonically duplicate directories remain removed. All other
inherited variables and values are copied unchanged; schema 1 provides no
project environment overrides. The same environment snapshot is used for
discovery and execution. On Windows, all case variants of the inherited `PATH`
key are replaced by one canonical `PATH` entry in the plan.

Platform discovery providers may later add conventional installation sources,
but they must return the same typed evidence and define a deterministic
precedence before use. Shared project configuration never stores executable
paths. Explicit machine-local tool configuration is not supported initially; a
future provider requires a separate local-configuration decision and defined
precedence before it can participate.

Rejected or unreadable individual entries are recorded and the ordered search
continues. They do not fail planning if a later acceptable candidate exists.
Unknown engines, unsupported strategies, or failure to find an acceptable
required tool are typed planning errors. They identify the exact required tool
and rejected search evidence and produce no `BuildPlan`, no `BuildSession`, and
no process. Initial discovery is filesystem-only. Any future version probe must
use a bounded structured `ProcessBuilder` invocation without a shell and cannot
change the selected candidate.

The canonical paths selected here are final plan values. A session never
searches `PATH` or resolves either tool again.

## 11. Execution

`BuildProcess` constructs `ProcessBuilder` directly from the plan's executable,
arguments, directory, and environment. It never invokes a shell and never
reconstructs arguments from rendered command text. The builder environment is
replaced with the plan's copied map; it does not additionally inherit the
environment that happens to exist at session start.

The initial `latexmk` invocation:

- is non-interactive;
- disables automatic system, user, and project initialization files;
- selects the configured engine explicitly;
- never enables unrestricted shell escape;
- directs generated files to the effective output directory;
- receives the confirmed main document as one path argument.

AeTeX closes process stdin immediately. It does not provide an interactive
terminal and does not answer TeX prompts.

Stdout and stderr remain separate and are drained concurrently from process
start until EOF. Each captured chunk retains stream identity and raw bytes.
Decoding uses a platform-aware charset selected once for the process; malformed
input produces marked replacement text without losing the original bytes.

The exit code is recorded when available:

- zero is strategy success evidence, subject to artifact and cleanup validation;
- non-zero produces `Failed` with the exit code and complete evidence;
- failure to start produces `Failed` without an exit code.

Only `CompilationManager` accepts cancellation. A request has a typed origin:
explicit user action, latest-request replacement, project close, application
shutdown, or execution deadline. Cancellation of a `Queued` session transitions
directly to `Cancelled`, records its origin and time, produces a terminal result
without a process or exit code, and does not acquire or release an output lease.

For a `Running` session, cancellation:

1. records the accepted request and enters `Cancelling`;
2. prevents success publication;
3. requests graceful termination of the coordinator and all descendants known
   to the platform cleanup provider;
4. continues draining both streams during a bounded grace period;
5. forcibly terminates remaining tracked processes;
6. drains and closes resources during a second bounded wait;
7. records exit codes when observed, the cancellation origin, graceful and
   forced actions, remaining process identities, stream completion, and the
   cleanup verdict.

Cancellation is requested when the manager accepts it; it is completed only
when the top-level process and every tracked descendant are proven terminated,
stdout and stderr reached EOF, and process resources are closed. Only then does
the session become `Cancelled` and release its output lease. Partial stdout,
stderr, and lifecycle evidence remains in the session log throughout
cancellation.

If the process has exited but terminal publication has not occurred, an
accepted cancellation still follows this protocol and wins the terminal race.
If a terminal result was already published, the request is rejected as a
no-op and is not retroactively added to the session.

The two waits are finite execution-policy constants, shared by all sessions and
covered by platform tests; they are not project configuration. Ordinary builds
have no universal execution timeout. The executor supports an optional
caller-owned deadline; expiration uses the same cleanup protocol and terminates
as `Failed` with a timeout reason.

## 12. Concurrency

AeTeX permits at most one active compilation for one output space. Output-space
identity is derived from the configured absolute output path and the real
filesystem identity of its nearest existing ancestor. This rule applies across
projects if two plans resolve to the same space.

Different isolated output spaces may execute concurrently within application
resource limits. A strategy's own internal parallelism remains part of that
single session.

When another plan arrives for an occupied output:

- the manager atomically creates the one queued latest-request session or
  replaces the session already occupying that queued slot;
- every replaced queued session becomes `Cancelled` with replacement as its
  cause and never starts a process;
- the running session receives exactly one cancellation request with replacement
  as its origin;
- no replacement process starts until the prior session's processes have
  actually terminated, both streams reached EOF, resources are closed, and the
  output lease is safe;
- additional requests replace only the queued slot and do not add cancellation
  protocols for the already-cancelling session.

Only a request that successfully produced a `BuildPlan` participates in this
policy. A readiness, validation, or discovery failure never cancels the active
session and never replaces the queued slot.

This latest-request policy keeps rebuild behavior current while preserving
output serialization. For `A: Running` followed by requests B, C, and D for the
same output, A enters `Cancelling`, B is cancelled when C arrives, C is
cancelled when D arrives, and only D remains `Queued`. D starts only after A
releases the output safely. No pair may overlap.

The exclusion key is the output-space identity alone, not
`project + output`. Two projects resolving to the same output space contend for
the same lease. Planning may proceed for a leased or quarantined output, but
process start, cleaning, and every AeTeX write to that output are prohibited.

### Output quarantine

Quarantine is an output-availability state owned by `CompilationManager`, not a
`BuildSession` state. A `QuarantineRecord` identifies the affected space using
the planned normalized absolute output path, nearest-existing-ancestor real
identity, final output real identity when created, project root, responsible
session, recorded coordinator/descendant process identities and start tokens,
cause, creation time, responsible result identity when available, and retained
log reference.

The manager enters quarantine whenever process cleanup is incomplete or
uncertain, including a process that survives graceful and forced termination,
a lost or unverifiable process handle, stream cleanup that cannot reach EOF, or
an abnormal application exit with an unreleased output lease. The responsible
session is `Failed`; quarantine does not change that terminal result.

While quarantined:

- no session may enter `Running` for that output;
- the latest request may remain `Queued`, and newer requests continue replacing
  that queued slot;
- AeTeX may not clean, create, delete, or modify files in the output;
- read-only inspection of logs, results, diagnostics, process evidence, and
  existing artifacts remains available;
- the UI exposes the cause, responsible session, known process identities,
  blocked operation, and a recovery/recheck action.

Before starting a process, the manager persists an application-owned output
lease record outside the project. Clean terminal completion removes it.
Incomplete cleanup converts it to a durable quarantine record. An abnormal exit
therefore leaves a record that is loaded before any new build dispatch after
restart. This operational safety state is not shared project configuration and
is never written to `.aetex/project.toml`.

On restart, an unreleased lease is converted to quarantine before recovery is
attempted. AeTeX does not reactivate the old session. When its retained session
metadata and log are available, it publishes a recovered immutable `Failed`
result with `ABNORMAL_APPLICATION_TERMINATION`; otherwise the quarantine record
itself carries a typed recovery diagnostic and the available log reference.

Quarantine is lifted automatically only after the platform cleanup provider
proves that every recorded process identity from the same operating-system boot
has terminated, no unresolved descendant uncertainty remains, and section 15
path, directory, symlink, confinement, and output-identity validations all
succeed. The same proof is run when the user requests recheck. After restart,
AeTeX performs it before dispatch; a changed operating-system boot identity
proves prior processes cannot still be alive, but path revalidation is still
required.

If process identity is unavailable or ambiguous, AeTeX keeps the space
quarantined and reports the exact unresolved evidence. It never offers a force
release while a prior writer may still exist. Recovery remains possible by
terminating the reported processes and rechecking or by rebooting and reopening
the project. Work may continue independently by configuring a different
isolated output, but that does not clear the old quarantine. Once verification
succeeds, the record is removed, the space becomes `Available`, and the current
latest queued session may start. If the output was safely removed or recreated
after all recorded processes terminated, revalidation records its new safe
identity rather than requiring the obsolete identity to reappear. Quarantine is
therefore persistent across AeTeX restarts but never permanent without a
defined recheck path.

## 13. Logs

Every session has one complete append-only log stored in application-owned
storage outside the project and outside the configured output. It is not shared
project metadata. A bounded or indexed in-memory projection may support live UI,
but the backing log is never truncated to match that projection.

Each event contains:

- session identifier;
- monotonically increasing session sequence;
- wall-clock timestamp and monotonic elapsed time;
- origin: AeTeX lifecycle, coordinator stdout, coordinator stderr, process
  cleanup, or attributed tool file;
- raw bytes or a stable reference to them when the origin is external output;
- decoded text and decoding status when text is available;
- severity or typed payload when AeTeX knows one.

Sequence defines the observed AeTeX order. Order within stdout and within stderr
is preserved. Interleaving between the two streams is observational and never
claims the exact order in which the child process wrote.

Raw capture is chunk-based, so a chunk may contain part of a line or several
lines. A streaming decoder preserves multibyte state across chunks. A derived
line view buffers an incomplete final line and emits it at line termination or
EOF; it never rewrites the raw chunks used as evidence.

The complete log also records the executable, argument vector, working
directory, selected tool evidence, start and finish times, exit code,
cancellation, cleanup, and artifact validation. Environment values that may
contain secrets are redacted from rendered evidence; execution still uses the
exact immutable environment in the plan.

All session logs are retained for the lifetime of the opened project. A
`BuildResult` is never retained without its accessible complete log. Project
close releases non-quarantined result history and its application-owned log
storage together. Normal startup may remove orphaned temporary logs from prior
abnormal exits only when no active lease or quarantine record references them.
Diagnostic extraction, UI tail limits, success, failure, and cancellation never
shorten retention.

Complete logs are file-backed and are not subject to the bounded live-view
limit. Application-owned storage has a finite local quota defined by execution
policy. Reaching the quota or any backing-store write failure triggers the same
process cleanup protocol and terminates the session as `Failed` with
`LOG_STORAGE_FAILURE`; all bytes captured before the failure remain retained,
and no captured evidence is silently truncated. Logs referenced by an active
lease or quarantine record are never removed as orphaned storage.

Future Diagnostics consumes the raw evidence and attributed tool logs through
the session identity. Future Preview consumes build lifecycle and result
identity and may expose the same session log, but it never parses console text
to choose a PDF.

## 14. Diagnostics

Compilation diagnostics are typed values in the compilation domain. A
diagnostic contains:

- stable kind and severity;
- normalized user-facing message;
- session identity, or planning-request identity for pre-session failures;
- originating tool or AeTeX subsystem;
- project-confined source path and optional line and column when reliable;
- related event sequence or tool-log location;
- confidence/completeness marker;
- technical detail suitable for local evidence, not normal UI text.

Diagnostics are extracted conservatively from:

- discovery and process-start failures;
- live stdout and stderr evidence;
- TeX and supporting logs proven created or changed by the current session;
- exit, cancellation, timeout, cleanup, and artifact-validation outcomes.

Unchanged tool logs are not treated as new-session diagnostics merely because
they have an expected filename. Unknown messages remain in raw logs. A parser
failure adds its own diagnostic and never removes evidence or changes an
otherwise valid artifact result.

Compilation diagnostics are separate from
`ProjectConfigurationDiagnostic`. A future presentation layer may display both
through a common UI projection, but their ownership, lifetime, and codes remain
independent.

## 15. Output

Compilation uses exactly
`EffectiveProjectConfiguration.outputDirectory.value`. It never substitutes a
default during planning, resolves output relative to the main document, or
relocates a build to a per-session directory.

Immediately before process start, AeTeX:

- revalidates the project root and confirmed main;
- verifies lexical and real-path confinement of the output;
- rejects symbolic-link escapes and non-directory existing output paths;
- validates each existing ancestor;
- creates missing output directories one segment at a time with containment
  checks;
- snapshots the pre-build state of expected artifacts;
- verifies that metadata and source paths do not overlap output.

These checks use only the paths stored in the plan; they do not load
configuration again or change the plan. The main path may contain newly saved
content or a safely replaced regular file at the same planned path; AeTeX
revalidates it and compiles its current on-disk content. If the main path is
removed, relocated, unreadable, non-regular, unsafe, or resolves outside the
project, validation fails. If the output no longer matches the planned
output-space identity, type, confinement, or symlink safety, validation also
fails. In either failure the dispatched session enters `Running` but fails
before `ProcessBuilder.start`, starts no process, and retains the output lease
only for the validation operation.

If the output did not exist during planning, its output-space identity uses the
nearest existing ancestor. At dispatch AeTeX revalidates that same ancestor,
creates missing directories one segment at a time, rejects any intervening
symlink or file, and records the new final real identity before process start.
If a safe output directory cannot be created, the session becomes `Failed`.

Configuration changes after plan creation are ignored by that plan. Source
content changes at the same validated paths are ordinary filesystem inputs and
the build compiles the content present when the tools read it. An invalid main
path or a changed/invalid output-space identity fails pre-start validation and
requires a new plan.

After process termination, every required artifact must be a confined, readable
regular file. The result records whether it was created, modified, or
successfully reused unchanged by a zero-exit `latexmk` no-op. A stale artifact
does not turn a failed or cancelled process into success.

The primary PDF is the exact required PDF path defined in section 7. A zero exit
code with that PDF missing, outside output, unreadable, non-regular, or resolving
through an unsafe link is `Failed`. The result inventory explicitly separates
observed artifacts from required artifacts that are missing or invalid.

The configured output is shared across sequential sessions so `latexmk` may
reuse auxiliaries. AeTeX does not:

- delete output before an ordinary build;
- delete output after failure or cancellation;
- recursively clean the output;
- treat cleanup as an automatic recovery action;
- add generated artifacts to the static editable project tree.

Cleaning requires a future explicit operation with its own containment and
ownership contract.

## 16. Security

Opening a project never executes it. Compilation occurs only after an explicit
user action. Future automatic compilation requires a separate trust decision.

AeTeX executes only known strategy-owned programs selected from typed
configuration. It never accepts an executable path, command string, argument
fragment, hook, script, or environment override from schema 1 project
configuration.

Process security rules are:

- no shell;
- absolute validated executable paths;
- structured fixed arguments;
- safe path operands that cannot become tool options;
- project-root working directory;
- sanitized `PATH` without relative, empty, or project-controlled entries;
- no unrestricted shell escape;
- no automatic `latexmk` system, user, or project initialization;
- no recursive cleanup;
- complete command and process evidence.

The initial environment policy is exact: snapshot the application's inherited
environment during planning, remove unsafe or duplicate `PATH` entries, replace
`PATH` with the deterministic value from section 10, and otherwise preserve
variables and values unchanged. AeTeX accepts no project-supplied environment
overlay. The complete map is copied into the plan; sensitive values are never
rendered without redaction.

These controls prevent command-string injection and accidental tool shadowing.
They do not sandbox TeX. TeX source is active content and the toolchain runs with
the AeTeX user's operating-system permissions. Projects from unknown sources
must therefore be treated as untrusted executable input, and AeTeX must not
describe local compilation as safe merely because no shell is used.

The strategy always disables automatic `latexmk` system, user, and project
initialization. A detected project `latexmkrc` or `.latexmkrc` is ignored and
produces a visible warning before execution. AeTeX does not add unrestricted
shell-escape flags. When tool evidence shows that compilation requires an
ignored initialization file, unrestricted shell escape, or an arbitrary
script, the session fails with a typed unsupported-dangerous-capability
diagnostic; AeTeX never enables the capability silently.

Any future custom command, initialization file, shell escape, hook, or automatic
build capability requires an explicit security and trust architecture.

## 17. Portability

All platforms share the same plan, session, state, result, diagnostic, and
artifact semantics. Only executable discovery, console decoding, and
process-tree cleanup may use platform adapters.

`PATH` is split using the host platform separator supplied by the JVM, never a
hard-coded `:` or `;`. Filesystem identity and canonical paths govern
de-duplication and confinement; display case and raw path-string equality do not.
The output charset name is captured in the resolved invocation descriptor:
UTF-8 on Linux and macOS, and the JVM default charset captured at planning time
on Windows. Raw bytes remain authoritative on every platform.

### Windows

- Use `Path`/`Files` for drive and UNC-aware path handling.
- Resolve only directly executable native tool candidates; `.bat` and `.cmd`
  candidates do not satisfy shell-free discovery.
- Accept only the exact `.exe` names defined in section 10; do not depend on
  `PATHEXT` shell behavior or `.com` fallback.
- Preserve raw console bytes and use the selected Windows decoding provider.
- Treat locked outputs and incomplete descendant termination as typed failures,
  never as reasons to overlap builds.
- Compare canonical filesystem identity using Windows filesystem semantics
  rather than case-sensitive strings.

### Linux

- Require absolute executable paths and executable permission.
- Preserve case-sensitive path and tool identity.
- Use JVM process handles for staged descendant cleanup, with tested
  platform-specific strengthening only behind the cleanup contract.
- Do not assume every distribution installs `latexmk` with an engine.

### macOS

- Do not source an interactive shell to repair a GUI application's `PATH`.
- Report unavailable tools when the inherited validated environment cannot
  resolve them.
- Permit later MacTeX discovery through the common provider contract.
- Treat case preservation, application packaging, and process cleanup as
  platform capabilities verified by tests.

Across all platforms, paths containing spaces, non-ASCII characters, and
leading hyphens remain single structured arguments. Path comparison uses
filesystem-aware validation rather than string concatenation or shell quoting.
The cleanup provider must report whether top-level and descendant termination
is proven. Platform signal, process-group, or job-object mechanisms may differ,
but an uncertain result has the same normative outcome: `Failed` plus
quarantine. Portability never weakens the no-overlap invariant.

## 18. Compatibility

Compatibility failures are explicit and recoverable:

| Condition | Required behavior |
| --- | --- |
| Configuration absent, invalid, unsupported, or provisional | Planning fails because `isReady` is false; editing remains available. |
| `latexmk` absent | Typed unavailable-tool planning failure; no direct-engine fallback. |
| Configured engine absent | Typed unavailable-tool planning failure; no engine substitution. |
| Unknown engine | Typed planning failure, even if defensive validation encounters a value outside the current enum. |
| Unsupported strategy | Typed planning failure; no generic command interpretation. |
| Unsafe or stale path | Typed planning failure before process start, or `Failed` if the path changes after session creation. |
| Process cannot start | Session becomes `Failed` without an exit code. |
| Process exits non-zero | Session becomes `Failed` with exit and complete logs. |
| Process exits zero but required output is invalid or missing | Session becomes `Failed` with artifact diagnostics. |
| Cancellation completes | Session becomes `Cancelled`. |
| Cancellation or normal cleanup is incomplete or uncertain | Session becomes `Failed`, a durable quarantine record is created, and no process may start on that output until verified recovery. |
| Diagnostic parsing is partial | Raw evidence remains available; parsing incompleteness is reported without inventing diagnostics. |

Missing compilation capability never prevents project opening, scanning,
editing, or saving.

## 19. Invariants

- `BuildPlan` is immutable.
- `BuildPlan` contains only a resolved invocation descriptor, arguments,
  working directory, environment/charset snapshot, and expected files.
- Both coordinator and configured-engine executable paths are canonical,
  absolute, and fully resolved in the plan.
- A `BuildSession` references exactly one `BuildPlan`.
- A `BuildSession` has exactly one official state at a time.
- A terminal session has exactly one immutable `BuildResult`.
- No two live processes may write concurrently to the same output space.
- An output lease is not released while process termination is unproven.
- A quarantined output cannot start a build or be modified by AeTeX.
- No compilation process is executed through a shell.
- Engine and strategy remain distinct typed values.
- A configured engine or strategy is never silently substituted.
- A session never mutates or rereads `EffectiveProjectConfiguration` to change
  its plan.
- A session never repeats tool discovery or reconstructs plan arguments.
- Diagnostics never replace complete logs.
- Ordinary compilation never recursively cleans output.

## 20. Evolution

The architecture reserves bounded future work for:

- watch mode;
- parallel compilations over proven isolated output spaces;
- multiple named targets;
- an explicit cleaning operation;
- verified build caching;
- remote or isolated compilation.

These capabilities are not designed here. They must preserve or explicitly
revise the plan/session/result contract, output ownership, security model, and
configuration semantics before implementation.

## 21. Example

A user opens a configured project whose confirmed main document is
`src/thesis.tex`, whose engine is `xelatex`, whose strategy is `latexmk`, and
whose output is `build`. Project loading produces a ready
`EffectiveProjectConfiguration`.

The user requests a build. `BuildPlanner` snapshots those values, revalidates
the main and output paths, resolves `latexmk` and `xelatex` from the validated
environment, and produces an immutable plan. The plan names the absolute
`latexmk` executable, its structured arguments, the project root as working
directory, the exact environment, and `build/thesis.pdf` as required output.

`CompilationManager` creates a queued session. Because no other session owns
that output, it transitions to running. `BuildProcess` starts `latexmk` directly
through `ProcessBuilder`, closes stdin, and drains stdout and stderr into the
append-only session log while `latexmk` coordinates `xelatex` and supporting
tools.

The process exits with code zero. AeTeX finishes draining streams, confirms
that no descendant remains, validates the expected PDF, inventories the TeX log
and any SyncTeX data, and extracts conservative diagnostics without removing
raw evidence. The session publishes one `Succeeded` result that future Preview
can use to load exactly that PDF.

If the user requests another build while the first is running, the manager
keeps only the latest request, cancels the active session, waits for complete
cleanup, and only then starts the replacement against the same output. The
cancelled session retains its complete log and terminal result for the lifetime
of the opened project.
