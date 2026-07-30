# AeTeX Architecture Study 003: Compilation System

| Field | Value |
| --- | --- |
| Identifier | AeTeX Architecture Study 003 |
| Status | Study |
| Date | 2026-07-29 |
| Scope | Local tool discovery, build orchestration, process lifecycle, results, logs, diagnostics, output, concurrency, security, and portability |

This document studies architecture alternatives for the AeTeX compilation
system. It is intentionally not an accepted architecture decision and does not
specify implementation APIs.

The question is:

> How should AeTeX design its compilation system so that it is portable,
> extensible, and reliable?

The recommendation at the end identifies decisions that should be promoted into
a future accepted Architecture 003 after review. It does not make those
decisions normative.

## Repository Baseline and Constraints

The product direction comes from [Architecture 000](000-vision.md): AeTeX is a
local desktop IDE that integrates established TeX tools instead of replacing
them. Compilation must remain inspectable, cancellable, and outside the UI
thread. Missing tools must produce an explicit capability state, and projects
must remain usable for unaffected workflows.

[Architecture 001](001-project-model.md) and the
[decision log](../decisions/decision-log.md) establish further constraints:

- a project directory is the unit of work;
- `Path` is the internal filesystem representation;
- filesystem mechanisms, workflow coordination, state, and UI have separate
  responsibilities;
- project access is confined through normalized and real-path checks;
- symbolic links are visible but not recursively followed;
- user intent is required for destructive behavior;
- preview depends on a compilation result with identifiable output.

[Architecture 002](002-project-configuration-system.md) is the normative input
contract for compilation. Its implemented schema 1 provides:

- one confirmed main document;
- an effective `TeXEngine` of `pdflatex`, `xelatex`, or `lualatex`;
- an effective `CompilationStrategy`, currently only `latexmk`;
- an effective output directory;
- explicit, inferred, and default value provenance;
- a readiness rule that rejects absent, invalid, unsupported, or provisional
  configuration for configuration-dependent workflows.

The implementation reflects that contract:

- [`ProjectConfiguration`](../../src/main/kotlin/dev/aetex/project/configuration/ProjectConfiguration.kt)
  keeps stored paths relative and typed values separate;
- `EffectiveProjectConfiguration`, in the same source file, exposes the resolved
  absolute output directory and `isReady`;
- [`ProjectLoader`](../../src/main/kotlin/dev/aetex/project/ProjectLoader.kt)
  applies `pdflatex`, `latexmk`, and `build` defaults without writing them;
- the configured output is excluded from project scanning and editing;
- [`ProjectConfigurationLoader`](../../src/main/kotlin/dev/aetex/project/configuration/ProjectConfigurationLoader.kt)
  validates shared values and output confinement before effective resolution;
- invalid configuration leaves the project editable through
  [`AeTeXState`](../../src/main/kotlin/dev/aetex/app/AeTeXState.kt) but removes
  uncertain effective build values;
- configuration diagnostics are typed and kept separate from technical
  exception detail.

This means compilation should consume effective configuration. It should not
parse `.aetex/project.toml` again, rerun main-document heuristics, infer an
engine from installed programs, or reinterpret an invalid configuration.

There are two documentation differences worth carrying forward without
changing earlier RFCs in this study:

- the introductory text in Architecture 002 still says the contract is not
  implemented, while the repository and roadmap show that its loading,
  validation, defaults, and non-interactive resolution are implemented;
- Architecture 001 describes the pre-configuration baseline, so its statements
  that `.aetex` is not excluded and that configuration is absent have been
  superseded by the later implementation.

The [roadmap](../roadmap/roadmap.md) defines Milestone 2 as asynchronous,
observable local compilation with cancellation, process cleanup, complete
logs, stable results, initial diagnostics, and explicit failure modes.

## 1. Objectives

The compilation system should:

- compile the confirmed main document of a ready project;
- consume the effective engine, strategy, and output from Project
  Configuration without changing their meaning;
- discover whether the required local tools are available and explain when
  they are not;
- support `pdflatex`, `xelatex`, and `lualatex` as engine choices under the
  existing schema;
- support `latexmk` as the first configured strategy;
- run outside the Compose UI thread and publish understandable progress;
- support cancellation and bounded cleanup of process trees;
- capture the invoked program, arguments, working directory, environment
  policy, output streams, exit status, duration, and produced artifacts;
- preserve complete raw evidence while also producing useful typed
  diagnostics;
- create a stable build result that future PDF preview and SyncTeX work can
  associate with the exact project, main document, engine, strategy, output,
  and run;
- serialize or isolate writes so concurrent builds cannot corrupt one output;
- behave predictably on Windows, Linux, and macOS;
- fail in typed, recoverable states for invalid readiness, unavailable tools,
  start failures, non-zero exits, cancellation, timeout, cleanup failure, and
  missing expected output;
- keep the mechanism independently testable from Compose rendering.

Reliability here does not mean that every LaTeX project must compile. It means
that AeTeX reports what it attempted, does not confuse one run with another,
does not block the UI, does not leave avoidable processes behind, and does not
claim success without sufficient evidence.

## 2. Non-Objectives

The initial compilation architecture should not solve:

- document editing or unsaved-change policy;
- PDF rendering, PDF lifecycle, or preview UI;
- SyncTeX forward or inverse navigation;
- autocompletion, parsing for editing, or semantic indexing;
- intelligent bibliography authoring or citation management;
- replacement of `latexmk`, TeX engines, BibTeX, Biber, or a TeX distribution;
- a general shell, terminal, task runner, or build language;
- arbitrary project scripts, hooks, variables, or custom command execution;
- multiple targets, because schema 1 has one active main document;
- remote, containerized, or cloud compilation;
- a cross-platform security sandbox for TeX;
- automatic installation or updating of a TeX distribution;
- a cache or watch mode;
- guaranteed interpretation of every TeX log construct.

Bibliography execution may occur as part of the selected `latexmk` strategy.
That is delegated orchestration, not intelligent bibliography support.

## 3. Engine Versus Strategy

An engine and a strategy answer different questions.

### Engine

The engine is the program that interprets the TeX source and produces a format
artifact:

- `pdflatex`;
- `xelatex`;
- `lualatex`.

Engine choice changes document semantics and compatibility. It affects font
handling, Unicode behavior, available primitives, package behavior, and the
format of an otherwise similar compilation. It is shared project intent and is
therefore represented by `TeXEngine` in Architecture 002.

An engine value is not:

- an executable path;
- a shell command;
- the number of passes to run;
- a bibliography processor;
- a complete build pipeline.

### Strategy

