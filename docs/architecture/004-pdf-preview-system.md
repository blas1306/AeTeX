# AeTeX Architecture 004: PDF Preview System

## 1. Status

| Field | Value |
| --- | --- |
| Identifier | AeTeX Architecture 004 |
| Title | PDF Preview System |
| Status | Accepted |
| Date | 2026-07-30 |
| Scope | Compilation-result intake, immutable PDF generations, page rendering, scheduling, caching, concurrency, Compose presentation, portability, and extension boundaries |

This document is the normative architecture for AeTeX PDF preview. It promotes
the accepted direction from
[Architecture Study 004](004-pdf-preview-system-study.md) into a stable system
contract and selects a rendering engine using the audited
[experimental rendering benchmark](../../tools/rendering-benchmark/README.md).
The study and benchmark remain engineering evidence, but this document takes
precedence wherever their provisional recommendations differ from this
decision.

This document does not implement preview or change the benchmark. The current
repository still contains only a placeholder preview panel.

## 2. Context and Objectives

[Architecture 003](003-compilation-system.md) establishes the only
authoritative source of previewable output: a successful, immutable
`BuildResult` containing the exact valid artifact whose role is
`PRIMARY_PDF`. Preview must consume that result rather than search an output
directory, infer a filename, observe an arbitrary filesystem event, or accept a
path from Compose.

The preview system must:

- display the `PRIMARY_PDF` produced by a successful AeTeX compilation;
- remain responsive while documents open and pages rasterize;
- represent every accepted document version as an immutable generation;
- prevent pages or late work from different generations from being combined;
- render visible pages lazily and refine the preview incrementally;
- bound CPU, memory, queued work, and renderer-owned resources;
- keep PDF and renderer types outside Compose;
- avoid holding the compilation output open while it may be regenerated;
- preserve page-space geometry for future navigation and overlays;
- remain independently testable from both compilation processes and Compose.

The architecture governs one preview subsystem per open project. Application
policy may coordinate global renderer and memory limits across projects.

## 3. Accepted Decisions

AeTeX adopts the following design:

1. **Apache PDFBox 3.x is the production rendering engine.** The exact
   dependency version is pinned by the implementation and must remain within a
   tested, supported 3.x release.
2. **Compilation results are the sole update authority.** Preview has no public
   operation that opens an arbitrary PDF path.
3. **Every accepted successful build creates a new immutable
   `DocumentGeneration`.** A path is not a generation identity.
4. **Each generation renders from a preview-owned immutable source snapshot,**
   not from the replaceable compilation output.
5. **Pages render lazily through a bounded priority scheduler.** AeTeX never
   renders the complete document merely because it was opened.
6. **Completed pages use a memory-weighted, priority-aware LRU cache.** A fixed
   page-count limit is prohibited.
7. **Compose consumes already-rendered, engine-neutral page images.** It never
   opens a PDF, invokes PDFBox, or rasterizes PDF content.
8. **PDFium remains the primary benchmark comparator and migration candidate.**
   It is not a production dependency under this decision.

These decisions define behavior and boundaries. Numeric defaults for worker
limits, cache budgets, scale buckets, and neighbor radius are runtime policy
chosen and measured during implementation; they are not project configuration
and do not weaken the boundedness requirements in this document.

## 4. Rendering Engine

### 4.1 Selection

AeTeX selects **Apache PDFBox 3.x**, accessed through its direct JVM API and
contained behind `DocumentRenderer`.

The audited benchmark compared PDFBox 3.0.5 and PDFium 152.0.7961.0 through the
same engine-neutral raster contract. On the recorded Linux x64/JVM 21 run, both
engines opened and rendered the synthetic corpus without unexpected normal or
robustness failures. PDFium was materially faster for several text-heavy,
many-page, and high-scale operations; PDFBox was faster for the image-heavy
fixture, and several ordinary first-page results were close. The evidence does
not establish one universal performance winner. It does establish that PDFBox
produced interactive-scale page latencies for the measured corpus and that a
lazy, prioritized architecture is viable without assuming native rendering.

The selection weighs that measured sufficiency together with integration and
operational ownership:

- **JVM integration:** PDFBox is a direct Java dependency. Its page raster API
  fits AeTeX's Kotlin/JVM process without JNA, JNI, Panama bindings, or native
  handle exposure.
- **Operational complexity:** one JVM dependency avoids building, obtaining,
  verifying, extracting, loading, signing, and updating a native library for
  every supported OS and CPU architecture.
- **Portability:** the same adapter and dependency model apply on Windows,
  Linux, and macOS. Java2D, fonts, color management, and file behavior still
  require platform testing; “pure JVM” does not mean pixel-identical output.
- **Maintenance:** PDFBox follows ordinary JVM dependency monitoring and
  upgrade testing. PDFium would additionally require ownership of a production
  binding, ABI compatibility, native crash diagnosis, binary provenance, and
  platform packaging.
- **Dependencies and licensing:** PDFBox uses the Apache License 2.0. Any
  optional codecs introduced by the implementation require their own necessity
  and license audit. The benchmark's PDFium package is a pinned third-party
  precompiled distribution and is explicitly not a production packaging
  decision.
- **Evolution:** the renderer boundary contains PDFBox types and thread-safety
  rules. A future engine can replace the adapter without changing compilation,
  generation identity, cache keys, view intents, or Compose contracts.

PDFium's speed advantage in important benchmark categories is real evidence,
not dismissed evidence. It does not currently justify taking on the native
distribution and binding lifecycle when PDFBox satisfies the measured initial
preview workload. If representative product workloads later fail the gates in
section 18, PDFium is the first alternative to reevaluate.

### 4.2 Scope and Evidence Limits

The engine decision is definitive for the initial stable preview architecture,
but the benchmark does not prove all-platform performance or memory behavior:

- the audited measurements were produced on one Linux x64 machine;
- reliable sampled RSS was available only through Linux `/proc`;
- the corpus is synthetic and does not cover every LaTeX font, codec,
  transparency, color-space, rotation, form, or malformed-PDF case;
- the benchmark did not measure Compose scrolling, image upload, cancellation,
  file replacement, packaging, or native crash isolation;
- visual inspection is evidence, not an automated fidelity oracle;
- three measured repetitions characterize that run, not the complete hardware
  population.

Consequently, no claim is made that PDFBox uses less memory than PDFium, wins
every rendering category, or is already validated on Windows and macOS. Cache
budgets, maximum raster area, packaging, file replacement, and fidelity remain
release-validation obligations. A material failure can trigger the
engine-reevaluation process without changing the engine-neutral architecture.

### 4.3 Engine Containment

Only the PDFBox adapter may depend on `PDDocument`, `PDFRenderer`,
`BufferedImage`, or other PDFBox/AWT rendering types. None may appear in:

- `PreviewManager` state;
- generation, render-request, rendered-page, or cache contracts;
- Compose state or composable parameters;
- plugin or future integration contracts.

PDFBox access to one document is serialized. The initial adapter opens exactly
one renderer document for a generation; parallel duplicate `PDDocument`
instances are not used unless later memory and throughput evidence authorizes a
document-source strategy for them.

## 5. Architecture

The conceptual delivery pipeline is:

```text
CompilationManager
        |
        | successful BuildResult + exact PRIMARY_PDF
        v
PreviewManager
        |
        v
DocumentGeneration
        |
        v
DocumentRenderer
        |
        v
PageCache
        |
        v
Compose UI
```

The request path is not a blind pass through every box. `PreviewManager` checks
`PageCache` first, coalesces misses in its scheduler, calls
`DocumentRenderer`, stores an accepted result, and publishes a presentation
model. The diagram expresses ownership and dependency direction: compilation
does not depend on preview, and lower layers never call Compose.

### 5.1 `CompilationManager`

`CompilationManager` remains governed by Architecture 003. For preview it:

- publishes or replays terminal `BuildResult` values for the same project;
- supplies session, plan, output-space, and artifact identity;
- identifies exactly one required `PRIMARY_PDF`;
- publishes success only after process cleanup and artifact validation.

It does not open renderer documents, create images, manage preview state, or
wait for the preview to finish. A preview failure never changes a successful
compilation into a failed compilation.

### 5.2 `PreviewManager`

`PreviewManager` is the project-scoped coordinator. It:

- subscribes to the owning `CompilationManager`;
- accepts only successful results for that same project identity;
- selects the exact valid `PRIMARY_PDF` observation;
- creates, prepares, promotes, retires, and closes document generations;
- owns the bounded render scheduler and request tokens;
- queries and invalidates `PageCache`;
- preserves or clamps view anchors across generation promotion;
- exposes immutable, engine-neutral preview state and page results;
- keeps the last good generation visible with an explicit stale state when a
  newer build or preview preparation fails;
- cancels work and releases resources on project close or application shutdown.

It does not parse project configuration, infer output names, execute a build,
render pixels itself, expose PDFBox objects, or own Compose gesture state.

### 5.3 `DocumentGeneration`

`DocumentGeneration` is the immutable identity and lifetime boundary for one
accepted successful build result. It contains:

- an opaque, globally unique generation identifier;
- the owning project and compilation session identities;
- the accepted `PRIMARY_PDF` artifact observation;
- a preview-owned immutable source snapshot and its content digest;
- the selected renderer-adapter identity and version;
- immutable document metadata: page count, page boxes, rotation, and the
  transforms needed between PDF page space and raster space;
- lifecycle references needed to retire the renderer document only after all
  users release it.

A generation never changes its source bytes, page count, renderer version, or
metadata. View state such as zoom, current page, scroll, selection, and focus
does not belong to it.

### 5.4 `DocumentRenderer`

`DocumentRenderer` is the small engine-neutral rendering port. It:

- opens metadata from a generation-owned immutable source;
- validates page indexes and supported render parameters;
- rasterizes one requested page at one effective scale;
- returns an immutable `RenderedPage` or a typed preview error;
- reports its safe concurrency and optional capabilities;
- releases engine resources when the generation retires.

The PDFBox adapter implements this port. It is read-only: it never modifies the
PDF, writes annotations, invokes Compose, or publishes state directly.

### 5.5 Render Scheduler

The scheduler is owned by `PreviewManager` but has a separately testable
policy. It:

- maintains bounded worker and queue capacities;
- prioritizes current visible work over speculative work;
- coalesces identical in-flight keys;
- applies memory admission before starting a render;
- cancels queued obsolete work;
- suppresses results whose generation or request token is no longer valid;
- respects the renderer's per-generation serialization rule.

It is the only route from a page request to `DocumentRenderer`.

### 5.6 `PageCache`

`PageCache` owns completed immutable raster pages under a global preview memory
budget. It:

- serves exact `RenderKey` hits;
- admits accepted results only after generation and request validation;
- tracks retained byte weight and UI-copy estimates;
- pins pages currently visible to registered views;
- evicts unpinned pages according to section 10;
- removes retired-generation entries when their outstanding view references
  end.

It does not own document truth, schedule work, convert compilation results, or
decide which generation is current.

### 5.7 Compose UI

Compose owns presentation and view-local interaction:

- viewport geometry and visible-page reporting;
- zoom mode/value, scroll anchor, focus, gestures, and keyboard commands;
- placeholders, stale/current/loading/error presentation;
- conversion or wrapping of an already-rasterized neutral image into the
  UI-owned image representation;
- overlay composition in page coordinates.

Compose sends intents to `PreviewManager` and observes immutable state. It does
not know which PDF engine is installed and never rasterizes PDF content.

## 6. Core Contracts

### 6.1 Compilation Input

Preview accepts a build result only when all of these conditions hold:

1. the result is terminal `Succeeded`;
2. it belongs to the same open project and manager subscription;
3. it contains exactly the plan-declared required artifact with role
   `PRIMARY_PDF`;
4. the observation status is `CREATED`, `MODIFIED`, or `REUSED_UNCHANGED`;
5. the artifact remains the confined readable regular file reported by the
   result while the immutable source snapshot is captured;
6. the result and generation have not been superseded or cancelled.

Failed, cancelled, quarantined, unrelated, incomplete, optional, or invalid
artifacts cannot create a generation. There is no `openPdf(path)` preview
contract.

### 6.2 Immutable Source Snapshot

