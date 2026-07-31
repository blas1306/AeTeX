# AeTeX Architecture 005 — Workspace Layout

## Status and scope

This document defines the implemented IDE-style arrangement of the Project,
Editor, and PDF Preview regions. It is deliberately a three-region workspace,
not a general docking system.

The Compose hierarchy is:

```text
AeTeXApp / Window
└── MainWindow
    ├── EditorToolbar
    ├── feedback banner
    └── Workspace
        └── tool rail | optional Project | divider | Editor | divider | optional PDF Preview
```

`AeTeXState` continues to own projects, open documents, compilation managers,
and preview managers. Hiding a panel changes only `WorkspaceLayout`; it does
not close, replace, or recreate any domain manager.

## Layout model and units

`WorkspaceLayout` is an immutable, Compose-independent model. It stores:

- the current Project and Preview widths;
- whether each side panel is collapsed;
- the last useful expanded width for each side panel; and
- the persistence schema version.

The 44 dp left tool rail is structural chrome, not a collapsed Project panel.
It is always present and currently contains one Project action. The action
uses a tested toggle policy: selecting it opens Project when closed and closes
Project when already open. `WorkspaceTool` and the rail composition provide
the extension point for future Search, Outline, Logs, or Settings actions
without introducing docking.

All model and persisted measurements are density-independent pixels (dp).
Compose converts a pointer delta from physical pixels to dp at the drag input
boundary. The native window minimum is converted from dp to AWT pixels at the
window boundary. No raw pixel value is persisted.

Schema 1 defaults and constraints are:

| Value | dp |
| --- | ---: |
| Project preferred width | 260 |
| Preview preferred width | 360 |
| Project expanded minimum | 180 |
| Preview expanded minimum | 260 |
| Editor minimum | 320 |
| Divider pointer target | 8 |
| Visible divider line | 1 |
| Persistent tool rail | 44 |
| Closed-Preview restore affordance | 28 |

The default 1440 dp window allocates 260 dp to Project and 360 dp to Preview;
after the tool rail and dividers, the editor receives 760 dp.

Persisted preferred widths are finite and bounded to 4096 dp. Invalid,
negative, non-finite, or out-of-range values use the corresponding default.
Collapsed current widths are zero while last-expanded widths remain valid.

## Constraint policy

For a given workspace width, resolution proceeds deterministically:

1. Allocate the persistent tool rail.
2. Reserve the 320 dp editor minimum when the remaining workspace permits it.
3. Allocate expanded dividers or the compact Preview restore affordance.
4. Allocate expanded-panel minimums.
5. Allocate the remaining portion of each requested preferred width.
6. Give all unrequested remainder to the editor.

The pure resolver accepts widths below the sum of all preferred regions for
tests and embedding: after keeping the tool rail visible, it preserves the
editor first and proportionally compresses side content without negative or
overlapping constraints. Panels are never auto-collapsed; collapse is always
explicit user state.

A divider drag updates only its adjacent preferred width. The Project divider
uses the pointer delta directly. The Preview divider uses the inverse delta.
Both calculations account for the tool rail, other side panel, dividers or
Preview edge affordance, and the editor minimum. Invalid deltas are ignored.
An interrupted drag leaves the last already-normalized state intact.

## Collapse, restoration, and view state

Project does not produce a separate collapsed header or labeled empty rail.
The persistent Project tool action is selected while Project is open and
toggles the expanded panel. Preview has a header-adjacent close control that
closes it toward the right. When closed, only a 28 dp right-edge restore
affordance remains; no Preview header or expanded width is measured.

Both expanded separators retain an 8 dp resize target, a 1 dp visual line,
resize cursor, focus semantics, tooltip, and left/right keyboard resizing.
Collapse controls are outside the separator pointer-input region, so clicking
them cannot begin a drag.

Collapse copies the expanded width to the last-expanded field before setting
the current width to zero. Restore uses that last width and clamps it against
the current workspace and the opposite panel. Very constrained layouts retain
a valid preferred minimum and let the resolver apply its documented compact
policy.

Project-tree expansion and Preview zoom/navigation state are remembered above
the conditional side-panel content. The editor remains continuously composed.
Consequently divider recomposition does not replace editor documents or tabs,
and collapse/restore does not reset Project expansion or Preview zoom. The
Preview list and Compose image copies are view resources and may be disposed
while hidden; the current `PreviewManager`, generation, snapshot, bounded
cache, and Ready state remain alive. Restoring observes that current state and
does not require a build.