The strategy coordinates how the project reaches a stable result. Examples
include:

- `latexmk`, which observes dependencies and reruns tools as needed;
- direct engine execution, which needs some other policy for passes,
  bibliography, indexes, and convergence;
- a future pipeline that coordinates several known tools;
- a future task-system integration, if separately designed and trusted.

Strategy choice determines orchestration, termination conditions, dependency
handling, and which supporting tools may execute. It does not determine TeX
language semantics by itself. `latexmk` still needs to know which engine it
should drive.

### Why a Single Abstraction Is Insufficient

Treating `pdflatex` and `latexmk` as peers in one "compiler" enum creates
ambiguity:

- selecting `latexmk` would not state which engine it drives;
- selecting `pdflatex` would not state whether it is run once or until
  references converge;
- logs and missing-tool errors could not distinguish coordinator failure from
  engine failure;
- future strategies would duplicate engine variants, such as
  `latexmk-xelatex`, `direct-xelatex`, and `pipeline-xelatex`;
- tool discovery would mix a required orchestrator with a semantic engine.

The reverse mistake is to hide engine choice inside a `latexmk` strategy. That
would discard an existing shared configuration invariant and could compile a
project with different semantics on different machines.

### Conclusion

Engine and strategy should remain separate typed concepts. A build request
combines one effective engine with one effective strategy. The strategy
translates that combination into a process plan. Tool discovery resolves typed
tool requirements to machine-local executables without changing either
configured value.

This conclusion is already strongly constrained by Architecture 002 and should
be promoted into Architecture 003.

## 4. Compilation Strategies

### Alternative A: `latexmk`

`latexmk` acts as the coordinator and invokes the selected engine and supporting
tools until its dependency rules consider the output current.

Advantages:

- delegates reference reruns and bibliography/index coordination to a mature
  ecosystem tool;
- handles common multi-pass projects better than a fixed pass count;
- reduces AeTeX-specific orchestration and maintenance;
- usually avoids unnecessary work when outputs are already current;
- exposes one top-level process to observe and a conventional log ecosystem;
- matches the schema 1 default and only accepted strategy;
- follows Architecture 000's preference for integration over reinvention.

Disadvantages:

- adds a required executable beyond the engine;
- versions and TeX distributions may differ in behavior and available options;
- it can launch child and descendant processes that complicate cancellation;
- coordinator output can obscure which underlying tool caused a failure;
- user or project initialization files can alter behavior and may execute code;
- some projects depend on bespoke Makefiles or scripts that `latexmk` does not
  represent;
- `latexmk` success still does not prove that the expected PDF belongs to this
  run unless AeTeX validates artifacts.

Portability:

`latexmk` is widely used across major TeX distributions, but presence cannot be
assumed. The invocation must use a resolved executable and argument vector, not
a shell command string. Distribution and platform differences still require
capability reporting and integration tests.

Speed:

It can reuse auxiliary files and avoid redundant passes. Startup overhead is
usually smaller than the cost of AeTeX reimplementing an incomplete dependency
algorithm. Clean builds and projects with custom rules may behave differently.

Bibliography and cross-references:

This is its main advantage. It can recognize when BibTeX, Biber, or another TeX
pass is required. AeTeX should not claim a bibliography feature merely because
the coordinator invoked one.

Errors:

AeTeX must retain the coordinator output, the TeX `.log`, and available
supporting logs. A non-zero `latexmk` exit is a process result; structured
diagnostics should identify underlying engine or tool evidence when possible.

### Alternative B: Direct Engine Execution

AeTeX launches `pdflatex`, `xelatex`, or `lualatex` directly.

Advantages:

- requires no coordinator beyond the selected engine;
- makes the immediate process and exit code straightforward;
- can provide a useful low-level diagnostic mode;
- may suit intentionally single-pass or generated workflows;
- gives AeTeX complete control over pass policy.

Disadvantages:

- one pass is not a complete build for many real documents;
- fixed two- or three-pass policies are wasteful for some projects and
  insufficient for others;
- AeTeX would need to detect convergence, bibliography processors, indexes,
  glossaries, and rerun warnings;
- each added tool expands process, error, portability, and test surface;
- maintaining an orchestration engine duplicates `latexmk`;
- partial success is easy to mislabel when a PDF exists but references or
  bibliography are stale.

Portability:

The direct engine may be present when `latexmk` is not, which makes it attractive
as a capability fallback. However, silently changing a configured `latexmk`
strategy into direct execution changes build semantics. Availability alone
does not justify that substitution.

Speed:

A single direct pass is fast but often incomplete. A correct convergence loop
can be efficient only after significant implementation work and will continue
to trail specialized tooling on edge cases.

Bibliography and cross-references:

Direct execution does not solve them. Either the strategy remains explicitly
limited to one pass, or AeTeX becomes responsible for a larger build system.

Errors:

The engine's output is closer to the failure, but TeX's console and log formats
remain difficult. Simpler process topology does not imply simpler diagnostic
semantics.

### Alternative C: Hybrid Strategies

"Hybrid" can mean several materially different designs:

1. Prefer `latexmk`, silently fall back to direct engine execution.
2. Use `latexmk` for orchestration but let AeTeX discover and explicitly select
   the engine.
3. Run the engine directly for a fast first pass and use `latexmk` when more
   work is detected.
4. Offer both named strategies and require the project to select one.

The second form is not really a separate strategy. It is the correct composition
of the existing engine and strategy concepts.

The first form improves apparent availability but violates explicit intent and
can yield a lower-quality result with no obvious configuration change. It also
makes failures machine-dependent.

The third form may duplicate work, makes cancellation and result attribution
harder, and assumes AeTeX can reliably identify when the first pass is enough.

The fourth form is architecturally coherent, but schema 1 currently accepts only
`latexmk`. Adding direct execution as a selectable strategy requires a
configuration architecture extension and precise semantics for what "direct"
promises.

Advantages:

- can expose a useful degraded mode when explicitly selected;
- lets future projects choose between mature orchestration and controlled
  low-level behavior;
- supports staged evolution behind one strategy boundary.

Disadvantages:

- silent fallback is semantically unsafe;
- automatic switching makes results less reproducible;
- more strategies multiply test matrices and diagnostic behavior;
- strategy-specific outputs may not have identical artifact guarantees.

### Alternative D: Future Custom Pipelines

Possible future strategies include a declarative sequence of typed tools,
Tectonic, a Make-based integration, container execution, or named tasks.

Advantages:

- can support established non-standard projects;
- may model multiple deliverables or reproducible environments;
- can integrate tools that `latexmk` does not coordinate.

Disadvantages:

