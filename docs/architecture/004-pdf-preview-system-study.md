# AeTeX Architecture Study 004: PDF Preview System

| Field | Value |
| --- | --- |
| Identifier | AeTeX Architecture Study 004 |
| Status | Study |
| Date | 2026-07-30 |
| Scope | PDF lifecycle, rendering engines, preview coordination, change detection, caching, rendering policy, Compose Desktop integration, concurrency, portability, and future extension |

This document explores architecture alternatives for the AeTeX PDF preview
system. It is comparative rather than normative: it does not select a renderer,
define an implementation API, or replace a future accepted Architecture 004.
Names used for possible components describe responsibilities, not committed
classes.

The question is:

> How should AeTeX turn the validated PDF artifact of a compilation into a
> responsive, stable, portable preview without coupling compilation, rendering,
> and Compose UI?

The final section gives a provisional recommendation and identifies the
evidence still required before making a decision.

## Repository Baseline and Constraints

[Architecture 000](000-vision.md) defines AeTeX as a local desktop IDE that
integrates established tools. The edit-compile-preview loop should remain
responsive and predictable, expensive work must stay outside the UI thread, and
missing optional capabilities must degrade explicitly.

[Architecture 001](001-project-model.md) makes the project directory the unit
of work and separates filesystem access, state coordination, and UI. Generated
PDFs appear in the project tree but are not editable text documents.

[Architecture 002](002-project-configuration-system.md) resolves one confirmed
main document and one confined output directory. Preview must not reinterpret
project configuration or repeat main-document detection.

[Architecture 003](003-compilation-system.md) supplies the authoritative input
contract:

- every terminal compilation publishes one immutable `BuildResult`;
- the result inventories the exact required `PRIMARY_PDF` artifact;
- success requires the PDF to be confined, readable, regular, and valid under
  the compilation artifact contract;
- the result belongs to one immutable build session and output-space identity;
- Preview must consume build lifecycle and result identity, never parse logs or
  guess which PDF in an output directory is current;
- an unchanged PDF may legitimately be reported as `REUSED_UNCHANGED` after a
  successful no-op build;
- failed or cancelled builds may leave an older PDF on disk, but that stale file
  does not become the result of the failed session;
- while an output is quarantined, read-only artifact inspection is allowed but
  AeTeX must not write there.

The implemented
[`CompilationManager`](../../src/main/kotlin/dev/aetex/compilation/CompilationManager.kt)
already offers session listeners and immutable snapshots. The implemented
[`BuildResult`](../../src/main/kotlin/dev/aetex/compilation/CompilationModel.kt)
contains typed artifact observations. The current
[`PreviewPanel`](../../src/main/kotlin/dev/aetex/ui/panels/PreviewPanel.kt) is
only a static placeholder, so there is no preview behavior whose compatibility
must be preserved.

The application currently uses JVM 21 and Compose Desktop. The preview study
therefore favors solutions that can be distributed on Windows, Linux, and
macOS, work offline, and expose pixels or another Compose-compatible
presentation without making the rendering engine part of UI state.

## 1. Preview Objectives

### 1.1 Functional objectives

The initial preview should support:

- opening the exact PDF from the latest accepted successful build result;
- showing document loading, ready, stale, unavailable, and failed states;
- continuous vertical navigation and direct navigation to a page;
- preservation of a meaningful reading position across compatible rebuilds;
- zoom in, zoom out, actual size, fit width, and fit page;
- mouse, trackpad, keyboard, and scrollbar navigation;
- smooth scroll while visible pages are still being refined;
- automatic refresh after compilation without exposing a partially written
  output;
- explicit recovery when the new PDF cannot be opened or rendered;
- bounded resource use for large or image-heavy documents.

Opening an arbitrary PDF from the project tree could be useful later, but it is
not the primary lifecycle studied here. The first integration is
build-result-driven.

### 1.2 Quality objectives

The preview should optimize for:

- **time to first useful page**, rather than time to rasterize the complete
  document;
- **interaction latency**, so scroll and zoom input remain responsive;
- **visual correctness**, especially embedded fonts, vector graphics,
  transparency, color, rotation, and LaTeX-generated links;
- **stable reload**, keeping the previous good image visible until a replacement
  generation is ready;
- **bounded memory**, measured from actual raster bytes and renderer-owned
  resources rather than entry count;
- **controlled failure**, so one malformed page does not crash the UI or falsely
  replace the last good preview;
- **portable semantics**, even if pixel-level output differs by platform.

These goals compete. Rendering every page at maximum display density minimizes
later latency but makes first display slower and memory use unbounded. Rendering
only at low resolution is responsive but visibly degrades text during zoom.
The architecture must make this policy adjustable and measurable.

### 1.3 Suggested evaluation measures

A later spike should record at least:

- document-open time;
- time to metadata and page count;
- time to first visible page;
- time until all currently visible pages are sharp;
- p50 and p95 page-render time by page type and scale;
- scroll frame stability while rendering;
- peak JVM heap, native memory, and total resident memory;
- cache hit rate and eviction rate;
- cancellation latency and stale-result suppression;
- reload latency after a compilation;
- visual differences against a trusted reference renderer;
- crash and render-failure rate over a representative corpus.

Targets should be established from measurements on supported hardware. This
study should not invent fixed millisecond or memory thresholds without that
evidence.

### 1.4 Initial non-objectives

The first preview need not provide:

- PDF editing;
- persistence of modifications into compiler-owned output;
- full annotation authoring;
- form filling or JavaScript execution;
- digital-signature validation;
- printing;
- browser-equivalent PDF accessibility;
- simultaneous rendering of every page at every zoom;
- a public plugin API before preview contracts stabilize.

Search, SyncTeX, annotations, multiple views, and plugins are future
considerations in section 9, not requirements to implement all of them now.

## 2. Rendering Engines

### 2.1 Evaluation criteria

Engine selection affects more than image quality. A comparison should cover:

- rendering speed for vector-heavy, text-heavy, and image-heavy pages;
- visual fidelity and format coverage;
- font discovery and substitution;
- HiDPI behavior and control over raster scale;
- incremental document access and per-page rendering;
- text, link, annotation, and coordinate information for future features;
- cancellation and thread-safety constraints;
- JVM and Compose integration cost;
- operating-system and CPU coverage;
- native library packaging, signing, and update work;
- security response and malformed-input behavior;
- license compatibility and transitive licenses;
- upstream activity and API stability;
- AeTeX's long-term maintenance burden.

License summaries below are screening information, not legal advice. Any chosen
binary and all of its transitive components require a release-time license
audit.

### 2.2 Comparative summary