After accepting a result, `PreviewManager` copies only its exact `PRIMARY_PDF`
into preview-owned runtime storage. It revalidates the confined real-file
identity and matches the size and modification evidence reported by the result
before and after the copy. A mismatch rejects the pending generation; preview
does not guess whether externally changed bytes are safe. The snapshot receives
a content digest, is never exposed as an editable document, and is opened
read-only by the renderer.

The snapshot provides three properties:

- lazy PDFBox reads cannot observe bytes from a later compilation;
- PDFBox may hold its private source open without locking the build output;
- a generation's content remains immutable until every render and view
  reference releases it.

Snapshot storage is bounded by the allowed live-generation count and cleaned
when a generation closes. Copy time, disk capacity, cleanup after abnormal
termination, and platform file semantics must be tested. The benchmark did not
measure this policy.

Every successful accepted build receives a fresh generation identity, including
`REUSED_UNCHANGED`. An implementation may deduplicate identical immutable
snapshot bytes by digest, but it may not reuse generation identity or return a
rendered page tagged with another generation.

### 6.3 Render Request

A render request contains:

- generation identifier;
- zero-based page index;
- normalized effective `RenderScale`;
- view/consumer identifier and request token;
- priority class;
- cancellation handle.

`RenderScale` captures the final raster scale after logical zoom and display
density are combined. It determines exact output pixel dimensions under fixed
page geometry and initial rendering flags. Later variable crop, rotation,
background, color, or quality settings must become part of `RenderScale` or
extend `RenderKey`; they may never vary outside the cache key.

### 6.4 Rendered Page

A successful `RenderedPage` contains:

- the exact generation, page, and scale requested;
- immutable engine-neutral pixel data;
- width, height, stride, pixel format, and retained byte weight;
- page box and transforms between PDF page, raster, and logical view space;
- renderer identity/version and optional diagnostics.

The initial pixel contract is opaque RGB in a documented byte layout. The
renderer owns no mutable buffer after return. Conversion to a Compose/Skia image
must either copy the bytes or retain an explicit lease that prevents cache
eviction from invalidating pixels still being drawn.

## 7. Official Flow

```text
Successful compilation

↓

exact PRIMARY_PDF from BuildResult

↓

new pending DocumentGeneration + immutable source snapshot

↓

metadata open and old-request invalidation

↓

lazy render of the initial visible page

↓

atomic generation promotion

↓

incremental delivery of rendered images

↓

Compose UI
```

The complete cycle is:

1. `CompilationManager` publishes a successful result after compilation
   cleanup and artifact validation.
2. `PreviewManager` validates project/session causality and selects only the
   result's exact `PRIMARY_PDF`.
3. It cancels any older pending generation, invalidates obsolete scheduled
   work, and creates a new pending generation with a private immutable source.
4. `DocumentRenderer` opens that source outside the UI thread and returns
   metadata.
5. `PreviewManager` maps the current view anchor to a valid page in the pending
   generation and requests that page at visible priority.
6. A cache miss becomes one bounded scheduled render. Its accepted result is
   inserted in `PageCache`.
7. Once metadata and the initial requested visible page are displayable,
   `PreviewManager` atomically promotes the pending generation.
8. Other visible pages arrive independently. Configured neighbors may render at
   lower priority.
9. Compose displays immutable images and reports viewport changes; it never
   opens or rasterizes the PDF.
10. The old generation remains only while pinned images or in-flight
    non-interruptible calls hold leases, then its renderer document, cached
    entries, and source snapshot close.

If preparation of the new generation fails, the previous good generation
remains visible as stale and the preview error is shown separately from the
successful compilation result. If no previous generation exists, the preview
enters a failed/unavailable state.

## 8. Generation Semantics

### 8.1 Identity

A generation is identified by its opaque generation ID, never by path,
timestamp, page count, digest alone, or current project. Two successful build
sessions create distinct generations even when their artifact bytes are
identical.

Every render request, in-flight job, cache entry, `RenderedPage`, view lease,
diagnostic, and optional future text/overlay result carries exactly one
generation ID.

### 8.2 Lifecycle

The generation lifecycle is:

```text
Pending -> Active -> Retiring -> Closed
Pending -> Failed -> Retiring -> Closed
```

- `Pending` owns a captured source and is opening metadata or preparing the
  first visible page.