- custom commands, Makefiles, and scripts are execution-bearing configuration;
- portability and trust vary sharply;
- generic pipelines require variables, dependency rules, exit policies, and
  artifact declarations;
- a plugin or task abstraction created before stable build results exist would
  be premature.

### Comparison

| Criterion | `latexmk` | Direct engine | Silent hybrid fallback | Explicit future strategies |
| --- | --- | --- | --- | --- |
| Schema 1 fit | Exact | Not representable | Contradicts selected strategy | Requires evolution |
| Tool requirements | Coordinator plus engine/support tools | Engine, then AeTeX orchestration | Machine-dependent | Strategy-dependent |
| Cross-references | Mature coordination | AeTeX must implement | Inconsistent | Strategy-dependent |
| Bibliography | Common cases delegated | AeTeX must implement | Inconsistent | Strategy-dependent |
| Maintenance | Lowest initial AeTeX cost | High and growing | High ambiguity | Bounded only with strict contracts |
| Failure transparency | Needs log correlation | Direct process is clearer | Poor | Must be designed per strategy |
| Portability | Good when installed | Engine often available | Behavior varies by machine | Varies |
| Reproducibility | Good with controlled invocation | Good only with defined pass policy | Poor | Requires declared semantics |

### Conclusion

The initial system should implement `latexmk` as the only strategy because that
is the only strategy Architecture 002 can currently express. The design should
have a strategy boundary, but not a generic scripting framework.

Missing `latexmk` should be an explicit unavailable-tool result, not an
automatic direct-engine fallback. Direct execution remains a credible future
named strategy after its pass, bibliography, convergence, artifact, and
configuration semantics are defined.

## 5. Tool Discovery

Compilation needs to locate `latexmk` and the configured one of `pdflatex`,
`xelatex`, or `lualatex`. It may also need to report supporting tools discovered
by `latexmk`, but AeTeX should not predeclare every transitive requirement.

Discovery and invocation are separate:

- discovery produces typed candidates and evidence;
- selection chooses one candidate under a deterministic policy;
- invocation uses the selected absolute executable and records it in the
  session;
- a process start failure can invalidate stale discovery evidence.

### Alternative A: `PATH`

Advantages:

- follows normal command-line behavior;
- works with custom and package-managed installations;
- requires no distribution-specific registry;
- is easy to explain and reproduce in a terminal when the GUI inherits the
  same environment.

Disadvantages:

- desktop applications may inherit a different `PATH` than interactive shells,
  especially on macOS and GUI launches;
- Windows executable extension and `PATHEXT` behavior require deliberate
  handling;
- multiple installations make the first match significant;
- a project directory or unsafe `PATH` entry could shadow a trusted tool;
- the environment can change after discovery.

### Alternative B: Explicit Machine-Local Configuration

Advantages:

- deterministic for users with multiple installations;
- works when GUI `PATH` is incomplete;
- supports non-standard locations;
- can expose and preserve a user choice.

Disadvantages:

- Architecture 002 forbids executable paths in shared configuration;
- AeTeX has not designed local configuration or precedence yet;
- paths become stale after upgrades;
- users must understand tool boundaries and choose both coordinator and engine
  locations correctly;
- per-project versus global ownership remains undecided.

Any explicit executable path belongs to future local or user configuration, not
`.aetex/project.toml`.

### Alternative C: Platform and Distribution Autodetection

Examples include known TeX Live, MiKTeX, MacTeX, package-manager, registry, or
application-bundle locations.

Advantages:

- can recover from incomplete GUI environments;
- reduces setup friction for conventional installations;
- can show all detected distributions rather than only the first `PATH` match.

Disadvantages:

- installation conventions change and differ by version;
- registry, symlink, bundle, and package-manager rules are platform-specific;
- hard-coded scanning is maintenance-heavy and potentially slow;
- "found" does not prove executable compatibility;
- broad filesystem searches are inappropriate;
- autodetection precedence can surprise users with multiple distributions.

### Alternative D: Ask the Toolchain

Distribution utilities may locate programs or report installation roots.

Advantages:

- delegates some platform knowledge to the installed distribution;
- can provide coherent tool sets from one installation.

Disadvantages:

- requires first locating and trusting the query utility;
- output formats and utility names vary;
- querying is another external process with timeout and error behavior;
- it may still select a different installation from the one the user expects.

### Multiple Installations

A boolean "installed" flag is insufficient. A useful capability model would
retain, conceptually:

- tool kind;
- absolute candidate path;
- discovery source;
- version or probe result when safely available;
- installation grouping when inferable;
- compatibility or health state;
- selection reason;
- diagnostics for rejected candidates.

Coordinator and engine should preferably come from a coherent installation,
but enforcing that rule may exclude valid mixed setups. Version probing can
improve diagnostics but must be bounded, cached, and cancellable; discovery
must not make project opening block indefinitely.

### Open Questions

This study does not decide:

- precedence among an explicit local override, `PATH`, and autodetection;
- whether selection is global, per project, or per installation;
- which distributions and package managers receive first-class detection;
- how candidate versions are probed and cached;
- whether a coordinator and engine from different roots are warned or rejected;
- how the UI asks the user to resolve multiple candidates.

### Direction

Architecture 003 should define a discovery contract and deterministic
precedence, while leaving platform-specific providers replaceable. At minimum,
the first implementation can use a carefully validated `PATH` lookup and clear
manual remediation. It must not imply that `PATH` is the permanent complete
policy.

Tool resolution must not modify shared project meaning. A missing configured
engine is not a reason to substitute another engine.

## 6. Compilation Model

The names below describe responsibility alternatives, not proposed APIs.

### `BuildService`

Possible responsibility:

- validate a prepared request;
- translate a strategy into a process plan;
- execute one build;
- capture evidence and produce one result.

Advantages:

- a narrow mechanism is independently testable;
- matches the existing use of focused services for filesystem behavior;
- avoids Compose dependencies;
- makes strategy and process test doubles possible.

Disadvantages:

- it does not by itself own overlapping requests, queue policy, or project
  replacement;
- a broad service can become a manager under another name.

### `CompilationManager`

Possible responsibility:

- accept user build/cancel requests;
- enforce per-project concurrency policy;
- own active-session lifecycle;
- coordinate cancellation, replacement, and result publication.

Advantages:

- makes lifecycle and concurrency explicit;
- gives project close/replacement one place to terminate active work;
- prevents application state from owning raw `Process` objects.

Disadvantages:

- can mix process mechanics, UI state, and policy if not bounded;
- is unnecessary if it merely forwards every call to a service.

### `JobQueue`

Possible responsibility:

