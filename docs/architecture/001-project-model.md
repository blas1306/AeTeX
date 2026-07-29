# AeTeX Architecture 001: Project and Document Model

| Field | Value |
| --- | --- |
| Identifier | AeTeX Architecture 001 |
| Status | Implemented |
| Date | 2026-07-29 |
| Scope | Current project scanning, open-document model, filesystem access, and application-state coordination |

This document describes the architecture present in the repository at commit `62d7f68`. It documents current behavior and bounded extension points; it does not design the future configuration or compilation systems. Product direction is defined by [AeTeX Architecture 000](000-vision.md).

## Motivation

LaTeX documents commonly depend on included source files, bibliography databases, style and class files, images, and build configuration. Opening isolated buffers would discard the boundary and relationships needed for later compilation, navigation, diagnostics, and SyncTeX.

AeTeX therefore opens a directory as a project and confines document access to that root. The first implementation uses a hierarchical snapshot of the filesystem. This is sufficient for navigation and safe multi-document editing while leaving dependency analysis and persistent configuration for later work.

## Current model

The core types are defined in [`TeXProject.kt`](../../src/main/kotlin/dev/aetex/project/TeXProject.kt):

- `TeXProject` contains `rootDirectory: Path`, top-level `entries: List<ProjectEntry>`, and `mainDocument: Path?`.
- `ProjectEntry` is a sealed interface with `path`, derived `name`, and `isSymbolicLink`.
- `ProjectDirectory` adds hierarchical `children`.
- `ProjectFile` represents a non-directory entry. It is not limited to editable or LaTeX-specific file types.

`ProjectScanner` resolves the selected root to a real, absolute path. Entry paths are stored as absolute, normalized paths. Open documents are created with the validated real path returned by `DocumentService`. The model does not store a separate relative-path value; callers can derive one from the project root when needed.

`DocumentService` accepts absolute paths or relative paths. A relative path is resolved against its project root before validation. The UI and `AeTeXState` currently pass absolute paths from the scanned tree.

`mainDocument` is a nullable extension point in the data class. The scanner does not populate it, there is no root-document detection or selection flow, and its value is therefore `null` in projects created by the current application.

### Editable files

`EditableFileTypes` centralizes the current case-insensitive extension allowlist:

```text
tex, bib, sty, cls, txt
```

The scanner still displays files with other extensions. `AeTeXState.openDocument` checks the allowlist before asking `DocumentService` to read a file, so files such as PDFs and images appear in the tree but cannot be opened in the text editor.

### Scanner exclusions

The default excluded directory names are:

```text
.git, .gradle, build, out
```

The match is an exact, case-sensitive name check and applies to discovered directories, including symbolic links that resolve to directories. It is not a general ignore-file mechanism and does not exclude generated files elsewhere.

### Symbolic links

Filesystem attributes are read with `NOFOLLOW_LINKS`. A symbolic link is retained as a tree entry and marked with `isSymbolicLink`:

- a link whose target is a directory is represented as `ProjectDirectory` with no children;
- another link, including a broken link, is represented as `ProjectFile`;
- the scanner never recurses through a symbolic directory link;
- `DocumentService` rejects a path whose final component is a symbolic link as an editable document.

This means symbolic entries are visible but not directly editable through the current project tree.

## Scanning

[`ProjectScanner.kt`](../../src/main/kotlin/dev/aetex/project/ProjectScanner.kt) performs a synchronous recursive traversal:

1. It converts the requested root to an absolute normalized path, verifies that it exists without following its final link, resolves it with `toRealPath`, and requires a readable directory.
2. It opens each directory with `Files.newDirectoryStream`.
3. It reads each child's basic attributes without following the final symbolic link.
4. It skips excluded directory names, recursively scans ordinary directories, and creates file or directory entries.
5. It sorts the resulting entries with directories first, then by case-insensitive name, then by the original name as a stable tie-breaker.

A set of real directory identities prevents the same directory from being scanned twice. Encountering an already visited identity records a cycle issue and stops that branch. Symbolic directory links are not traversed in the first place, so the visited set is an additional defensive boundary.

An invalid, missing, inaccessible, unreadable, or non-directory root causes `ProjectScanException` and the project is not replaced. Most I/O and access failures while inspecting entries or opening nested directory streams are recoverable: the scanner records `ProjectScanIssue`, omits or empties the affected branch, and returns the rest of the tree. `AeTeXState` logs technical detail and exposes a summary count in the UI. An unexpected exception that is not converted to an issue—for example, a `SecurityException` while resolving a nested directory identity—propagates to `AeTeXState` and fails the project-open operation with its generic message.

The resulting tree is an eager snapshot. It is not refreshed after creation and has no connection to later filesystem changes. Scanning and tree construction currently happen on the caller's thread.

## Open-document model

[`OpenDocument.kt`](../../src/main/kotlin/dev/aetex/editor/OpenDocument.kt) defines an immutable `OpenDocument` value with:

- `path`, which is both its identity in the current state and its validated filesystem location;
- `content`, the current editor text;
- `savedContent`, the text from the last successful open or save;
- optional `error`, containing the failed operation, a user-facing message, and optional technical detail.

`isModified` is derived by comparing `content` and `savedContent`. `withContent` replaces the current content and clears an earlier error. `markedSaved` copies current content into `savedContent` and clears the error.