| Alternative | Performance and fidelity | Portability | JVM / Compose integration | License and dependencies | Main maintenance risk |
| --- | --- | --- | --- | --- | --- |
| Apache PDFBox 3.x | Adequate candidate for ordinary LaTeX output; complex pages and high DPI may be CPU- and memory-intensive; output can vary with Java2D and available fonts | Same Java implementation across all targets | Direct JVM dependency; pages render to `BufferedImage`, which can be adapted to UI images | Apache-2.0; pure JVM core, with optional ImageIO codecs carrying separate terms | Performance, per-document serialization, optional codec coverage, and renderer-specific visual defects |
| PDFium | Strong performance/fidelity candidate with a mature page-raster API; Chromium-scale PDF corpus and security attention are advantages | Source supports Windows, Linux, and macOS, but AeTeX must provide binaries per OS and CPU | Requires JNI, Panama FFM, or a maintained wrapper; pixels must cross a native boundary | BSD-style project license, with a required audit of bundled third-party components; native toolchain | Building, publishing, signing, loading, updating, debugging, and securing native binaries and bindings |
| PDF.js | Mature browser viewer, built-in page-oriented async model, HiDPI canvas pattern, and strong future text/search UI potential | Consistent inside a compatible embedded web runtime | Requires an embedded browser and a JVM↔JavaScript bridge, or a separate viewer surface | Apache-2.0; JavaScript assets plus the chosen browser runtime and its licenses | Runtime size, UI interop, lifecycle bridging, accessibility split, and browser security/update cadence |
| Platform-native engines | Potentially excellent local performance, integration, and system behavior | Different API and behavior on every OS; Linux has no single built-in equivalent | A platform adapter and often native UI interop are required on each target | OS frameworks on Windows/macOS; Linux choices such as Poppler have separate license constraints | Three implementations, divergent bugs, inconsistent features, and a multiplied test matrix |
| MuPDF or commercial SDK | Often strong performance, compact native design, and broad feature sets | Usually available across desktop platforms through native or vendor bindings | Native or vendor-specific binding and packaging | MuPDF is AGPL unless commercially licensed; commercial SDK terms and cost vary | License fit, vendor dependency, native operations, and long-term cost |

No row is a decision. Performance and fidelity claims must be validated using
the same AeTeX corpus, dimensions, color settings, and hardware.

### 2.3 Apache PDFBox