- schedule pending builds;
- limit global or per-project parallelism;
- coalesce repeated requests;
- order cancellation and replacement.

Advantages:

- explicit backpressure and deterministic ordering;
- useful for watch mode, multiple projects, or targets;
- can prevent unbounded coroutine/task creation.

Disadvantages:

- a general queue is more machinery than a single active project needs;
- stale builds may execute after newer edits;
- queue persistence, fairness, and visibility become new questions;
- a visible queue is not useful until multiple meaningful independent jobs
  exist.

### `BuildSession`

Possible responsibility:

- identify one immutable build attempt;
- snapshot project root, confirmed main, engine, strategy, output, selected
  tools, request cause, and start time;
- own process handles and log sinks while running;
- produce a terminal result and artifact inventory.

Advantages:

- prevents mutable current configuration from being confused with an in-flight
  build;
- gives preview and SyncTeX a stable run identity;
- supports correlation of logs, diagnostics, processes, and artifacts;
- clarifies exactly which request cancellation targets.

Disadvantages:

- session lifecycle must be carefully constrained;
- retaining sessions and logs without limits can leak memory or disk space;
- it can become a container for unrelated state.

### Alternative Architectures

#### One service owns everything

This minimizes types but combines request policy, process I/O, cancellation,
history, and UI publication. It is simple only on the happy path and becomes
difficult to test once cancellation races appear.

#### Manager plus service

The manager owns active/pending policy; the service executes one request. A
session/result model carries identity and evidence. This follows AeTeX's
existing proportional responsibility split.

#### Queue-first architecture

All compilation requests become generic jobs. This prepares for multiple
targets and watch mode but introduces abstractions that schema 1 and the current
single-project application cannot use yet.

### Conclusion

The strongest initial shape is:

- an immutable build request derived from a ready effective configuration;
- a per-attempt session identity and terminal result;
- a focused executor/service for strategy and process mechanics;
- a manager for active-build and cancellation policy;
- application state that exposes user-visible projections but does not own
  process mechanics;
- no general-purpose persistent job queue initially.

This is a responsibility recommendation, not an API design. Architecture 003
should name the boundaries only as far as required to state ownership and
lifecycle.

## 7. Processes

### Starting Processes

The JVM `ProcessBuilder` is the appropriate baseline because AeTeX is already a
JVM desktop application and the needed tools are local executables.

The process should be started with:

- a resolved executable path;
- one argument per semantic value;
- an explicit working directory;
- a documented environment policy;
- separate stdout and stderr pipes unless a strategy proves merging necessary.

AeTeX should not construct a shell command and invoke `cmd.exe /c`,
`powershell`, or `/bin/sh -c`. Shell invocation adds quoting differences,
metacharacter injection, and another process layer. `ProcessBuilder` argument
vectors avoid shell parsing, although each tool can still interpret its own
arguments.

The working directory affects TeX include and resource resolution. Plausible
choices are:

- project root, consistent with the AeTeX project boundary and root-relative
  output;
- main-document parent, consistent with projects designed to compile from that
  directory;
- a strategy-specific directory selected by an explicit contract.

Architecture 002 does not decide this. Architecture 003 must do so because
implicit dependence on the application's launch directory is unacceptable.
Project root is the strongest default for AeTeX consistency, but nested-main
compatibility should be validated against representative projects before it is
accepted.

### Standard Output and Error

Both streams must be drained concurrently. Reading one stream to completion
before the other can deadlock when the unread pipe fills.

Each stream should preserve:

- stream identity;
- order within that stream;
- decoded text;
- enough raw or decoding evidence to diagnose encoding failures;
- arrival sequence or timestamps for UI interleaving.

A total order across two independently drained OS pipes is observational, not a
guarantee of the order in which the child wrote messages. The model should not
claim stronger chronology than the platform exposes.

Stream decoding is a portability issue. Assuming UTF-8 for all TeX console
output is unsafe, especially on Windows. The accepted architecture should
define a tolerant decoding policy that preserves evidence when replacement
characters occur.

### Exit Codes and Success

An exit code is necessary but not sufficient. Terminal build outcomes should
distinguish at least:

- succeeded;
- failed with a process exit;
- failed to start;
- unavailable tool;
- invalid or no-longer-ready configuration;
- cancelled;
- timed out;
- process cleanup incomplete;
- internal I/O or orchestration failure.

Success should require:

- the selected strategy reached its successful exit condition;
- cancellation was not requested first;
- expected artifact validation passed;
- the result is associated with the current session rather than a prior PDF
  left in the output directory.

Artifact validation may use existence, regular-file checks, modification
evidence, and a session snapshot. Timestamp-only identity is vulnerable to
filesystem granularity and clock assumptions, so it should not be the sole
long-term proof.

### Cancellation

Cancellation is a protocol, not just a `destroy()` call:

1. atomically mark the session as cancellation requested;
2. stop accepting success publication for that session;
3. terminate the coordinator and known descendants gracefully;
4. continue draining streams;
5. wait for a bounded grace period;
6. forcibly terminate remaining known processes;
7. wait again for a bounded period;
8. publish whether cleanup completed.

The process may exit normally at the same time cancellation is requested. The
accepted architecture must define which terminal state wins. A defensible rule
is that a cancellation accepted before terminal publication yields
`cancelled`, while a request after terminal publication has no effect.

`ProcessHandle.descendants()` is useful but not a complete cross-platform
process-group guarantee. Descendants can exit, reparent, or spawn between
enumeration and termination. Windows and Unix process-tree semantics differ.
The first implementation should make best-effort cleanup observable rather
than promising a guarantee the JVM cannot provide.

### Timeout

Advantages of a default timeout:

- bounds hangs caused by prompts, blocked tools, or pathological input;
- improves automated and unattended behavior.

Disadvantages:

- legitimate large projects can run for a long time;
- one duration cannot fit local hardware and project scale;
- timeout may terminate a build that is still producing useful progress.

An architecture should support bounded process operations and an optional build
timeout, but should not choose an aggressive universal duration without usage
evidence. Cancellation cleanup always needs its own short bounded waits.

Interactive TeX prompts must be prevented or handled non-interactively by the
strategy. Otherwise a process can wait on stdin invisibly. AeTeX should not
provide an interactive terminal in the initial milestone.

### Resource Cleanup

Cleanup includes:

- closing stdin when no input is supported;
- draining and closing stdout and stderr;
- closing log files;
- releasing executor threads or coroutines;
- terminating remaining processes on project replacement and application exit;
- removing only session-owned temporary files;
- publishing cleanup failure separately from the original build cause.

Exceptions during cleanup must not replace the primary failure evidence.