- `Active` is the only generation eligible to satisfy new ordinary view
  requests.
- `Retiring` accepts no new ordinary work; existing leases drain and late
  results are suppressed.
- `Closed` has no renderer document, snapshot, cache entry, job, or view lease.
- `Failed` is a pending-generation outcome, not a source of page results; its
  resources proceed directly to retirement and close.

At most one generation is active and one is pending per project. The previous
active generation may overlap briefly as retiring during atomic handover. Side-
by-side comparison would require an explicit future lease that changes this
retention policy; it is not implicit retention.

### 8.3 Promotion and Invalidation

Promotion is atomic from the point of view of published preview state. State
never names the new generation while displaying an old generation's page as if
it were current.

When a newer accepted result arrives:

- any older pending generation is cancelled and retired;
- queued and speculative work for the active generation is invalidated;
- the active generation may remain visibly pinned while the new one prepares;
- only the pending generation may become the next active generation;
- completion from any superseded generation is discarded before cache
  admission or state publication.

Invalidation changes eligibility immediately; resource disposal waits for
leases. This avoids both mixed generations and use-after-release.

## 9. Rendering Policy

### 9.1 On-Demand and Incremental Rendering

Opening a generation loads metadata, not all page rasters. Pages render only
when requested by a visible viewport, direct navigation, or configured neighbor
prerender.

Rendering is incremental: each completed page becomes independently available
after validation. A slow or failed page does not delay unrelated pages unless
the renderer reports that document integrity is uncertain. AeTeX does not
perform default full-document rasterization.

The initial architecture renders complete pages. Tiled rendering is a possible
future capability, not an initial requirement.

### 9.2 Priority

The scheduler orders work within the current generation as follows:

1. initial page needed to promote a pending generation;
2. pages currently visible after navigation or scroll;
3. visible-page scale refinement after zoom or density change;
4. an explicit page-navigation request;
5. immediate neighbors in the current scroll direction;
6. remaining configured neighbors and other speculative work.

Within one class, newer viewport intent supersedes older intent. Current visible
work must not wait behind obsolete or speculative work.

### 9.3 Neighbor Prerender

Neighbor prerender is configurable application policy, expressed as a bounded
radius and optionally informed by scroll direction. It is not stored in shared
project configuration.

Neighbor requests:

- use lower priority than every visible request;
- are cancelled first under queue or memory pressure;
- never expand recursively;
- may be reduced to zero on constrained systems;
- are subject to the same generation, cache, and admission rules.

### 9.4 Zoom and HiDPI

Logical zoom and display density produce an effective raster scale. During
continuous zoom or resize, Compose may temporarily display the nearest cached
scale, clearly treated as a refinement state. Once interaction settles,
`PreviewManager` requests the normalized final scale.

A monitor-density change invalidates the fitness of old scales, not their
generation. Existing images may remain temporary fallbacks while higher-quality
visible pages render. Maximum width, height, and pixel area apply before any
allocation.

## 10. Page Cache

### 10.1 Key

The mandatory cache key is:

```text
RenderKey = (generationId, pageIndex, RenderScale)
```

`RenderScale` includes every initial parameter that changes pixels or output
dimensions. If later rendering options can change output, the key must be
extended before those options are supported.

The path is never a cache key. Neither page plus scale nor digest plus page may
substitute for generation identity.

### 10.2 Weight and Budget

The cache has a global preview byte budget. Entry weight includes at least:

- retained raster storage;
- row/stride padding and object overhead;
- an estimate or measured weight for a retained UI image copy;
- other directly attributable native image storage where observable.

Renderer-document and source-snapshot resources are tracked separately but
participate in global preview admission and telemetry. A page-count limit is
prohibited because page dimensions and scale determine memory, not count.

The cache uses a soft working budget and a hard allocation ceiling. Visible
pages may be pinned only within the hard ceiling. If a requested page cannot fit
safely, the system lowers the admitted scale, evicts unpinned work, or reports a
typed resource error; it never allocates without a bound.

### 10.3 Replacement

Replacement is **priority-aware, byte-weighted LRU**:

1. entries from retired generations are first candidates;
2. speculative neighbor entries are preferred over visible/recent entries;
3. among equal retention classes, the least recently used unpinned entries are
   evicted until sufficient byte weight is available;
