# Workspace panels

AeTeX uses a persistent tool rail on the far left, an optional Project panel,
the editor, and an optional PDF Preview on the right.

The Project button on the tool rail toggles Project: selecting it opens the
panel when closed and closes it when already open. Closing Project removes its
entire expanded width; the narrow tool rail remains. Opening it restores the
last useful width, clamped to the current window. Expanded folders and the
selected editor document remain unchanged. **New Project...** and **Open
Project...** remain at the top of the expanded panel.

Drag either thin vertical separator to resize its adjacent panel. The full
separator is a wider pointer target than its visible line, shows the horizontal
resize cursor, has a tooltip, accepts keyboard focus, and supports left/right
arrow resizing. AeTeX reserves usable editor space and clamps a drag that
would consume it.

Use **›** beside the PDF Preview header to close Preview toward the right.
Closing it removes the panel and header and gives the freed width to the
editor. Use the compact **‹** at the right window edge to restore the most
recent Preview width, clamped for the current window. The collapse buttons are
separate from the draggable separators, so a click does not start a resize.

Collapsing Preview does not close the PDF, cancel the current generation, or
request another build. Compose may release hidden image copies, but the
Preview manager, Ready artifact, page cache, navigation position, and zoom mode
remain alive. Restoring shows the current or stale PDF without rebuilding.

## PDF zoom

New Preview views start in **Fit Width**. Open the zoom selector to choose:

- **Fit Width**;
- **Fit Page**;
- **50%**, **75%**, **100%**, **125%**, **150%**, or **200%**.

**Fit Width** makes the current page use the available horizontal content
width. Widening Preview grows the page and narrowing Preview shrinks it.
**Fit Page** uses the smaller horizontal or vertical fit so the complete
current page fits in the measured Preview body. Page rotation and landscape
geometry are respected, and pages are never stretched non-uniformly.

The selector continues to say **Fit Width** or **Fit Page** while also showing
the current effective percentage. **100%** is a distinct fixed zoom:
`Fixed(1.0)`. Resizing Preview does not change a fixed scale. Using **+** or
**−** from either fit mode switches to a fixed scale and then changes it in
25% steps. Fixed zoom is safely clamped between 50% and 400%.

Fit results are session-level view state rather than saved workspace
preferences. AeTeX never stores a derived fit percentage. Hiding and restoring
Preview retains the current mode for the session.

## Saved layout

AeTeX saves panel widths and collapsed state as user preferences:

- Linux/Unix: `$XDG_CONFIG_HOME/aetex/workspace.toml`, or
  `~/.config/aetex/workspace.toml`;
- macOS: `~/Library/Application Support/AeTeX/workspace.toml`;
- Windows: `%APPDATA%\AeTeX\workspace.toml`.

This file is separate from every project and never changes
`.aetex/project.toml`. AeTeX writes it shortly after the last resize and again
during normal shutdown. If it is missing, corrupt, oversized, or from an
unsupported schema, AeTeX starts with safe defaults. A write failure does not
close the application or affect project files.

The workspace is intentionally fixed to these regions. Arbitrary docking,
floating windows, user-customizable tool-rail ordering, tabbed tool windows,
and per-project layouts are not part of this milestone.

## Preview image quality

The percentage shown in Preview is logical zoom. AeTeX converts it through the
current Compose display density, including fractional scaling and Retina, then
requests a bounded 1.5× oversampled PDF raster. Raster requests share 25%
quality buckets and remain subject to the 192 MiB cache, render queue, and
per-page safety limits. Resizing a fit mode can reuse a nearby quality bucket
while the logical page size still follows the panel exactly. Changing zoom or
moving the window between display densities never requests a LaTeX build or
creates a new PDF generation.

## Editor appearance and syntax colors

The editor uses one professional dark palette. The opaque white caret remains
visible over both the editor and subtle current-line highlight. Selection stays
blue and distinguishable when focus moves away from the editor.

Lexical highlighting recognizes LaTeX commands, structural commands,
comments, `begin`/`end` environment names, optional and mandatory arguments,
nested braces, numbers, escapes, and the common inline/display math
delimiters. It is intentionally tolerant while a construct is unfinished and
updates incrementally as lines are edited.

This visual milestone does not provide autocompletion, semantic analysis, LSP,
code folding, or diagnostics. Highlighting indicates lexical form only; it does
not determine whether TeX commands, environments, references, or math are
semantically valid.