### Conclusion

Use `ProcessBuilder` directly with argument vectors, explicit working directory,
concurrent stream draining, typed terminal outcomes, and a staged best-effort
process-tree cancellation protocol. Treat artifact validation and cleanup
evidence as part of the result, not as logging afterthoughts.

## 8. Concurrency

### Alternative A: One Compilation Per Project

Advantages:

- prevents simultaneous writes to the same auxiliary and output files;
- makes current build state understandable;
- matches schema 1's one main document and one output;
- simplifies preview association and cancellation;
- permits reuse of auxiliary files between sequential runs.

Disadvantages:

- a long build blocks a second requested build until cancellation or completion;
- future independent targets would need more granular output ownership;
- a tool that internally parallelizes is still outside AeTeX's direct model.

### Alternative B: Multiple Compilations Per Project

Advantages:

- can build independent targets concurrently;
- supports comparisons or different profiles;
- could improve throughput on multi-document projects.

Disadvantages:

- current schema has no target or profile identity;
- builds can corrupt shared `.aux`, `.log`, PDF, and SyncTeX files;
- CPU and I/O contention can make both slower;
- UI and result selection become ambiguous;
- cancellation and cleanup affect overlapping process trees.

Without isolated output ownership, this alternative is unreliable.

### Alternative C: FIFO Queue

Advantages:

- preserves every request;
- makes execution order deterministic;
- avoids output overlap.

Disadvantages:

- editing can make queued builds stale before they start;
- repeated shortcuts can create a backlog;
- users usually care about the newest source state, not every intermediate
  request;
- a queue requires visible pending/cancel behavior.

### Alternative D: Automatic Cancel-and-Replace

Advantages:

- prioritizes the latest requested state;
- avoids a stale queue;
- works naturally for future auto-build or watch mode;
- still serializes output once cleanup completes.

Disadvantages:

- frequent edits can starve completion;
- cancellation is not instantaneous;
- starting the replacement before cleanup finishes recreates output races;
- cancelling an explicit manual build without visible intent can surprise the
  user.

### Alternative E: Reject While Busy

Advantages:

- simplest state machine;
- never cancels work unexpectedly;
- avoids queues.

Disadvantages:

- rebuilding requires a separate cancel then build interaction;
- poor fit for the roadmap's "cancelled and rebuilt" workflow;
- future auto-build becomes awkward.

### Conclusion

The initial policy should allow at most one active build per project/output.
It should never run a replacement until the prior session reaches a terminal
cleanup state.

A one-slot latest-request replacement policy is preferable to an unbounded FIFO
queue: a new request can request cancellation of the current session and
replace any pending request. Manual UI behavior should make that transition
visible. Automatic triggers, if later added, need debounce and starvation
rules.

This policy can be implemented by the compilation manager without introducing
a reusable `JobQueue`. Multiple parallel builds should wait for named targets
and isolated output ownership.

## 9. Logs

"Log" covers several kinds of evidence that should not be collapsed into one
string.

### Process Transcript

The transcript should represent:

- the resolved executable and argument list;
- working directory;
- selected tool versions when known;
- start and finish times and duration;
- stdout events;
- stderr events;
- exit code;
- cancellation and timeout events;
- process cleanup events.

Arguments should remain a list internally. A rendered command is for display
and must use platform-appropriate quoting without becoming the value executed.
Potential future secrets would need redaction, although schema 1 contains none.

### Build Messages

AeTeX-originated messages should be distinct from child-process text. Examples:

- resolving tools;
- starting engine through `latexmk`;
- cancellation requested;
- waiting for process cleanup;
- expected PDF missing;
- log parsing incomplete.

These messages need a category or severity and session identity. They should not
be inserted into raw stdout in a way that makes the original transcript
irrecoverable.

### Structured Events

A useful conceptual event contains:

- session identity;
- monotonically increasing AeTeX sequence;
- timestamp;
- origin such as AeTeX, coordinator stdout, coordinator stderr, or parsed file;
- severity when known;
- text or structured payload.

Events permit incremental UI rendering and later filtering. They should not
force every console line into a claimed diagnostic.

### Retention and Size

Keeping an entire large transcript as Compose observable strings can create
memory and rendering problems. Alternatives are:

- all events in memory: simplest, but unbounded;
- bounded in-memory tail: responsive, but violates the roadmap requirement for
  complete logs unless another sink exists;
- file-backed complete transcript plus bounded live view: more lifecycle work,
  but preserves full evidence;
- only TeX-generated logs: incomplete when startup or coordinator failure
  occurs before a `.log` exists.

The strongest direction is a complete append-only session transcript backed by
a controlled file or stream, plus a bounded/indexed projection for live UI.
The exact storage location and retention policy need a decision because
`.aetex/cache` is only reserved, not an accepted current contract. Temporary
application-owned storage avoids modifying the shared project but must survive
long enough for user inspection.

### Conclusion

Keep raw process evidence, AeTeX lifecycle messages, and parsed diagnostics as
related but distinct models. Preserve full logs even when the UI uses a bounded
projection. Never replace complete evidence with only "build failed."

## 10. Diagnostics

### Alternative A: Raw Messages Only

Advantages:

- lossless and low interpretation risk;
- available for every tool and unknown format;
- smallest initial parser surface.

Disadvantages:

- users must interpret verbose TeX output;
- no editor navigation;
- warnings and root causes are hard to find;
- does not meet the roadmap goal of initial extraction and navigation.

### Alternative B: Parse Console Output

Advantages:

- diagnostics can appear while the process runs;
- coordinator and startup errors may exist only in console output;
- no need to wait for a `.log` file.

Disadvantages:

- stdout formatting varies by engine, wrapper, version, line wrapping, and
  locale;
- file context may be ambiguous;
- stdout and stderr do not necessarily contain the complete TeX record;
- incremental parsing must handle chunks split inside lines or messages.

### Alternative C: Parse TeX and Supporting Log Files

Advantages:

- the engine `.log` normally contains more detailed context;
- bibliography and index logs can identify supporting-tool failures;
- parsing after completion sees a stable file more often than live console;
- file and line context can be richer.

Disadvantages:

- TeX log syntax is nested, line-wrapped, and not a formally stable diagnostic
  protocol;
- fatal startup failures may create no log;
- stale logs from a previous run can be mistaken for current evidence;
- auxiliary tools have different formats;
- output may be partially written after cancellation.

### Alternative D: Typed Diagnostics With Raw Fallback

The parser emits conservative typed values while every item points back to raw
evidence.

A conceptual compilation diagnostic may contain:

- severity;
- normalized message;
- source path and optional line/column;
- originating tool;
- related log location or event sequence;
- stable AeTeX diagnostic kind where meaningful;
- confidence or completeness indicator;
- session identity.

Advantages:

- supports future editor navigation;
- permits consistent filtering and presentation;
- preserves uncertainty;
- can combine process-start, coordinator, engine, and artifact failures.

Disadvantages:

- typed schemas can overstate certainty;
- parsers require fixtures from several engines and platforms;
- one generic diagnostic hierarchy may prematurely couple configuration,
  compilation, editing, and analysis.

### Relationship to Configuration Diagnostics

`ProjectConfigurationDiagnostic` is an effective precedent: it separates code,
severity, user message, paths, source positions, and technical detail. However,
compilation diagnostics have different lifetime and provenance. They should not
be forced into the configuration enum or stored on `TeXProject`.

A later cross-feature presentation interface may be useful, but the compilation
domain should first establish its own semantics. Shared UI projection does not
require shared persistence or one giant diagnostic enum.

### Conclusion

Use typed compilation diagnostics backed by complete raw evidence. Parse the
TeX `.log` and supporting logs conservatively after verifying that they belong
to the current session; supplement them with live console and AeTeX process
diagnostics. Unknown or partially parsed messages remain visible as raw text.

Initial parsing should target high-value cases:

- fatal process or tool startup failures;
- TeX error message plus best available file and line;
- conventional warnings with a source location when unambiguous;
- missing expected artifact;
- cancellation and timeout.

It should not promise a complete TeX parser or suppress unrecognized content.

## 11. Output Directory

### Consume Effective Output

Compilation must use
`EffectiveProjectConfiguration.outputDirectory.value`. It must not:

- fall back to `build` when `isReady` is false;
- reinterpret the stored path relative to the main document;
- permit an invocation option to escape the configured output;
- rescan the project to derive a different output.

The loader validates the path at project-open time, but filesystem state can
change afterward. Before creating or writing output, compilation should
revalidate lexical containment, existing ancestor identity, directory type,
symbolic-link behavior, and metadata overlap. This is defense against stale
state and time-of-check/time-of-use changes, not a second configuration parser.

### Shared Output Versus Per-Session Isolation

#### Shared configured output

Advantages:

- enables incremental reuse of `.aux`, `.fdb_latexmk`, and related files;
- produces a stable PDF location for future preview;
- matches current configuration semantics;
- works with ordinary command-line use outside AeTeX.

Disadvantages:

- requires serialized builds;
- stale artifacts can confuse result detection;
- cancelled builds can leave partial auxiliaries;
- clean builds need careful deletion rules.

#### Per-session temporary output

Advantages:

- isolates concurrent and failed runs;
- artifact ownership is clear;
- cleanup is conceptually simpler.

Disadvantages:

- discards incremental value;
- preview path changes each run;
- moving final artifacts can break SyncTeX paths and supporting files;
- projects may expect auxiliary files at the configured location;
- it does not match Architecture 002's output intent without another publishing
  contract.

#### Staging then publishing

Advantages:

- could provide atomic-ish final artifact replacement;
- protects prior successful output from some partial failures.

Disadvantages:

- TeX emits a set of mutually related files, not just one PDF;
- moves across filesystems may not be atomic;
- SyncTeX and embedded paths may refer to staging locations;
- it adds complexity before preview requirements are defined.

### Auxiliary Files

The output is a generated subtree, not only a PDF path. The result should
inventory relevant artifacts rather than assume every file is safe or current.
At minimum, future consumers need the PDF and eventually SyncTeX identity.

Generated auxiliaries should remain excluded from the editable project tree,
consistent with the current scanner and application-state behavior. The
compilation system should not mutate the static project tree to insert them.

### Cleaning

Possible policies:

- clean before every build;
- never clean automatically;
- expose an explicit clean operation;
- clean only files proven to belong to the selected strategy/target.

Cleaning before every build reduces stale evidence but discards incremental
speed and increases destructive risk. Never cleaning preserves speed but can
retain corrupt or obsolete auxiliaries.

The safest initial direction is:

- do not recursively delete the configured output as part of ordinary build;
- offer no implicit clean fallback after failure;
- design a future explicit clean action with containment revalidation and a
  strategy-owned manifest or conservative known-artifact policy;
- never delete `.aetex`, the project root, source files, or paths reached through
  unsafe links.

### Conclusion

Use the configured shared output, serialize access to it, revalidate it before
process start, and treat produced artifacts as session evidence. Do not adopt
per-session output or recursive cleaning until preview, SyncTeX, and artifact
publication semantics justify them.

## 12. Security

Local compilation executes programs and interprets untrusted project content.
Path validation alone is not a security boundary.

### Command Construction and Injection

Risks:

- shell metacharacters in project paths or filenames;
- options injected through a main path beginning with `-`;
- a project-local executable shadowing a system tool;
- environment variables changing tool behavior;
- user-controlled future custom arguments.

Mitigations:

- no shell;
- resolved absolute executable;
- structured argument list;
- an option terminator where supported, or a tool-specific safe path form;
- fixed options derived only from typed configuration;
- explicit working directory;
- validation that tool candidates are regular executable files outside unsafe
  project-controlled resolution;
- exact command evidence in the build session.

Avoiding a shell prevents shell injection, but it does not prevent option
injection into the invoked tool. Strategy adapters must understand their tool's
argument grammar.

### TeX Execution Risk

TeX input is active content. Depending on engine configuration and flags, it can
read files, write generated files, and invoke restricted or unrestricted
external commands. A malicious or unfamiliar project may exploit the local
user's permissions.

AeTeX cannot provide a credible cross-platform sandbox using `ProcessBuilder`
alone. Therefore:

- compilation must be treated as execution, not as opening a text document;
- AeTeX should not enable unrestricted shell escape by default;
- requested shell-escape or custom execution requires a separate design and
  explicit trust;
- the UI should expose the command and trust consequence;
- documentation must not claim that a no-shell Java invocation makes TeX input
  safe.

### `latexmk` Initialization and Project Files

`latexmk` can be influenced by initialization files and project-specific
configuration. Such files can carry execution behavior beyond
`.aetex/project.toml`.

Plausible policies are:

- allow normal `latexmk` initialization for ecosystem compatibility;
- disable project/user initialization for a controlled AeTeX strategy;
- allow it only after project trust is granted;
- inspect and report detected initialization sources.

Compatibility favors normal behavior; predictability and security favor a
controlled invocation. Architecture 003 must explicitly decide this after
verifying supported `latexmk` options and the workflows AeTeX intends to
preserve. It must not leave the behavior as an accidental consequence of the
launch environment.