`AeTeXState` keeps an ordered observable list of open documents and a separate `activeDocumentPath`. Opening a second file does not discard the first; opening a path already present activates the existing entry. Closing the active document selects the tab at the same list position when possible, otherwise the previous tab.

Safe close behavior is divided between state and UI:

- `closeDocument` refuses to remove a modified document;
- `openProject` refuses to replace a project while modified documents exist unless explicit discard intent is supplied;
- the UI presents save, discard, and cancel choices for closing a modified tab, opening another project, and closing the application;
- application close can save all modified documents and aborts the close if any save fails;
- explicit discard methods exist only for flows that have already collected user intent.

## Filesystem access

[`DocumentService.kt`](../../src/main/kotlin/dev/aetex/editor/DocumentService.kt) owns document reads, writes, validation, and filesystem-specific failure mapping. Its root is resolved with `toRealPath` at construction.

Before reading or writing, it:

1. resolves relative paths against the project root and normalizes the result;
2. verifies lexical containment under the project root;
3. rejects a symbolic link in the final path component;
4. requires the path to exist and be a regular file without following the final link;
5. resolves the real path and verifies containment again;
6. checks readability for reads or writability for saves.

Files are read and written explicitly as UTF-8. A save creates a temporary sibling file, writes the complete content, copies POSIX permissions when that attribute view is supported, and replaces the target with `ATOMIC_MOVE` plus `REPLACE_EXISTING`. If atomic movement is not supported, it falls back to replacement without the atomic guarantee. A leftover temporary file is deleted on a best-effort basis after failure.

The service returns `DocumentResult.Success` or `DocumentResult.Failure` rather than exposing ordinary I/O and security exceptions to the UI. Failures contain an operation category and a stable user message; technical exception text is retained separately for logging. A failed save does not mark the document clean.

## Application state

[`AeTeXState.kt`](../../src/main/kotlin/dev/aetex/app/AeTeXState.kt) owns the observable application state shared by the Compose UI:

- the active `TeXProject`;
- the ordered open-document list;
- the active document path;
- modified-document projections;
- the latest UI message;
- project scan issues;
- the `DocumentService` bound to the active project.

It coordinates project scanning and replacement, document opening and activation, content updates, individual and batch saves, tab removal, and error reporting. Dependencies on `ProjectScanner` and the document-service factory are injectable, while defaults are supplied for the application.

`AeTeXState` does not formally claim a named architectural pattern. It is a state holder and workflow coordinator at the UI boundary. Filesystem traversal belongs to `ProjectScanner`, file I/O belongs to `DocumentService`, immutable document semantics belong to `OpenDocument`, and rendering and confirmation-dialog state belong to composables.

Future build processes, watchers, PDF loading, semantic indexes, toolchain detection, and configuration persistence should not be implemented directly inside this class. State may coordinate their user-visible outcomes, but the mechanisms need independent lifecycles, concurrency, errors, and tests.

## Invariants

The current implementation establishes these invariants through its public application flows:

- A successfully opened project has a readable real directory as its root.
- Replacing a project clears the previous open-document list and active document.
- A failed project scan or document-service initialization leaves the current project state intact.
- An open document has an editable extension, is a regular file, and resolves inside the active project at open time.
- Paths are made absolute and normalized before state lookup; `DocumentService` additionally validates real-path confinement.
- A successfully opened document starts clean because `content == savedContent`.
- A successful save makes `savedContent == content` and clears the document error.
- A failed save retains both the edited content and its prior saved baseline, so the document remains modified.
- `activeDocumentPath`, when non-null after state operations, refers to an entry in `openDocuments`.
- Directories and final-component symbolic links are not opened as editable documents.
- The project scanner does not recurse through symbolic directory links.
- Ordinary close and project-replacement operations do not discard modified documents; discard requires a separate explicit path in the UI flow.

These are application invariants, not guarantees against concurrent external filesystem mutation. A file may change or disappear after validation.

## Current limitations

- The project tree is a static snapshot with no manual or automatic refresh operation.
- There is no file watcher or incremental scanner.
- External edits are neither detected nor reconciled. Saving can overwrite a change made after the document was opened.
- Project scanning and document I/O are synchronous and may block the UI on slow or large filesystems.
- There is no persistent per-project configuration.
- `mainDocument` has no detection, persistence, validation, or formal selection workflow.
- There is no compilation or toolchain discovery.
- `PreviewPanel` is a visual placeholder containing only a “PDF Preview” label; no PDF is loaded.
- There is no SyncTeX or LaTeX structural/semantic analysis.
- Scan exclusions are a fixed name set, not project-configurable ignore rules.
- The UI reports the count of scan issues but does not provide an issue browser.
- Document identity is path-based; renames, moves, and alternate path aliases are not tracked as the same logical document.

These limitations describe the baseline and do not imply immediate implementation commitments.

## Expected evolution

The current boundaries allow later work to add, through explicit designs:

- versioned project configuration and validated defaults;
- root-document selection and assisted detection;
- output-directory and generated-file policies;
- toolchain and compilation strategy selection;
- incremental refresh and a filesystem watcher;
- detection and resolution of external modifications;
- discovered LaTeX dependencies and resources;
- explicit treatment of generated artifacts;
- projects with multiple root documents or build targets.

Those additions may extend `TeXProject` or introduce separate configuration and runtime models. Their formats, precedence rules, concurrency behavior, and migration policies belong in future RFCs rather than this description of the implemented baseline.