[Apache PDFBox](https://pdfbox.apache.org/) is a Java library under the Apache
License 2.0. Its renderer can produce page images at a selected scale or DPI.
PDFBox 3.x incrementally parses document data, which reduces initial memory when
only part of a PDF is touched, according to its
[3.0 migration guide](https://pdfbox.apache.org/3.0/migration.html).

Advantages:

- it fits the current JVM 21 build and dependency model directly;
- one Java integration can run on Windows, Linux, and macOS;
- no AeTeX-owned JNI ABI or native binary distribution is required;
- Java objects, exceptions, lifecycle, and profiling are comparatively easy to
  observe;
- page rendering to an image is a natural match for an engine-neutral raster
  boundary;
- the library also exposes text, links, page geometry, and annotations that
  could support future overlays without requiring them in the first milestone;
- Apache licensing is straightforward for an ordinary desktop distribution.

Disadvantages and uncertainties:

- PDFBox explicitly states that one document may be accessed by only one thread
  at a time in its [FAQ](https://pdfbox.apache.org/3.0/faq.html). Parallel page
  rendering therefore needs serialization per document or multiple separately
  loaded document instances, with corresponding memory and I/O cost;
- raster memory rises with the square of scale, and PDFBox documents can retain
  decoded resources in addition to AeTeX's page cache;
- complex transparency, shadings, large images, unusual fonts, and color
  conversion can be slower than native engines;
- Java2D font and color behavior can vary with installed platform resources;
- PDFBox itself warns that malformed input can cause unchecked failures or
  excessive CPU/memory even though it does not intend to permit privilege
  escalation; see its [security model](https://pdfbox.apache.org/security.html);
- full support for embedded JBIG2 and JPEG 2000 images needs optional components.
  The [dependency documentation](https://pdfbox.apache.org/3.0/dependencies.html)
  requires separate license review for those codecs;
- cancellation may be cooperative only between page renders if a single
  rendering call offers no interruption point.

PDFBox has the lowest integration risk, but that does not establish that its
rendering quality and latency are sufficient. It is a strong baseline candidate
precisely because it lets AeTeX test the rest of the architecture without first
building native distribution infrastructure.

### 2.4 PDFium

[PDFium](https://pdfium.googlesource.com/pdfium/) is Chromium's C++ PDF engine.
Its public headers expose document, page, bitmap, text, link, and rendering
operations. `FPDF_RenderPageBitmap` renders into a caller-supplied
device-independent bitmap, while the
[getting-started guide](https://pdfium.googlesource.com/pdfium/+/HEAD/docs/getting-started.md)
shows explicit global library, document, and page lifecycles.

Advantages:

- mature native rasterization and a likely high performance ceiling;
- page rendering accepts explicit pixel dimensions, rotation, flags, and
  clipping, which fits lazy HiDPI rendering and potential tiled rendering;
- broad real-world use through Chromium provides useful format and security
  pressure;
- public embedding headers are kept separate from internal APIs; PDFium says it
  endeavors to keep the public surface stable;
- future text and coordinate operations can remain behind the same AeTeX
  capability boundary.

Disadvantages and uncertainties:

- there is no direct official JVM API. AeTeX would need to choose and own a JNI
  or FFM bridge, or depend on a third-party wrapper with its own release cadence;
- the official build uses Chromium's GN/Ninja/depot_tools toolchain and Clang.
  This is significantly more operational work than a Maven dependency;
- AeTeX would need reproducible native builds for every supported
  OS/architecture, resource extraction/loading, ABI checks, crash diagnostics,
  notarization or code signing where applicable, and security updates;
- a native memory error can terminate the process rather than becoming an
  ordinary Kotlin exception;
- ownership rules for global initialization, document/page handles, buffers,
  and shutdown must be modeled carefully;
- public APIs may still be marked experimental. PDFium's
  [API contribution policy](https://pdfium.googlesource.com/pdfium/+/main/CONTRIBUTING.md)
  distinguishes experimental, stable, and deprecated calls;
- PDFium has a BSD-style
  [top-level license](https://pdfium.googlesource.com/pdfium/+/main/LICENSE),
  but a shipped build also requires auditing the licenses and notices of
  enabled third-party components.

PDFium should be treated as the primary native comparator. It becomes more
attractive if measurement shows that PDFBox cannot meet first-page latency,
scroll refinement, memory, or fidelity requirements.

### 2.5 PDF.js

[PDF.js](https://mozilla.github.io/pdf.js/) has a core layer, a public display
layer, and a complete viewer layer. The
[official examples](https://mozilla.github.io/pdf.js/examples/) demonstrate
asynchronous per-page rendering and multiplying canvas pixels by device pixel
ratio for HiDPI output. It is licensed under
[Apache-2.0](https://github.com/mozilla/pdf.js/blob/master/LICENSE).

Advantages:

- it already embodies document loading, page navigation, zoom, progressive
  rendering, text layers, links, search, and viewer behavior;
- its browser canvas model has an explicit, well-understood HiDPI path;
- a web worker separates parsing/render preparation from the browser UI thread;
- behavior is broadly consistent when the same embedded browser version is used
  on all desktop platforms;
- adopting parts of the viewer could accelerate future search, selection,
  accessibility, and annotation UI.

Disadvantages and uncertainties:

- AeTeX is a Compose/JVM desktop application, not a browser. PDF.js needs an
  embedded browser such as JCEF or another web runtime, with a large
  distribution and security footprint;
- Compose focus, keyboard shortcuts, scroll, theming, accessibility, drag/drop,
  clipboard, and lifecycle would cross an interop boundary;
- a JavaScript bridge must associate build generations, errors, navigation, and
  future SyncTeX coordinates without race conditions;
- `file:` loading restrictions encourage an internal resource-serving or byte
  transfer mechanism, which must remain local and properly scoped;
- memory accounting spans JVM heap, browser processes, JavaScript heap, canvas
  surfaces, and GPU resources;
- customizing the complete viewer may create a long-lived fork, while using only
  the display layer means AeTeX still builds most UI behavior itself;
- startup time and package size may be disproportionate to the first preview
  milestone.

PDF.js is not a natural raster-library dependency for Compose Desktop. It is a
credible alternative architecture in which the preview is an embedded viewer
subsystem. That alternative becomes stronger if rich browser-style PDF
interaction is valued more than native Compose integration.

### 2.6 Platform-native engines

On macOS, Apple's
[`PDFView`](https://developer.apple.com/documentation/pdfkit/pdfview) provides a
native view with document display, selection, zoom, page navigation, history,
and coordinate conversion. On Windows,
[`Windows.Data.Pdf`](https://learn.microsoft.com/en-us/uwp/api/windows.data.pdf?view=winrt-26100)
provides page-to-image rendering and related dimensions/options, with deeper COM
or DirectX integration for some scenarios.

Linux has no one equivalent OS framework. Poppler is a widely used
[PDF rendering library](https://poppler.freedesktop.org/) with C++, GLib, and Qt
APIs, but its distribution and license family require careful evaluation for
AeTeX. Choosing it only for Linux would still leave independent Windows and
macOS implementations.

Advantages:

- platform-native user interaction and accessibility may be excellent;
- system frameworks can provide optimized rendering and familiar behavior;
- macOS `PDFView` already solves much of the viewer UI;
- OS vendors own part of the security and compatibility update path.

Disadvantages:

- the abstraction must hide fundamentally different capabilities, not merely
  different implementations of the same calls;
- navigation, link handling, selection, search, zoom limits, annotations, and
  rendering output will diverge;
- embedding native views inside Compose introduces focus, clipping, z-order,
  density, and input interoperability concerns;
- Linux requires an additional packaged dependency;
- bugs need to be reproduced and fixed through different platform paths;
- cross-platform pixel consistency and automation become difficult.

A native-per-platform strategy optimizes local integration at the cost of the
largest product-level consistency burden. It is more suitable as a future
optional accelerator than as an initial common foundation unless a shared
engine proves inadequate.

### 2.7 MuPDF, Poppler, and commercial SDKs

MuPDF and Poppler are relevant performance/fidelity comparators, but their
licenses and native integration deserve early screening. MuPDF's official
[release page](https://mupdf.com/releases) states that embedding uses AGPL or a
commercial license. Poppler's source includes GPL-family terms and multiple
component notices. Neither should enter an AeTeX distribution without a precise
legal and dependency review.

Commercial SDKs may provide supported JVM bindings, annotations, search, and
high fidelity, but add recurring cost, redistribution terms, license-key
operations, and vendor lock-in. They are a fallback if open-source engines
cannot meet measurable requirements, not a neutral default.

### 2.8 HiDPI implications common to all engines

Logical Compose size and raster pixel size must be distinct. A page displayed at
a logical zoom should normally be rendered using:

- the page's physical PDF dimensions;
- the view zoom or fit mode;
- the current Compose density / device scale;
- a bounded quality multiplier;
- the page rotation and crop box;
- a maximum pixel-area safety limit.

Rendering only at 72 DPI and enlarging the bitmap makes text blurry. Rendering
at device density gives sharp output but can be expensive. A typical A4 page of
approximately 595 by 842 PDF points needs about 7.6 MiB as an uncompressed RGBA
raster at 144 DPI and about 30.6 MiB at 288 DPI, before engine and GPU copies.
A few cached pages can therefore dominate application memory.

Moving a window between displays or changing OS scale invalidates the preferred
raster scale even when semantic zoom is unchanged. The preview should keep the
old image temporarily, request the new scale, and swap only after the
replacement is ready.

## 3. Architecture Alternatives

### 3.1 Alternative A: UI-owned viewer

In the simplest model, the Compose panel observes compilation, opens the PDF,
calls the renderer, stores images, and implements navigation and caching.

Advantages:

- few types and little initial ceremony;
- easy to prototype one page;
- UI state is immediately available where it is displayed.

Disadvantages:

- compilation, filesystem, renderer, cache, concurrency, and view lifecycle
  become entangled;
- recomposition can accidentally trigger expensive or repeated work;
- engine handles or `BufferedImage` values leak into presentation state;
- multiple views duplicate document loads and caches;
- unit testing requires Compose;
- future SyncTeX and plugins would depend on UI internals;
- cancellation and disposal races are hard to centralize.

This is acceptable for a disposable spike, not a durable preview subsystem.

### 3.2 Alternative B: engine-specific viewer service

One service owns the selected engine, document, page cache, and background
rendering. Compose observes that service.

Advantages:

- expensive work leaves the UI;
- engine lifecycle and thread-safety are centralized;
- smaller design than a fully layered system;
- can deliver a first implementation quickly.

Disadvantages:

- cache keys, errors, concurrency, and future features tend to expose
  engine-specific concepts;
- changing engines may require rewriting the coordination layer;
- document lifecycle and UI-view lifecycle can still be conflated;
- multiple-view and plugin contracts are difficult to add cleanly later.

This is viable if its public surface is deliberately engine-neutral. Without
that discipline it becomes a lock-in point.

### 3.3 Alternative C: layered preview subsystem

A layered model separates coordination, document ownership, page rendering,
caching, and presentation:

```text
CompilationManager
        |
        | terminal BuildResult + exact PRIMARY_PDF
        v
Preview coordinator
        |
        +---- document repository / generation lifecycle
        |
        +---- render scheduler ---- renderer port ---- engine adapter
        |
        +---- bounded page cache
        |
        v
engine-neutral preview state
        |
        v
Compose presentation and image adapter
```

Possible responsibilities are:

- **Preview coordinator / `PreviewManager`:** subscribes to build results,
  selects an accepted preview generation, preserves or maps view state, exposes
  document and error states, and owns shutdown;
- **document repository / `DocumentCache`:** opens an immutable document
  generation, exposes metadata and page geometry, shares it across views, and
  closes it only after outstanding work releases it;
- **renderer port / `Renderer`:** accepts an engine-neutral page request and
  returns an immutable raster plus geometry and diagnostics;
- **render scheduler:** prioritizes visible work, coalesces duplicates, limits
  concurrency, applies cancellation/generation checks, and invokes the renderer;
- **page cache:** stores completed raster results under a byte budget and never
  owns the authoritative document identity;
- **Compose presentation:** owns view-local zoom, scroll, focus, gestures, and
  presentation state, then turns neutral rasters into Compose images.

Advantages:

- renderer choice remains replaceable and comparable;
- compilation integration does not depend on Compose or a PDF library;
- cache and scheduler policies can evolve independently;
- document generations make reload races explicit;
- multiple views can share immutable document and page work;
- tests can use a fake renderer and deterministic scheduler;
- future SyncTeX, search, and overlays can reuse geometry without receiving
  native engine handles.

Disadvantages:

- more lifecycle concepts must be designed before the first page appears;
- over-generalizing a port before testing real engines can create speculative
  abstractions;
- neutral raster conversion may introduce an extra memory copy;
- capabilities differ between engines, so a lowest-common-denominator interface
  can become restrictive;
- ownership and close semantics require careful documentation.

This division best matches existing AeTeX boundaries, provided the initial port
is intentionally small and capability extensions are evidence-driven.

### 3.4 Alternative D: embedded viewer boundary

An embedded PDF.js or native viewer can be treated as a self-contained surface.
AeTeX sends it the current build generation and high-level navigation commands,
then receives state and interaction events.

Advantages:

- delegates caching, layout, selection, and much viewer behavior;
- can reach rich PDF interaction sooner;
- fewer page-image objects pass through Compose.

Disadvantages:

- the viewer becomes a second UI framework with its own state model;
- SyncTeX and AeTeX shortcuts must cross a bridge;
- styling, accessibility, input, tests, and failure handling split across
  runtimes;
- swapping from the embedded component later is a presentation rewrite, not
  only a renderer change.

This is architecturally coherent, but materially different from a
Compose-native page viewer. The choice should be explicit rather than allowing
an embedded viewer to masquerade as a renderer adapter.

### 3.5 Suggested neutral concepts

The following concepts appear useful across alternatives, without fixing their
eventual names or signatures:

- **artifact reference:** session ID, exact path, artifact status, output-space
  identity, size/modification evidence, and build completion time;
- **document generation:** an opaque identity created for one accepted artifact
  version; never just a path;
- **document metadata:** page count, per-page boxes, rotation, permissions, and
  optional capability flags;
- **view state:** generation, current/anchor page, page-space anchor coordinate,
  zoom mode, zoom value, layout mode, and scroll intent;
- **render request:** generation, page index, pixel dimensions or scale,
  rotation, crop, background/alpha policy, and priority;
- **rendered page:** generation, page index, exact render parameters, immutable
  pixels, pixel format, dimensions, stride, and page-space transform;
- **preview state:** no project, waiting for build, loading, ready, stale-ready,
  failed-with-previous, and unavailable;
- **capabilities:** text extraction, links, annotations, partial/tiled render,
  cancellable render, and other optional behavior discovered from the adapter.

The core should not expose `PDDocument`, `FPDF_DOCUMENT`, JavaScript objects,
`PDFView`, `BufferedImage`, `ImageBitmap`, or other engine/UI handles.

## 4. Change Detection and Reload

### 4.1 Direct notification from `CompilationManager`

The preview observes terminal session snapshots and accepts only a successful
result with the exact valid `PRIMARY_PDF` observation.

Advantages:

- strongest identity and causality;
- preview never opens a half-written artifact;
- failed/cancelled sessions cannot promote a stale file;
- no filesystem race is needed to discover which PDF belongs to which build;
- session identity is already available for future SyncTeX.

Disadvantages:

- it does not detect external changes to the PDF;
- a listener attached after publication needs a current-result replay or
  explicit lookup;
- project close and manager retirement need coordinated subscription disposal.

This is the natural primary mechanism because it follows the Architecture 003
contract.

### 4.2 File watcher

A watcher monitors the exact active artifact or its parent directory.

Advantages:

- detects external rebuilds or replacements;
- can recover from integrations that do not originate in the active
  `CompilationManager`;
- avoids constant reads when the platform reports changes reliably.

Disadvantages:

- events can be duplicated, coalesced, reordered, or reported against the
  directory rather than the final file;
- atomic replacement can appear as delete/create/rename sequences;
- network and unusual filesystems differ;
- an event says that something changed, not that the file is complete, valid, or
  associated with a successful AeTeX build;
- watching a parent directory risks accidentally selecting the wrong PDF.

A watcher should be a hint followed by validation, never the authority for
build-result selection.

### 4.3 Polling

Polling periodically compares file identity, size, modification time, or a
content fingerprint.

Advantages:

- simple fallback where watcher delivery is unreliable;
- can detect a missed event;
- behavior can be made deterministic in tests.

Disadvantages:

- adds latency or background I/O;
- metadata alone can miss replacements or produce false positives;
- hashing large PDFs repeatedly is expensive;
- it still cannot establish compilation causality or safe completion.

Continuous polling is disproportionate for ordinary AeTeX-produced builds.

### 4.4 Hybrid strategies

Two hybrids are plausible:

1. direct build-result notification is authoritative, while a watcher only
   marks the displayed PDF as externally changed and offers or performs a
   separately validated reload;
2. direct notification triggers immediate load, while a narrowly bounded
   post-event retry handles transient Windows locking or delayed visibility.

The second must not reinterpret an invalid artifact as successful. Compilation
already validates readability before publishing success; retries address the
preview engine's open behavior, not compilation correctness.

### 4.5 Provisional reload semantics

A safe sequence would be:

1. receive a successful result and exact PDF observation;
2. create a new pending document generation;
3. open and validate metadata in the background;
4. request the first visible page;
5. keep the last good generation visible during these steps;
6. atomically promote the new generation only when it is displayable;
7. cancel or suppress older pending generations;
8. close the old generation after no render or view still references it.

For `REUSED_UNCHANGED`, the coordinator can associate the existing document
generation with the newer successful session without reopening or dropping the
cache, provided identity checks confirm it is still the same artifact. That
preserves view position and avoids pointless work.

If the new generation fails to open or its first requested page cannot render,
the prior preview should remain visible with an explicit stale/error state. The
UI must not silently imply that the previous PDF represents the latest build.

## 5. Cache Study

### 5.1 Document cache

A document cache retains parsed PDF state and engine resources.

Benefits:

- avoids reopening the file for every page;
- shares page tree, fonts, images, and other decoded resources;
- enables fast navigation and future text/link queries.

Costs and risks:

- engine-owned memory may be large and difficult to measure;
- some engines lock the source file, especially on Windows;
- an open document may observe unexpected replacement semantics if it reads
  lazily from a path that later changes;
- a document cannot be closed while a render still uses it;
- multiple views or parallel document instances multiply resource use.

The cache key must be a document generation derived from an accepted artifact,
not only the PDF path. The first design likely needs the current generation and
possibly the last-good generation during handover, rather than an unbounded
multi-document cache.

If the selected engine reads lazily from the original file, a later compilation
can replace bytes behind an open handle. A spike must verify platform behavior.
Possible mitigations include engine-supported immutable byte sources, a
generation-owned read handle with known replacement semantics, or a private
snapshot. Copying every PDF improves isolation but adds time, disk use, cleanup,
and another data-retention policy, so it should not be assumed without evidence.

### 5.2 Page raster cache

A page cache stores expensive rendered output. A useful key includes:

- document generation;
- page index;
- effective raster scale or pixel dimensions;
- crop box and rotation;
- pixel format, background/alpha, and color/rendering flags;
- renderer-adapter version where upgrades can alter output.

Path, page index, and user zoom alone are insufficient. They can return pixels
from an obsolete build or at the wrong display density.

The cache should be weighted by actual retained bytes, including an estimate for
UI/GPU copies where measurable. Entry-count limits cannot distinguish a small
thumbnail from a 4K page.

### 5.3 Zoom cache alternatives

**Exact zoom entry per request**

- produces sharp output at every settled zoom;
- causes cache explosion during wheel or gesture zoom;
- repeats nearly equivalent renders.

**Discrete scale buckets**

- improves reuse and bounds variants;
- can temporarily rescale a close cached raster;
- may render more pixels than needed or remain slightly soft between buckets.

**Single best raster per page**

- minimizes entry count;
- zooming back evicts useful earlier output;
- a high-resolution raster can consume excessive memory.

**Two-tier preview/refinement**

- immediately rescales the nearest cached raster;
- after zoom input settles, renders the exact or nearest bounded target scale;
- gives good perceived responsiveness;
- needs debouncing, generation checks, and a clear quality state.

A bucketed or two-tier strategy appears most promising. The number and spacing
of buckets should come from interaction and memory measurements.

### 5.4 Invalidation

Invalidation should occur at different scopes:

- **new document generation:** old generation entries stop being eligible for
  new views, but pinned old images survive until handover completes;
- **display-density change:** render-scale variants become suboptimal, not
  semantically invalid; retain them as temporary fallbacks;
- **zoom/layout change:** cancel obsolete requests and reprioritize, without
  necessarily evicting useful cached results;
- **renderer setting or adapter upgrade:** change the cache key or clear affected
  entries;
- **memory pressure:** evict unpinned completed rasters;
- **project close:** cancel work, release views, close document generations, and
  clear their cache entries.

Invalidation must never dispose an image while Compose or a render result still
uses its backing memory. Ownership may require immutable copies, reference
counts, or UI-managed conversion lifetimes depending on the adapter.

### 5.5 Replacement policies

| Policy | Advantage | Disadvantage | Preview suitability |
| --- | --- | --- | --- |
| LRU | Simple; preserves recent navigation | Large entries can dominate unless weighted | Reasonable baseline when byte-weighted |
| LFU | Preserves repeatedly visited pages | Adapts slowly after navigation pattern changes | Weak as the only policy |
| FIFO | Very simple and predictable | Evicts visible/recently reused pages poorly | More suitable for internal I/O buffers than page rasters |
| Priority-aware weighted LRU | Protects visible pages, favors neighbors, accounts for bytes | More policy and bookkeeping | Best conceptual fit |
| Soft/weak references | Lets the GC reclaim entries | Unpredictable latency and poor native-memory accounting | Not a primary policy |

Visible pages should be pinned. Neighbor prerenders can have lower retention
priority. Thumbnails, full-size pages, and multiple views should share a global
budget or coordinated budgets so each cache cannot independently exhaust
memory.

### 5.6 Memory controls

The architecture should allow:

- a global preview memory budget with a conservative default;
- a maximum raster width, height, and pixel area;
- a maximum number of open document generations;
- visible-page pinning with a bounded exception policy;
- admission control that can reject a render larger than the budget;
- optional image subsampling for large embedded images;
- explicit release on project close and generation retirement;
- metrics for JVM heap, engine-native resources, UI images, hit rate, and
  eviction.

The system should degrade quality or concurrency before allowing unbounded
allocation. An out-of-memory failure is not an acceptable cache policy.

## 6. Rendering Strategies

### 6.1 Full-document rendering

All pages are rasterized when the document opens or zoom changes.

Advantages:

- navigation is instant after completion;
- simple steady-state UI;
- easy to export a complete raster set.

Disadvantages:

- poor first-page latency;
- unbounded CPU and memory for large documents;
- most work is wasted when the user reads only a few pages;
- zoom and rebuild invalidate a large amount of work;
- cancellation leaves substantial obsolete work.

This is unsuitable as the general strategy. It may be acceptable for very small
documents under a measured threshold, but that optimization adds branching and
is not necessary initially.

### 6.2 Incremental document loading

Metadata and page objects are parsed only as accessed, when the engine supports
it.

Advantages:

- reduces initial time and memory;
- complements lazy page rasterization;
- makes large documents viable.

Disadvantages:

- failures can surface late on a particular page;
- lazy reads complicate source replacement and document lifetime;
- engine behavior varies.

Incremental parsing is an engine capability, not a complete UI render policy.

### 6.3 Lazy rendering

Compose lays out lightweight page placeholders and requests pages as they enter
or approach the viewport.

Advantages:

- fast first useful content;
- work follows actual navigation;
- naturally bounds active pages;
- integrates with a lazy list or custom virtualized layout.

Disadvantages:

- fast scroll can expose placeholders;
- visibility changes can create request churn;
- page dimensions must be known before pixels to avoid layout jumps;
- Compose item disposal must not be confused with cache eviction.

This is the strongest default for continuous document navigation.

### 6.4 Explicit on-demand rendering

Only direct requests such as “go to page” render a page.

Advantages:

- minimum speculative work;
- predictable resource consumption.

Disadvantages:

- adjacent scroll always waits;
- continuous mode feels less fluid;
- the UI must explicitly manage every request.

It is useful as the scheduler primitive beneath lazy rendering, not as the whole
user experience.

### 6.5 Neighbor prerender

After visible pages, the scheduler requests pages immediately before and after
the viewport.

Advantages:

- makes normal reading scroll feel continuous;
- small, understandable speculation window;
- adapts to scroll direction.

Disadvantages:

- consumes CPU and memory that may be wasted;
- competes with visible refinement and new builds;
- a symmetric window is inefficient during rapid directional scroll.

Neighbors should be low priority, cancellable, bounded, and direction-aware.
Visible pages and explicit navigation always take precedence.

### 6.6 Tiled rendering

Large or highly zoomed pages are divided into rectangular tiles.

Advantages:

- bounds individual raster allocations;
- refines only visible regions at extreme zoom;
- suits PDFium's clipping/matrix model and similar APIs.

Disadvantages:

- more complex cache keys, seams, transforms, scheduling, and Compose drawing;
- PDFBox integration may not obtain equivalent savings;
- ordinary document zoom may not justify it.

The neutral model should avoid making tiled rendering impossible, but the first
implementation need not expose tiles unless benchmarks show that full-page
rasters cannot safely support required zoom.

### 6.7 Provisional combined policy

The most balanced strategy appears to be:

- load metadata first;
- virtualize page layout;
- render visible pages on demand;
- render immediate neighbors at lower priority;
- show a nearby cached scale during active zoom;
- refine to a density-aware scale after input settles;
- cancel or suppress obsolete work by document generation and view request;
- never rasterize the complete document by default.

This is a policy recommendation for evaluation, not a fixed algorithm or API.

## 7. Compose Desktop Integration

### 7.1 Engine-neutral presentation

Compose should receive immutable presentation models:

- page identity and geometry;
- loading, ready, failed, and refinement state;
- an engine-neutral raster or UI-owned image;
- mapping between PDF page coordinates, raster pixels, and Compose logical
  coordinates;
- optional link/text/annotation overlays as separate models.

Compose should send intents:

- viewport and visible page range;
- zoom mode/value;
- scroll or navigation target;
- retry/cancel;
- pointer positions that may later be translated to PDF coordinates.

The composables should not open files, invoke renderers, own engine documents,
or decide which build result is authoritative.

### 7.2 Raster boundary alternatives

**Renderer returns `BufferedImage`**

- simplest with PDFBox;
- exposes AWT and makes PDFium/PDF.js adapters unnatural;
- risks coupling cache and scheduler to one engine.

**Renderer returns raw immutable pixels and metadata**

- engine-neutral and explicit about format, stride, and ownership;
- maps naturally from native bitmaps;
- may require conversion or copy into a Skia/Compose image;
- lifecycle must prevent buffer reuse while the UI draws.

**Renderer returns encoded PNG**

- portable and easy to pass across process/runtime boundaries;
- adds encode/decode CPU and temporary memory;
- loses the advantage of already rasterized raw pixels.

**Renderer returns Compose `ImageBitmap`**

- easiest for the UI and may avoid a presentation copy;
- couples the renderer to Compose/Skia and its thread/lifetime rules;
- makes headless engine tests and alternate front ends harder.

Raw immutable raster data is the cleanest long-term boundary. A pragmatic
PDFBox spike may measure both direct `BufferedImage` adaptation and an explicit
pixel copy before committing. The chosen contract should document whether UI
conversion copies or shares storage.

### 7.3 Page representation

A continuous preview can use a virtualized vertical layout whose item geometry
is known from metadata before image completion. Each item can independently show
a placeholder, temporary scaled raster, final raster, or page-specific error.

Fit-width must be computed from the actual available page viewport, excluding
scrollbars and padding, then combined with display density for raster scale.
Fit-page additionally depends on viewport height. UI resize should debounce
expensive refinement while continuing to draw an older usable raster.

Zoom and scroll state belong to a view, not the document. Two views of the same
generation may share rasters but use different anchors and zoom modes.

### 7.4 Preserving position across rebuilds

Possible policies include:

- preserve page index and fractional vertical offset;
- preserve a page-space anchor coordinate at viewport center;
- preserve absolute document scroll fraction;
- navigate through SyncTeX to the active source location.

Page index plus page-space anchor is more stable than raw pixels or total scroll
fraction when page dimensions change. If the new document has fewer pages,
clamp to the nearest valid page and communicate the movement only when it would
otherwise be surprising. Future SyncTeX can improve semantic preservation, but
preview reload must work without it.

### 7.5 State and error presentation

Useful UI states include:

- no open project;
- project has no ready configuration or successful build;
- build queued/running while an older preview remains visible;
- loading a new PDF generation;
- ready and current;
- ready but stale because the latest build failed/cancelled;
- new build succeeded but preview loading/rendering failed;
- output quarantined, with the last readable preview clearly marked;
- page-specific rendering failure while other pages remain usable;
- renderer capability unavailable on this platform.

Compilation error and preview error are distinct. A successful build followed
by a renderer failure must not be displayed as a compilation failure.

### 7.6 Accessibility and input

Raster pages alone have little semantic accessibility. Even if the first
milestone is image-based, the design should permit:

- page semantics and navigation labels;
- keyboard zoom and page movement;
- focus that does not trap editor shortcuts;
- future text-layer semantics and selection;
- links as semantic overlays rather than pixels;
- density-independent hit testing through page coordinate transforms.

PDF.js or native viewers offer more of this behavior initially. A Compose-native
raster viewer accepts responsibility for building it incrementally.

## 8. Concurrency

### 8.1 Background execution

Document open, metadata extraction, page render, raster conversion, and any
content fingerprinting must run outside the Compose UI thread. Publishing a
small immutable state change back to Compose should be the only UI-thread work.

An unbounded executor is unsafe even if tasks use virtual threads: PDF
rendering is CPU- and memory-intensive, and native/Java2D calls can pin carrier
threads. Concurrency needs explicit limits based on renderer safety and memory
admission.

### 8.2 Scheduling and priority

A scheduler should distinguish:

1. first visible page after open or navigation;
2. other visible pages;
3. visible-page scale refinement;
4. page needed by direct SyncTeX/navigation intent;
5. neighbors in scroll direction;
6. other speculative or thumbnail work.

Exact priority order may change after testing. The important property is that
obsolete or speculative work cannot starve the visible current generation.

### 8.3 Cancellation

Cancellation has several meanings:

- a request has not started and can be removed;
- an engine operation supports cooperative/progressive cancellation;
- an engine operation cannot be interrupted safely, so its result is ignored
  and no further work follows;
- a complete document generation is retired;
- application/project shutdown waits for or safely abandons renderer work.

The architecture must not claim that cancellation stopped native or Java code
when it only suppressed publication. For non-interruptible page calls, bounded
concurrency keeps unavoidable obsolete work small.

Every task should capture its document generation and request token. Completion
publishes only if both are still current for the intended consumer. This
prevents an older, slower page from replacing a newer build or zoom result.

### 8.4 Repeated and overlapping requests

Identical in-flight render keys should be coalesced into one shared job.
Consumers can independently stop waiting without necessarily cancelling work
still needed by another view.

Near-equivalent zoom requests may temporarily reuse a completed raster but
should not be coalesced as the same final quality unless their normalized cache
key is equal.

Rapid build sequence A, B, C should allow only the latest acceptable pending
generation to promote. A render from A or B may finish, but generation checks
discard it. The last good displayed generation remains until C becomes
displayable or reports failure.

### 8.5 Renderer thread safety

Concurrency policy belongs partly to each adapter:

- PDFBox requires serialized access to one `PDDocument`;
- an adapter may load separate document instances for limited parallelism, but
  must measure the extra memory and source I/O;
- PDFium global and per-handle threading rules must be verified for the exact
  public APIs used;
- PDF.js schedules within its worker/browser runtime;
- native views may require all calls on a platform UI thread.

The renderer port should expose or configure its safe concurrency rather than
letting the generic scheduler guess.

### 8.6 Large documents and hostile complexity

Page count is not a sufficient complexity estimate. One page can contain huge
images, pathological vectors, deeply nested resources, or malformed content.
Controls should include:

- bounded concurrent documents and renders;
- maximum target pixel area;
- byte-budget admission before raster allocation;
- per-operation telemetry and a future timeout policy;
- page-local errors where safe;
- whole-generation failure when document integrity is uncertain;
- no automatic repeated retry loop for deterministic failures;
- renderer version and failure evidence in diagnostics.

Running an in-process renderer cannot provide hard memory or crash isolation.
If corpus testing reveals unacceptable native crashes, hangs, or memory
exhaustion, an out-of-process rendering worker becomes a future alternative. It
adds IPC, process lifecycle, bitmap transfer, and packaging complexity and
should be justified by measured reliability risk.

### 8.7 Lifecycle and shutdown

Closing a project should:

- unsubscribe from its compilation manager;
- reject new preview intents;
- cancel queued render work;
- prevent late results from publishing;
- release view references and cached images;
- close engine document generations after in-flight access ends;
- terminate adapter-owned executors or helper processes under a bounded policy.

The compilation manager may retire asynchronously during project replacement.
Preview must bind subscriptions and generations to the same project identity so
a late result from the old project cannot appear in the new one.

## 9. Future Integrations

### 9.1 SyncTeX

SyncTeX should consume the `.synctex` artifact associated with the same build
session as the PDF. The preview architecture should preserve:

- session and document-generation identity;
- page boxes, rotation, crop, and coordinate transforms;
- navigation to a page-space rectangle or point;
- pointer-to-page coordinate mapping;
- view-local highlighting overlays.

Forward synchronization maps source position to PDF coordinates; inverse
synchronization maps a preview click to source. Neither should depend on raster
pixel resolution, because zoom and HiDPI can change independently. The mapping
boundary should use PDF page space.

### 9.2 Search and text selection

Search may require text extraction, glyph positions, reading order, and a text
overlay. Engines vary substantially here.

Options are:

- require the renderer adapter to expose optional text-page capability;
- introduce a separate text extraction service that may use the same or another
  PDF library;
- delegate search entirely to an embedded viewer.

Keeping text as an optional capability avoids bloating the initial raster port.
However, page geometry and generation identity must be common so results cannot
be painted over a different build.

### 9.3 Annotations

Compiler output is replaceable and owned by the build workflow. Writing
annotations into that PDF risks loss on the next compilation and conflicts with
output ownership.

A future annotation system should therefore first consider AeTeX-owned overlay
data keyed to project/document semantics, with explicit export if PDF mutation
is ever supported. The preview layer composes overlays above rendered pages.
This keeps renderer adapters read-only and avoids invalidating the compilation
artifact contract.

### 9.4 Multiple views

Multiple views need:

- shared document generation and page cache where render parameters match;
- independent zoom, scroll, selection, and navigation history;
- consumer-aware pinning so one view cannot evict another's visible page;
- generation promotion coordinated across views or explicitly view-specific;
- reference-counted document lifetime.

Document state must therefore not contain a single global “current page” or
zoom.

### 9.5 Side-by-side comparison

Comparison may display:

- two pages from one generation;
- the same page at different zooms;
- the latest and previous successful generations;
- two project targets if configuration later supports them.

This multiplies visible raster demand. A global byte budget and priority-aware
cache are more important than per-panel caches. Retaining previous PDF
generations also raises source snapshot and cleanup questions that the initial
last-good handover alone does not fully solve.

### 9.6 Plugins

Plugins should not receive native document handles, renderer objects, mutable
pixel buffers, or unrestricted artifact paths. Potential future extension
points are:

- observe a stable preview/session identity;
- request navigation through bounded intents;
- contribute read-only overlays in page coordinates;
- query declared capabilities;
- request a page raster through quotas and cancellation;
- add actions without replacing core input handling.

Engine adapters themselves could eventually be pluggable, but only after the
renderer contract, security model, binary trust, compatibility negotiation, and
resource quotas are stable. A premature renderer plugin API would freeze
assumptions before AeTeX has experience with even one engine.

## 10. Portability

### 10.1 Common semantics

All platforms should agree on:

- successful build-result selection;
- document-generation identity;
- page numbering and page-space coordinates;
- zoom modes and navigation intents;
- cache key semantics and byte budgets;
- stale/current/error state;
- cancellation and late-result suppression;
- capability reporting.

Pixel-identical output is not a realistic invariant when fonts, color systems,
native engines, and display pipelines differ. Visual regression tests should
define tolerated differences and emphasize missing content, geometry, and severe
rendering errors over exact per-pixel equality.

### 10.2 Windows

Considerations include:

- compilation may replace a PDF while a renderer still has it open; the chosen
  engine/source mode must be tested for file sharing and lock behavior;
- antivirus and indexing can add transient file access delays;
- paths can be drive- or UNC-based and comparisons are not safely modeled as
  case-sensitive strings;
- display scale commonly changes when moving between monitors;
- native libraries need x64/ARM64 decisions, DLL dependency management, safe
  extraction/loading, and signing;
- `Windows.Data.Pdf` is available as a native option but requires WinRT/COM
  integration from the JVM.

The preview must not weaken Architecture 003's exact artifact/path identity.

### 10.3 Linux

Considerations include:

- font availability and fontconfig configuration vary across distributions;
- X11 and Wayland desktop scale behavior and fractional scaling need testing;
- package formats and native library baselines differ;
- glibc compatibility can constrain prebuilt PDFium/Poppler binaries;
- GPU/Skia backends and headless CI behavior differ;
- case-sensitive filesystem behavior usually exposes assumptions hidden on
  other platforms.

A pure JVM engine minimizes binary compatibility work but does not eliminate
font and graphics differences.

### 10.4 macOS

Considerations include:

- Intel and Apple Silicon builds may both be required;
- bundled native libraries must satisfy code-signing and notarization rules;
- Retina scale and moving between displays require raster-scale invalidation;
- sandboxing, if introduced later, affects file access and native loading;
- PDFKit offers a mature native viewer but creates a macOS-specific interaction
  path;
- Compose/Skia and AppKit interop must be tested for focus, clipping, and
  accessibility if a native view is embedded.

### 10.5 HiDPI and rendering differences

The application should test at 100%, 125%, 150%, 200%, and representative
fractional scales where the OS supports them. Important cases are:

- window movement between displays;
- zoom while a density change is pending;
- fit-width recalculation during resize;
- thin lines and glyph hinting;
- transparency and color profiles;
- embedded versus substituted fonts;
- page rotation and crop boxes;
- non-integer logical-to-physical pixel mapping.

The cache key should use the effective raster parameters, not the display name
or a rounded OS scale alone.

## 11. Risks

| Risk | Category | Likelihood before evidence | Impact | Possible mitigation / evidence |
| --- | --- | --- | --- | --- |
| Chosen engine renders common LaTeX PDFs too slowly | Performance | Medium | High | Representative benchmark corpus; first-page and visible-page metrics; PDFium comparison |
| High zoom or image-heavy pages exhaust memory | Performance / stability | High without controls | High | Pixel-area limits, byte-weighted cache, admission control, bounded concurrency, subsampling |
| Renderer output differs or omits content | Correctness | Medium | High | Visual corpus against reference renderers; page-specific diagnostics; engine escape path |
| Optional image codecs are absent or license-incompatible | Dependency / legal | Medium | Medium to high | Corpus inventory, explicit capability/error, license audit before distribution |
| Native bridge crashes the JVM | Stability | Medium for native choice | High | Minimize bridge, fuzz/corpus tests, crash evidence, possibly isolate in helper process |
| PDFium binaries become difficult to reproduce or update | Maintenance / supply chain | High for PDFium | High | Reproducible CI builds, pinned revisions, SBOM, signatures, update policy |
| Embedded browser dominates package size or creates UI inconsistencies | Product / maintenance | High for PDF.js | Medium to high | Distribution measurement and focused interop prototype before selection |
| Platform-native engines diverge in behavior | Portability | High for native-per-OS choice | High | Common contract/tests, explicit capabilities, avoid platform split initially |
| Old render completes after a new build and replaces current pixels | Concurrency / correctness | High without generation model | High | Opaque generation IDs, request tokens, atomic promotion, stale-result suppression |
| File replacement interacts badly with lazy document reads or locks | Filesystem / portability | Medium | High | Cross-platform replacement tests; source snapshot only if justified |
| Cache disposes pixels still used by Compose | Stability | Medium | High | Immutable ownership contract, reference/pinning model, adapter lifecycle tests |
| Scroll/zoom generates unbounded duplicate work | Performance | High without scheduler | Medium to high | In-flight coalescing, debounce, priority queue, bounded workers |
| Failed compilation silently displays an old PDF as current | Product correctness | Medium without explicit state | High | Direct result authority and visible stale state |
| Malformed PDF consumes excessive CPU/memory | Security / stability | Medium | High | Limits, timeout research, current dependencies, optional process isolation |
| Font substitution changes output across operating systems | Portability / fidelity | Medium | Medium | Embedded-font corpus, diagnostics, documented tolerance, engine comparison |
| Future SyncTeX cannot map through raster/UI coordinates | Evolution | Medium if ignored | High | Preserve page-space geometry and transforms from the first contract |
| Abstraction becomes a lowest-common-denominator trap | Architecture | Medium | Medium | Small raster core plus explicit optional capabilities; validate with two adapters/spikes |
| Renderer license conflicts with AeTeX distribution goals | Legal / dependency | Low to high by engine | High | Legal review before dependency adoption; retain replaceable renderer boundary |
| Renderer security updates lag application releases | Maintenance / security | Medium | High | Dependency monitoring, pinned provenance, documented update SLA |

### 11.1 Risk interactions

Several risks compound:

- higher DPI improves fidelity but increases render time, heap/native memory, UI
  upload cost, and cache churn;
- more parallel rendering hides latency but multiplies peak memory and may
  violate engine thread-safety;
- copying a PDF generation prevents replacement/locking races but increases
  reload latency and storage lifecycle complexity;
- a rich embedded viewer reduces feature implementation but increases runtime,
  integration, packaging, and security maintenance;
- a thin renderer abstraction is easy now but may block text/SyncTeX later,
  while a broad abstraction designed without engine evidence may be wrong.

The architecture should prefer measurable, reversible increments.

## 12. Validation Needed Before a Decision

### 12.1 Representative corpus

The benchmark corpus should include:

- short and long LaTeX documents;
- text-heavy papers and books;
- TikZ/vector-heavy pages;
- high-resolution raster images;
- transparency, gradients, clipping, and color profiles;
- embedded and non-embedded fonts;
- Type 1, TrueType, and OpenType/CID cases encountered in TeX output;
- links, outlines, annotations, rotation, and mixed page sizes;
- PDFs from pdfLaTeX, XeLaTeX, and LuaLaTeX;
- very large page counts;
- malformed, truncated, encrypted, and unsupported-feature samples;
- JBIG2 and JPEG 2000 content if it appears in realistic projects.

Redistribution rights for corpus files must be tracked. Private regression
samples can supplement a distributable minimal corpus.

### 12.2 Spikes

At least these focused experiments would reduce uncertainty:

1. PDFBox document open, metadata, visible-page lazy render, zoom refinement,
   cancellation suppression, and Compose conversion;
2. the same page corpus rendered by PDFium through a minimal throwaway bridge,
   measuring total native integration cost as well as performance;
3. cross-platform file replacement while an old document generation remains
   open;
4. Compose page virtualization with placeholders, fast scrolling, display
   density changes, and a byte-weighted cache;
5. visual comparison and feature-failure reporting for optional PDFBox codecs;
6. memory measurement separating source/document resources, raw raster,
   Compose/Skia image, and process RSS;
7. if PDF.js remains competitive, an embedded-runtime prototype measuring
   startup, distribution size, focus/shortcut behavior, and bridge reliability.

These are disposable investigations, not implicit production implementations.

### 12.3 Decision gates

A future Architecture 004 should not select an engine until it can answer:

- Does PDFBox meet agreed latency, fidelity, and memory thresholds on all three
  platforms?
- What concrete pages fail or regress, and are failures diagnosable?
- Does PDFium's measured improvement justify native build and binding ownership?
- Is a Compose-native raster viewer sufficient for accessibility and near-term
  search/navigation goals?
- Are optional codec licenses compatible with intended AeTeX distribution?
- Can an open document coexist safely with compilation replacement on each OS?
- Is in-process rendering sufficiently robust for malformed or extreme PDFs?
- What page-cache budget and scale policy behave acceptably on target hardware?

## 13. Provisional Recommendation

The most suitable direction to evaluate first is a **layered, engine-neutral
preview subsystem**:

- direct terminal notification from `CompilationManager` is the authoritative
  update path;
- a preview coordinator accepts only the exact successful `PRIMARY_PDF`
  artifact and creates opaque document generations;
- a document repository owns renderer documents and generation handover;
- a bounded scheduler renders visible pages in the background, coalesces
  duplicate requests, suppresses stale completions, and prerenders only nearby
  pages at lower priority;
- a byte-weighted, priority-aware page cache keys entries by generation and full
  raster parameters;
- Compose owns view-local zoom, navigation, scroll, gestures, and display state,
  consuming engine-neutral geometry and raster results;
- page-space transforms remain first-class so SyncTeX, links, search, and
  overlays can evolve without depending on bitmap pixels.

Within that architecture, **Apache PDFBox is the best first engine candidate for
an AeTeX evaluation**, not yet the selected production engine. Its pure-JVM
integration, Apache license, page raster API, incremental PDFBox 3.x loading,
and lack of native packaging align well with the current JVM/Compose Desktop
repository and minimize the number of new operational problems introduced at
once.

PDFBox should be accepted only if a representative Windows/Linux/macOS corpus
shows adequate fidelity, first-visible-page latency, zoom refinement, and
bounded memory. The adapter must serialize access to a document unless evidence
supports another safe strategy, and optional image codecs need explicit
capability and license treatment.

**PDFium should be retained as the primary fallback and benchmark comparator.**
If PDFBox misses agreed thresholds in material documents, PDFium is the most
credible next candidate, but its performance benefit must be weighed against
AeTeX owning native builds, a JVM bridge, binary distribution, crash behavior,
and security updates.

**PDF.js should remain a separate embedded-viewer alternative**, especially if
search, text selection, accessibility, and mature viewer interaction become
near-term priorities. It should not be hidden behind the same assumptions as a
page-raster adapter because its browser runtime and UI boundary are
architecturally significant.

**Platform-native engines should not be the initial common approach** because
they create three behavioral implementations and a larger test matrix. They may
later serve as optional accelerators if the neutral contracts and capability
model prove stable.

For change detection, direct build-result notification should be primary.
Filesystem watching may later detect external modifications to the exact active
artifact, but only as a validated hint with explicit stale/external semantics.
Continuous polling is not justified for the normal compilation path.

This recommendation deliberately leaves open:

- the final renderer;
- exact cache sizes and zoom buckets;
- whether raw pixels or an adapter-specific intermediate crosses the initial
  renderer boundary;
- whether source bytes need a private generation snapshot;
- whether tiled or out-of-process rendering is necessary;
- whether external PDF changes reload automatically;
- the exact component and API names.

Those decisions should follow the corpus, spikes, portability tests, and license
review described above. Until then, this document remains a study and does not
authorize implementation.

## Sources Consulted

Primary project and platform documentation consulted for this study:

- [Apache PDFBox project and license](https://pdfbox.apache.org/)
- [Apache PDFBox 3.x getting started and current release](https://pdfbox.apache.org/3.0/getting-started.html)
- [Apache PDFBox 3.x migration and incremental I/O](https://pdfbox.apache.org/3.0/migration.html)
- [Apache PDFBox thread-safety and rendering FAQ](https://pdfbox.apache.org/3.0/faq.html)
- [Apache PDFBox optional dependencies and codecs](https://pdfbox.apache.org/3.0/dependencies.html)
- [Apache PDFBox security model](https://pdfbox.apache.org/security.html)
- [PDFium repository and embedding guidance](https://pdfium.googlesource.com/pdfium/)
- [PDFium public embedding API policy](https://pdfium.googlesource.com/pdfium/+/main/public/README)
- [PDFium getting-started guide](https://pdfium.googlesource.com/pdfium/+/HEAD/docs/getting-started.md)
- [PDFium license](https://pdfium.googlesource.com/pdfium/+/main/LICENSE)
- [PDF.js architecture and distribution](https://mozilla.github.io/pdf.js/getting_started/)
- [PDF.js rendering and HiDPI examples](https://mozilla.github.io/pdf.js/examples/)
- [PDF.js license](https://github.com/mozilla/pdf.js/blob/master/LICENSE)
- [Apple PDFKit `PDFView`](https://developer.apple.com/documentation/pdfkit/pdfview)
- [Microsoft `Windows.Data.Pdf`](https://learn.microsoft.com/en-us/uwp/api/windows.data.pdf?view=winrt-26100)
- [Poppler project](https://poppler.freedesktop.org/)
- [MuPDF releases and licensing summary](https://mupdf.com/releases)
- [Compose Multiplatform project and desktop scope](https://github.com/JetBrains/compose-multiplatform)

Versions and upstream status are time-sensitive. A future decision must repeat
the dependency, security, license, and platform review against the exact
versions proposed for distribution.