### Trust Model

Alternatives:

- compile any opened project immediately;
- ask for confirmation on every build;
- persist trust for a project identity;
- distinguish controlled builds from execution-bearing project features.

Opening for editing should remain safe and independent. Compilation should
require an explicit user action at minimum. Future auto-build must not run
merely because an untrusted folder was opened.

Persisted trust raises identity and storage questions: paths can be reused,
repositories can change, and shared configuration must not store a personal
trust decision. Trust belongs to local/user state and requires a separate
decision before persistence.

### Paths and Temporary Files

- Revalidate output containment immediately before use.
- Do not follow output symlinks outside the project.
- Create temporary files with OS APIs, unpredictable names, and restrictive
  permissions where supported.
- Keep application transcript storage outside shared source unless explicitly
  designed.
- Do not parse an artifact as current solely because it has the expected name.
- Treat cleanup paths as untrusted and verify their resolved boundary.

### Environment

Inheriting the full environment maximizes TeX compatibility but allows
variables to change lookup, configuration, and output. Replacing it with a
minimal environment improves predictability but can break distributions.

The accepted architecture should specify:

- which environment is inherited;
- which variables AeTeX sets or removes;
- whether the effective environment is inspectable;
- how secrets are redacted from logs;
- how locale and encoding are controlled.

This study does not select the exact allowlist because distribution evidence is
needed.

### Conclusion

The initial system should execute only known strategy-owned programs with fixed
typed arguments, never a shell or arbitrary project command. Compilation should
be an explicit action, unrestricted shell escape should not be enabled, output
and temporary paths should be revalidated, and trust-sensitive `latexmk`
initialization behavior must be an explicit Architecture 003 decision.

## 13. Portability

The conceptual workflow should be consistent across platforms even when
discovery and cleanup mechanisms differ.

### Windows

Relevant differences:

- executable names may use `.exe`, `.bat`, or `.cmd`, and `PATHEXT` affects shell
  lookup while `ProcessBuilder` behavior must be tested explicitly;
- MiKTeX and TeX Live installation/discovery conventions differ;
- drive-qualified and UNC paths exist;
- path comparison is commonly case-insensitive but `Path` equality alone does
  not express filesystem identity;
- process termination does not use Unix signals and descendant cleanup may need
  platform-specific support for strong guarantees;
- open PDF or log files may be locked against replacement;
- console code pages may not be UTF-8;
- command-line length and long-path settings can matter.

AeTeX already rejects drive-qualified shared configuration paths. Tool
executable paths are machine-local and may legitimately be drive-qualified.

### Linux

Relevant differences:

- TeX tools are usually found through `PATH` and package-manager layouts;
- signals and process groups exist, but Java descendant termination still has
  races;
- files can usually be replaced while open;
- paths and executable names are case-sensitive;
- permissions and executable bits matter;
- distribution packages may split `latexmk` and engines.

The current native distribution configuration targets Debian only, but that is
not sufficient evidence for Linux-wide TeX support.

### macOS

Relevant differences:

- MacTeX commonly exposes tools through conventional links, but GUI applications
  may not inherit the interactive shell's `PATH`;
- application sandboxing, signing, and bundle launch environments may constrain
  executable access in future packaging;
- filesystems are often case-insensitive while preserving case;
- process and file behavior is Unix-like but should not be assumed identical to
  Linux;
- Apple Silicon and Intel installation paths can coexist.

### Cross-Platform Rules

- Use `Path` and `Files`, not string concatenation.
- Store shared project paths with `/`, then use the already resolved effective
  `Path`.
- Invoke argument vectors, not shell-quoted strings.
- Resolve tool paths once per session and record the absolute selection.
- Normalize presentation separately from path identity.
- Make output decoding tolerant and preserve raw evidence.
- Treat process-tree cleanup as a tested platform capability.
- Use platform-specific discovery behind a common typed contract.
- Test paths containing spaces, non-ASCII characters, leading hyphens, and long
  segments.
- Test absent tools, multiple installations, cancellation during child
  execution, locked outputs, and GUI-launch environments.

### Conclusion

Portability should mean the same state machine and result semantics, not one
identical discovery implementation. Architecture 003 should define platform
invariants and allow platform adapters only where executable discovery,
encoding, and process cleanup genuinely differ.

## 14. Future Evolution

The initial architecture should leave bounded extension points without
designing these features now.

### Incremental Compilation

`latexmk` already reuses auxiliary state. AeTeX-level incremental decisions
would require source/dependency identity and should not be inferred only from
editor buffers or timestamps.

### Multiple Targets

Named targets require configuration schema evolution, active-target selection,
per-target output and result identity, and preview/SyncTeX association. The
session model should not assume the main filename is a globally unique future
target identifier.

### Parallel Builds

Parallelism becomes safe only when targets own isolated outputs or a strategy
proves non-overlapping writes. Global resource limits and UI selection would
then matter.

### Cache

A cache requires content identity, tool/version inputs, environment policy,
invalidation, size limits, and security boundaries. Reusing the configured
auxiliary directory is not equivalent to a verified cache.

### Watch Mode and Auto-Build

Watch mode requires the future filesystem watcher, debounce, source/output
filtering, starvation control, trust persistence, and interaction with unsaved
editor content. It should reuse the manager/session lifecycle rather than run a
second hidden process system.

### Tasks and Custom Pipelines

Tasks introduce executable configuration, parameters, dependencies, secrets,
and trust. They should be designed as a separate capability rather than exposed
by making the initial strategy accept arbitrary command strings.

### Remote or Isolated Builds

A future executor might run in a container or remote environment. A stable
request/result/session contract helps, but local `Path` values, artifact
transfer, source snapshots, trust, and cancellation semantics would need an
explicit design.

### Unsaved Buffer Compilation

Compiling editor contents not yet saved would require a source overlay or
temporary project snapshot and would change path, diagnostics, and artifact
identity. The initial system should compile filesystem state and make the
unsaved-document policy explicit in the UI workflow.

## 15. Risks