4. visible entries and entries with active UI leases are pinned;
5. a single entry larger than the hard admissible size is rejected.

Soft/weak references are not the primary policy because garbage collection does
not provide predictable limits or account reliably for native/UI memory.

### 10.4 Invalidation

- A new generation makes old entries ineligible for its requests.
- Generation retirement evicts unpinned entries immediately and pinned entries
  when leases end.
- Zoom or layout changes cancel obsolete requests but do not automatically
  discard reusable scales.
- Density changes reprioritize scale refinement.
- Renderer-version or pixel-contract changes create different keys and clear
  incompatible entries.
- Project close clears every project entry after leases drain.
- Memory pressure evicts unpinned entries regardless of their nominal age.

Invalidation never releases pixels still leased by Compose.

## 11. Concurrency and Cancellation

### 11.1 Isolation

PDF open, metadata extraction, hashing/copying, page rasterization, and raster
conversion execute outside the Compose UI thread. Compose receives only small
immutable state publications and already-rendered image data.

`DocumentRenderer` cannot mutate `PreviewManager`, `PageCache`, or Compose. It
returns a value to the scheduler, which performs all validity checks before
cache admission and publication.

### 11.2 Bounded Scheduler

The scheduler has:

- a finite worker count;
- a finite priority queue;
- per-generation concurrency constrained by the renderer;
- memory admission before dispatch;
- backpressure that discards or refuses speculative work before visible work;
- no unbounded executor, thread creation, or request accumulation.

For PDFBox, calls against one generation's document are serialized. Different
generations may use separate documents during handover, but global worker,
memory, and live-generation limits still apply.

### 11.3 No Duplicate Rendering

There is at most one in-flight job for one `RenderKey`. Concurrent consumers
share that job. A consumer may cancel its interest without cancelling the
shared operation while another valid consumer remains.

Near-equivalent scales may reuse an old completed image temporarily, but they
are not the same final job unless their normalized `RenderScale` is equal.

### 11.4 Cancellation

Cancellation removes queued work immediately. If the engine call has started,
the adapter uses cooperative cancellation only where the selected PDFBox API
supports it safely. Otherwise the operation may finish physically, but its
result is logically cancelled and discarded.

Every job captures generation ID, `RenderKey`, consumer set, and request token.
Before cache insertion and again before publication, the scheduler verifies
that:

- the generation is still eligible;
- the key is still requested;
- at least one intended consumer remains valid;
- the job was not cancelled or superseded.

A cancelled job never publishes a result. The system must distinguish
“execution stopped” from “publication suppressed” in diagnostics.

## 12. Compose Integration

Compose observes a presentation model containing:

- current generation and current/stale status;
- page count and page geometry;
- per-page loading, ready, refinement, and failure state;
- immutable rendered images or leases;
- coordinate transforms;
- preview-level diagnostics suitable for display.

Compose emits intents containing:

- current viewport and visible pages;
- view-local zoom and layout mode;
- navigation/scroll target;
- retry or cancellation;
- pointer coordinates for future page-space translation.

The UI never:

- opens a file or PDF document;
- chooses the `PRIMARY_PDF`;
- sees `PDDocument`, `PDFRenderer`, or `BufferedImage`;
- invokes `DocumentRenderer`;
- manages renderer threads or caches;
- rasterizes PDF vectors, text, images, or annotations;
- modifies the PDF.

Multiple composables may eventually observe one generation, but each view owns
its own zoom, anchor, focus, selection, and history.

## 13. Preview State and Failures

The UI-facing state distinguishes at least:

- no project;
- waiting for a ready configuration or successful build;
- build queued/running while no preview exists;
- build queued/running while a previous preview remains visible;
- pending generation loading;
- current generation ready;
- previous generation visible but stale after failed/cancelled compilation;
- previous generation visible but stale after preview preparation failure;
- page-specific render failure;
- resource limit reached;
- renderer unavailable or generation closed.

Compilation errors and preview errors remain separate. A failed build cannot
promote a generation. A successful build followed by snapshot, open, or render
failure remains a successful build with a preview failure.