Viewport reports may change when the Preview width changes because Compose
remeasures visible content. They continue through the existing
duplicate-coalescing, bounded preview scheduler. Workspace mutations never
call the build entry point or create a document generation.

## PDF zoom modes

`PreviewZoomMode` is Compose-independent view state with three identities:

- `FitWidth`;
- `FitPage`; and
- `Fixed(RenderScale)`.

The session default is `FitWidth`. Zoom mode is deliberately not added to the
schema-1 workspace preferences: those preferences contain window layout, while
zoom is document-view state. This also avoids persisting a derived fit
percentage. Rebuilding the same project or hiding Preview retains the current
session mode; a newly created application view starts in Fit Width.

Compose measures the Preview body after the panel header, zoom/navigation
toolbar, and any notice. Toolbar and notice height are therefore already
excluded from `viewportHeight`. The page list defines 32 dp total horizontal
margin and 32 dp total vertical content padding:

```text
availableContentWidth  = viewportWidth  - 32 dp
availableContentHeight = viewportHeight - 32 dp

FitWidth scale = availableContentWidth / displayedPageWidth

FitPage scale = min(
    availableContentWidth  / displayedPageWidth,
    availableContentHeight / displayedPageHeight
)
```

Displayed geometry swaps width and height for 90° and 270° rotation. The
current page selects the fit geometry, so portrait, landscape, rotated, and
mixed-dimension documents remain uniform and unstretched at any one instant.
Changing the current page may recompute a fit scale.

The exact finite fit result drives logical page dimensions. Physical display
scale is `logicalScale × LocalDensity.density`. Raster quality then requests
`displayScale × 1.5`, rounds upward into the existing 0.25 cache buckets, and
clamps to the existing 0.5–4.0 raster interval. Small divider movements remain
visually responsive without producing arbitrary floating-point cache keys or
an undersized nearest bucket. Invalid, non-finite, non-positive, or
padding-exhausted measurements produce no resolved zoom update. Fixed choices
use the existing 50%–400% logical safety clamp. `+` and `−` enter or adjust
Fixed mode using 25% steps; selecting 100% is `Fixed(1.0)`, never an alias for
a fit mode. The toolbar reports logical zoom percentage while retaining “Fit
Width” or “Fit Page” in its label.

When a normalized raster bucket changes, `PreviewManager.updateViewport`
supersedes handles for obsolete scale keys. Identical work is coalesced, queue
and worker limits remain enforced, and the old successful page may remain
visible until its replacement raster arrives. Resizing and zoom changes do not
open PDFBox, replace the generation, invalidate the successful artifact, or
request compilation. Density changes recompute raster scale without changing
the logical fit.

## User preference persistence

Workspace preferences are user-level UI state, not project configuration. The
file is UTF-8 TOML:

```toml
schema = 1
project_panel_width_dp = 260.000
preview_panel_width_dp = 360.000
project_panel_collapsed = false
preview_panel_collapsed = false
last_project_panel_width_dp = 260.000
last_preview_panel_width_dp = 360.000
```

Locations are:

- Linux and other Unix: `$XDG_CONFIG_HOME/aetex/workspace.toml`, falling back
  to `~/.config/aetex/workspace.toml`;
- macOS: `~/Library/Application Support/AeTeX/workspace.toml`;
- Windows: `%APPDATA%\AeTeX\workspace.toml`, with the conventional roaming
  profile fallback.

The parser reads at most 64 KiB. A missing, malformed, oversized, or
unsupported-schema file produces defaults. Unknown schema-1 fields are
ignored, which is the forward-compatibility policy. Individual invalid values
are normalized independently. Existing schema-1 files from the former
collapsed-rail presentation need no migration: collapsed booleans and
last-expanded widths retain their meaning, and the resolver maps them to the
persistent Project rail and compact Preview edge affordance.

Writes are coalesced for 350 ms after layout changes. Completing or cancelling
a divider drag flushes once, and normal application shutdown flushes the final
state. A monotonically increasing revision prevents an older scheduled
callback from overwriting a newer state. Writes use a sibling temporary file,
then atomic replacement when supported and replacing move otherwise. Failures
produce a bounded warning and leave the application usable; temporary cleanup
is best effort.

## Non-goals

This milestone does not provide:

- arbitrary docking or rearrangement;
- floating windows;
- user-customizable tool-rail ordering;
- tabbed tool windows;
- per-project workspace layouts;
- window-coordinate persistence; or
- changes to project, compilation, or preview architecture.
