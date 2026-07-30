# AeTeX experimental PDF rendering benchmark

This standalone tool compares Apache PDFBox and PDFium for the evidence needed
by Architecture Study 004. It is deliberately outside `src/`, has its own
Gradle build, does not depend on AeTeX product code, and is not a preview
implementation.

It does **not** implement UI, Compose integration, preview coordination,
caching policy or a `PreviewManager`. It also does not select a winning engine.

## Requirements

- A JDK capable of supplying the configured Java 21 toolchain.
- Network access on the first run for Maven dependencies and the pinned PDFium
  binary.
- Linux or macOS on x64/arm64, or Windows on x64/arm64.

PDFium is pinned to `152.0.7961.0` (`chromium/7961`) from
`bblanchon/pdfium-binaries`. `build.gradle.kts` records a SHA-256 for every
supported archive and refuses to extract a mismatching download. The
precompiled distribution is an experimental convenience, not a product
packaging decision.

`bblanchon/pdfium-binaries` is a third-party binary distribution, not an
official Google JVM artifact. Every run verifies the cached archive, performs a
fresh extraction, hashes the native library actually loaded, and copies a
provenance record into the result directory.

## Run

From the repository root:

```bash
./gradlew -p tools/rendering-benchmark benchmark
```

For a quicker diagnostic run:

```bash
./gradlew -p tools/rendering-benchmark benchmark --args="--repetitions 3 --warmups 1"
```

Three measured repetitions and one discarded full-corpus warm-up round are the
defaults and the minimum recommended values. Use more repetitions for
decision-quality runs. The report records the exact policy.

To generate only the corpus:

```bash
./gradlew -p tools/rendering-benchmark generateCorpus
```

Tests:

```bash
./gradlew -p tools/rendering-benchmark test
```

## Isolation and timing policy

The coordinator generates the corpus before measurement, then launches one
fresh worker JVM for PDFBox and one for PDFium. Workers run sequentially.
JVM startup, renderer-global initialization and library download/extraction are
not timed. Each worker performs discarded open/render/close warm-up cycles over
every document and the same rendering sequence used by measured runs. Every
warm-up document uses a fresh adapter and is really reopened.

Every measured document repetition constructs a fresh adapter, opens the PDF,
runs all operations in a fixed order, and closes the document. PDFium's global
runtime lives for exactly one worker, outside document metrics, matching the
fact that PDFBox also has process-global library/font state. Global caches can
therefore remain in a post-warm-up steady state, but document/page/image objects
do not cross repetitions.

Measured document order is shuffled independently on each repetition with
fixed seed `20260730`; PDFBox and PDFium receive the same schedule, recorded in
`execution-order.tsv`. OS filesystem caches are not force-cleared: doing so is
not portable and generally requires privileges. Corpus generation and
full-corpus warm-up make measured file reads warm for both workers.

The harness requests two garbage collections with 30 ms settling intervals
before and after each measured repetition. This is outside timed regions and
reduces Java heap carry-over, but `System.gc()` remains only a request.

PNG conversion and encoding occur in a separate post-measurement pass.

## Common renderer interface

Both adapters implement only:

```text
open(document) -> page count
render(page, scale) -> engine-neutral opaque RGB raster in ARGB storage
close()
```

No PDFBox, PDFium, AWT or Compose type escapes the adapter boundary.

## Corpus

`benchmark-documents/generated/` contains deterministic synthetic content:

- small;
- medium;
- large mixed content;
- many images;
- much text;
- many pages;
- vector graphics;
- tables.

The generator also creates an empty file and a deliberately truncated PDF for
robustness checks. No personal or third-party PDF is used. `manifest.tsv`
records category, page count, byte size and SHA-256.

To add a document category, add a `CorpusDocument` and its deterministic writer
to `CorpusGenerator`. Avoid copyrighted, confidential, personal, encrypted or
network-fetched material. Keep category names descriptive and regenerate the
manifest.

## Metrics

For each document and renderer:

- open time, including page-count retrieval;
- first page at 100%;
- up to four successive pages at 100%;
- page 1 at 100%, 150%, 200% and 300%;
- all pages at 100%;
- document close time;
- total fixed-sequence time from immediately before open through close;
- JVM used heap before, sampled peak and after;
- process RSS before, sampled peak and after where available.