| Risk | Most affected alternative | Consequence | Architectural mitigation |
| --- | --- | --- | --- |
| `latexmk` is missing | `latexmk` first | Build unavailable despite installed engine | Typed discovery state and remediation; no silent semantic fallback |
| Coordinator behavior varies | `latexmk` | Non-reproducible command or diagnostics | Record version, executable, args, environment policy, and logs |
| AeTeX duplicates a build system | Direct engine | High maintenance and incomplete documents | Keep direct execution future and semantically narrow |
| Silent fallback changes meaning | Hybrid | Machine-dependent output quality | Strategies are explicit; missing tool is a failure |
| Wrong engine is substituted | Availability-driven discovery | Different document semantics | Resolve only the configured `TeXEngine` |
| Multiple builds share output | Parallel or poorly queued builds | Corrupt or misattributed artifacts | One active session per project/output |
| Stale PDF is reported as success | Shared output | Preview shows prior result | Session artifact validation and identity |
| Output path changes after project load | All | Writes escape or target unsafe location | Revalidate immediately before use |
| Process pipes fill | All external execution | Deadlock and frozen cancellation | Concurrent bounded draining |
| Descendants survive cancellation | `latexmk` and pipelines | Resource leak and continued file writes | Staged tree cleanup with explicit incomplete-cleanup state |
| Timeout kills legitimate build | Universal timeout | False failure and lost work | Optional/evidence-based build timeout; separate cleanup bounds |
| Logs exhaust memory | In-memory transcript | UI instability | Complete file-backed sink plus bounded projection |
| Log parser misattributes a file | Aggressive typed parsing | Wrong editor navigation | Conservative parsing, project confinement, raw evidence, confidence |
| Console encoding is wrong | Cross-platform capture | Unreadable diagnostics | Tolerant configurable decoding and preserved evidence |
| Project content executes commands | TeX/`latexmk` | Local security impact | Explicit build action, no arbitrary commands, shell-escape policy, trust design |
| `latexmk` initialization executes code | Compatibility-first invocation | Hidden behavior | Explicit initialization policy and command visibility |
| `PATH` selects a shadowed tool | PATH discovery | Unexpected code execution | Validate candidates, avoid project-local resolution, record absolute path |
| Tool selection differs by GUI launch | PATH-only discovery | Works in terminal but not AeTeX | Capability diagnostics and later layered discovery |
| Clean removes source or metadata | Recursive cleaning | Data loss | No implicit recursive clean; confined manifest-based future action |
| Mutable config changes mid-build | Manager reads live project state | Result cannot be attributed | Immutable request/session snapshot |
| Project closes during build | UI-owned process | Orphan process or stale publication | Manager owns lifecycle and project replacement cancellation |
| Abstraction is too generic early | Queue/plugin/pipeline first | Complexity without validated use | Focused `latexmk` strategy and proportional boundaries |

Residual risk remains because TeX and its ecosystem are executable,
platform-specific software. A reliable architecture makes those limits visible;
it cannot turn arbitrary local compilation into a pure or sandboxed operation.

## 16. Recommendation

The recommended direction is:

1. Consume only a ready `EffectiveProjectConfiguration`. Snapshot the project
   root, confirmed main, engine, strategy, and output into an immutable build
   request. Do not reinterpret persisted configuration.
2. Preserve engine and strategy as separate typed abstractions. Resolve their
   executable requirements locally without changing configured intent.
3. Implement `latexmk` as the first and only schema 1 strategy. Do not silently
   fall back to direct engine execution when it is missing.
4. Introduce a focused single-build executor/service, a lifecycle manager, and
   per-attempt session/result identity. Keep process ownership out of Compose
   state and avoid a general job framework.
5. Permit at most one active build per project/output. Coalesce replacement
   demand to one latest pending request and start it only after cancellation and
   cleanup of the active session.
6. Use `ProcessBuilder` with a resolved executable and argument vector, an
   explicit working directory and environment policy, concurrent stdout/stderr
   draining, and no shell.
7. Model terminal outcomes explicitly. Exit code alone is not success; validate
   expected artifacts as belonging to the session and report incomplete process
   cleanup.
8. Preserve a complete raw transcript and expose a bounded live projection.
   Keep lifecycle messages, process output, and structured diagnostics
   distinguishable and correlated by session.
9. Add conservative typed diagnostics from process failures, console evidence,
   and current-session TeX/supporting logs. Always retain raw fallback and do
   not claim complete TeX interpretation.
10. Use the effective configured output, revalidate it before execution,
    serialize writes, and avoid implicit recursive cleaning or per-session
    output relocation.
11. Treat compilation as execution of active content. Require explicit action,
    do not enable unrestricted shell escape, do not accept arbitrary commands,
    and make `latexmk` initialization/trust policy an explicit decision.
12. Define one cross-platform state/result contract with platform-specific
    discovery, encoding, and cleanup providers only where necessary.

This direction is intentionally narrower than a generic build system. It
delivers the roadmap workflow while keeping direct engines, targets, tasks,
parallel builds, caching, and watch mode available for later explicit
architecture work.

### Decisions to Promote Into Architecture 003

A future accepted Architecture 003 should decide and specify:

- the readiness precondition and immutable build-request snapshot;
- the engine/strategy separation and typed tool requirements;
- `latexmk` as the schema 1 strategy and the prohibition on silent direct
  fallback;
- ownership boundaries among single-build execution, lifecycle management,
  application state, and UI;
- build-session identity, state transitions, terminal outcomes, and result
  evidence;
- one-active-build-per-project/output concurrency and replacement semantics;
- `ProcessBuilder` invocation without a shell;
- exact working-directory policy;
- process environment and console-decoding policy;
- stdout/stderr capture, complete transcript storage, limits, and retention;
- cancellation race rules, grace periods, forced cleanup, and platform
  capability expectations;
- timeout policy, including whether ordinary builds have a default;
- artifact identity and success validation for PDF and future SyncTeX consumers;
- typed compilation diagnostics and their raw-evidence relationship;
- output revalidation, auxiliary-file ownership, and explicit clean behavior;
- trust behavior, shell-escape restrictions, and `latexmk` initialization-file
  policy;
- the initial tool-discovery and selection precedence;
- automated contract, integration, and supported-platform test requirements.

### Evidence Still Needed Before Acceptance

The recommendation is not a final decision. Architecture 003 should not be
accepted until the project has evaluated:

- representative `latexmk` invocations for all three configured engines and a
  nested main document;
- actual tool discovery behavior for TeX Live, MiKTeX, and MacTeX under both
  terminal and GUI launch environments;
- `latexmk` initialization-file controls and their compatibility/security
  trade-off;
- cancellation of coordinator, engine, and bibliography descendants on
  Windows, Linux, and macOS;
- console and `.log` encodings from supported distributions;
- a small diagnostic fixture corpus with multi-file errors and wrapped log
  lines;
- artifact freshness/identity behavior on filesystems with coarse timestamps
  and locked files;
- a storage and retention policy that provides complete logs without unbounded
  memory use.

Until those points are resolved, this document is a technical study and its
recommendation is a proposal for the accepted RFC, not the compilation-system
contract.