Deterministic render failures are not retried in a loop. An explicit user retry
may create new work only while the same generation remains valid. Errors carry
generation, page/scale when relevant, renderer version, and a technical cause
without exposing native handles.

## 14. Resource Lifecycle

Project close or replacement:

1. unsubscribes `PreviewManager` from the old `CompilationManager`;
2. rejects new intents for that project;
3. cancels queued work and suppresses late results;
4. releases view pins and image leases;
5. closes renderer documents after in-flight calls return;
6. evicts page-cache entries;
7. deletes generation snapshots through bounded, recoverable runtime cleanup.

Application startup cleans abandoned preview snapshots only from the exact
application-owned preview runtime namespace. It never recursively cleans a
project output directory.

The preview snapshot exists to prevent the renderer from locking or observing
replacement of the compilation output. Failure to delete a private snapshot is
a preview-storage diagnostic, not permission to delete or rewrite the
`PRIMARY_PDF`.

## 15. Future Integration Boundaries

These boundaries prepare future features without implementing them.

### 15.1 SyncTeX

SyncTeX must use the `SYNCTEX` artifact from the same compilation session as the
generation's `PRIMARY_PDF`. Forward and inverse synchronization operate in PDF
page coordinates using generation metadata and transforms, never in cached
raster pixels. A SyncTeX result tagged for another generation cannot be shown.

### 15.2 Search and Text

Search and selection may use an optional engine capability or a separate text
service. Results must carry generation, page, page-space geometry, and
capability/version identity. Text extraction is not added to the minimal
`DocumentRenderer` raster contract until evidence defines it.

### 15.3 Annotations

Annotations are future AeTeX-owned overlays in page coordinates. They are kept
outside the compilation PDF and composed above the rendered page. Export or PDF
mutation would require a separate architecture.

### 15.4 Multiple Views

Views may share a generation, in-flight render, and matching cache entry while
retaining independent zoom, scroll, navigation, and selection state. Cache pins
are consumer-aware so one view cannot invalidate another view's visible image.

### 15.5 Side-by-Side Comparison

Comparison requires explicit leases for both generation identities and
participates in the same global memory and scheduling limits. Pages from the two
generations remain visibly labeled and are never combined into one view state
or cache identity.

### 15.6 Plugins

Future plugins may receive stable generation/session identities, declared
capabilities, bounded navigation intents, page-coordinate events, read-only
overlay hooks, or quota-controlled raster requests. They never receive:

- PDFBox or native handles;
- mutable pixel buffers;
- arbitrary filesystem-open capability;
- direct cache mutation;
- permission to modify the compiled PDF.

A renderer-plugin API is not accepted by this document. It requires a later
contract for trust, binary provenance, compatibility, quotas, and failure
isolation.

## 16. Invariants

The following invariants are mandatory:

1. One page image belongs to exactly one document generation.
2. One render job belongs to exactly one generation and one `RenderKey`.
3. A generation's PDF bytes and metadata never change.
4. The renderer never modifies or calls the UI.
5. The preview never modifies the PDF or any compilation artifact.
6. Every displayed image comes from a currently valid generation or is
   explicitly labeled as the pinned last-good stale generation.
7. The cache never returns or retags an entry from another generation.
8. Cancelled, superseded, retired, or closed-generation jobs never publish
   results.
9. At most one job exists for an identical in-flight `RenderKey`.
10. Compose never opens a PDF or rasterizes PDF content.
11. Preview updates originate only from successful `BuildResult` values and
    their exact `PRIMARY_PDF`.
12. A failed or cancelled build never creates or promotes a generation.
13. A renderer document never reads from the replaceable build output.
14. Cache, queue, workers, raster area, and live generations are bounded.
15. A generation is not closed while a render, cache/UI lease, or registered
    view still references it.
16. Generation promotion is atomic and never mixes old and new page state.

## 17. Risks and Mitigations