All elapsed measurements use `System.nanoTime()`. The first-page metric includes
lazy work triggered by the first raster request. Zoom measurements are warm
rerenders of page 1. The full-document metric runs after the page/zoom
operations in the same session, so it is a warm-session traversal rather than a
cold standalone render. The report calculates count, mean, minimum, maximum and
population standard deviation. Memory peaks target a 5 ms sampling interval
and are approximate.

Render timings include allocation and population of the complete
engine-neutral `IntArray`: PDFBox uses `BufferedImage.getRGB`, while PDFium
crosses JNA and normalizes BGRA bytes in Kotlin. These are adapter-level results,
not isolated PDFBox/PDFium core-raster timings. The bridge cost remains visible
because AeTeX ultimately needs JVM-consumable pixels.

The output contract is white, opaque RGB (alpha normalized to 255), using
72-DPI scale factors and the same truncation rule for final pixels. Corpus
validation rejects pages whose rotation is nonzero or whose CropBox differs
from MediaBox. PDFium uses annotation rendering without LCD subpixel text.

Robustness scenarios record elapsed time, outcome, exception and diagnostic
for:

- nonexistent file;
- empty file;
- corrupt PDF;
- out-of-range page;
- repeated open;
- repeated close.

## Results and interpretation

Every invocation creates a new UTC timestamped directory under `results/` and
refuses to overwrite an existing file. The layout is:

```text
results/run-<timestamp>/
    benchmark-report.md
    pdfium-provenance.properties
    pdfbox/
        measurements.tsv
        robustness.tsv
        execution-order.tsv
        images/
    pdfium/
        measurements.tsv
        robustness.tsv
        execution-order.tsv
        images/
```

Read timings together with peak RSS, errors and side-by-side PNGs. A lower
single timing does not establish product suitability. Repeat on every supported
OS and representative low-/high-end hardware, and inspect fonts, vectors,
images, transparency and tables manually at every scale.

## Limitations

- The corpus is synthetic and cannot represent the full PDF ecosystem.
- The benchmark does not measure UI frame stability, scrolling, cancellation,
  text extraction, links, native packaging or cold machine disk cache.
- Garbage collection, CPU scaling and background load still add noise.
- RSS contains the whole worker JVM and binding, while used heap excludes
  PDFium native allocations.
- Current RSS is sampled from Linux `/proc/self/status` `VmRSS`. On macOS and
  Windows RSS is reported as unavailable rather than substituting JVM heap or
  committed virtual memory; use a validated native/external resident-memory
  measurement for decision-quality runs there.
- The 5 ms memory sampler adds a small harness load and can miss very brief
  peaks.
- Sequential renderer execution cannot eliminate thermal or unrelated
  background-load drift; repeat runs under controlled load.
- Installed fonts and PDFBox's per-user font cache influence substitution and
  visual output. Corpus generation can build that cache, but it is outside
  timed worker regions.
- The controlled corpus has CropBox equal to MediaBox and no intrinsic page
  rotation. Engines may still differ for other box/rotation combinations.
- RGB output is normalized structurally, but engine/platform color-management
  implementations may still differ.
- Visual fidelity is intentionally not assigned an automatic score.
- PDFium is accessed through JNA for this throwaway experiment; that is not a
  recommendation for a production binding.

## Offline and PDFium updates

The first run requires network access for Gradle/Maven artifacts and the pinned
PDFium archive. A later run can use:

```bash
./gradlew --offline -p tools/rendering-benchmark benchmark
```

provided the Gradle dependency cache and
`tools/rendering-benchmark/build/pdfium/downloads/` archive are preserved.
Cleaning the benchmark `build/` directory removes the native archive and makes
another network download necessary. There is no hidden system PDFium
dependency.

To update PDFium, change `pdfiumVersion`, `pdfiumTag`, every supported asset
checksum, and the adapter version constant together. Obtain digests from the
immutable release assets, run the tests and full benchmark on every supported
platform, inspect the generated provenance record, and treat results from the
new version as a new evidence series rather than merging them with old runs.