| Risk | Architectural response | Remaining validation |
| --- | --- | --- |
| Huge or complex PDFs | Lazy pages, pixel-area limits, bounded queue/workers, admission control, page-local errors | Representative long documents and pathological pages |
| Heap, native, UI, or snapshot memory/disk growth | Byte-weighted global cache, hard ceiling, live-generation limit, snapshot cleanup, telemetry | Tune budgets on low- and high-memory systems |
| Compilation output locked by renderer | Render only from a private immutable snapshot | Windows replacement and antivirus behavior |
| Snapshot copy is slow or disk is full | Background copy, capacity checks, typed failure, last-good preview | Large-file and abnormal-shutdown tests |
| Platform font/color/Java2D differences | Common geometry contract, visual corpus, documented tolerances | Windows, Linux, macOS and HiDPI matrix |
| PDFBox version changes output or behavior | Pin version, record adapter version in generation/key, rerun benchmark and visual corpus | Upgrade policy and regression thresholds |
| PDFBox or optional-codec vulnerability | Dependency monitoring, bounded inputs, timely upgrades, optional worker isolation if evidence requires it | Security-response and malformed corpus |
| License incompatibility in optional dependencies | Apache-2.0 core selection; explicit per-codec review before inclusion | Distribution SBOM and legal review |
| HiDPI causes excessive allocation or blurry pages | Effective density-aware scale, refinement, maximum pixel area, reusable fallback | Fractional scale and cross-monitor tests |
| Non-interruptible render delays newer work | Per-document serialization, bounded workers, logical cancellation and stale suppression | Worst-page latency and shutdown tests |
| Compose retains evicted pixels | Immutable copy or explicit image lease/pinning | Skia/Compose lifecycle tests |
| In-process malformed PDF destabilizes the JVM | Resource limits, current engine, diagnostics; future out-of-process option | Corpus/fuzz evidence and timeout research |

The private snapshot removes the normal build-output lock from the renderer but
does not eliminate all platform file behavior: snapshot creation still reads
the exact output once, and private-file deletion may be delayed while PDFBox is
closing.

## 18. Benchmark Relationship and Reevaluation

The [rendering benchmark](../../tools/rendering-benchmark/README.md) is the
experimental evidence for the engine decision. This RFC summarizes the
decision-relevant outcome and limitations; it intentionally does not reproduce
the report's timing or memory tables.

The benchmark remains an engineering tool, separate from product code. It must
be rerun when:

- upgrading the selected PDFBox version;
- reconsidering PDFium or another engine;
- changing the neutral pixel bridge materially;
- adding a supported OS/CPU architecture;
- a representative user PDF exposes unacceptable latency, fidelity, memory,
  crash, or codec behavior.

An engine reevaluation compares adapter-level first-visible-page latency,
successive visible pages, scale refinement, peak memory where accurately
measurable, robustness, and inspected visual output. It must also include
production integration, packaging, licenses, security updates, and maintenance;
a single faster timing does not override those costs.

PDFium becomes the preferred replacement candidate if repeated representative
evidence shows that PDFBox cannot meet product thresholds on supported
platforms and PDFium's improvement is large enough to justify a maintained JVM
binding and native binary supply chain. Such a change requires a new accepted
decision and benchmark evidence, but not a redesign of generations, scheduling,
caching, or Compose.

## 19. Out of Scope

This architecture does not implement or authorize:

- SyncTeX;
- watch mode or filesystem-driven preview reload;
- automatic builds or auto-build;
- PDF annotations;
- PDF editing or any PDF mutation;
- side-by-side comparison;
- plugins or renderer plugins;
- OCR;
- search or text selection;
- tiled rendering;
- an out-of-process renderer;
- arbitrary PDF opening;
- preview implementation code.

External modification of a built PDF is not an update source. A later explicit
“open standalone PDF” product feature would require a separate trust, identity,
and lifecycle architecture rather than bypassing `CompilationManager`.

## 20. Consequences

The accepted design adds an explicit preview coordinator, immutable snapshot
storage, generation lifecycle, bounded scheduling, weighted caching, and a
renderer adapter before the first production page is displayed. That complexity
is required to make rebuild races, file locks, stale results, and memory limits
observable rather than accidental.

PDFBox gives AeTeX one common JVM engine and the smallest operational surface
supported by the audited evidence. The neutral boundary preserves an exit path
if broader real-world evidence later justifies PDFium. Compose stays a
presentation consumer, compilation stays the artifact authority, and future
page-space features can evolve without depending on raster resolution or
engine handles.
