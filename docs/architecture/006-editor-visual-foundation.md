# AeTeX Architecture 006 — Editor Visual Foundation

## Status and scope

This document defines the implemented visual and lexical foundation for the
source editor. It adds a coherent dark palette, caret/selection/current-line
visibility, and tolerant incremental LaTeX syntax coloring. It does not change
the project model, document persistence, compilation, or preview lifecycle.

## Canonical theme

`EditorTheme` is the one editor palette contract. `AeTeXEditorThemes.Dark` is
the current canonical instance. It supplies editor background and foreground,
focused and unfocused selection, focused and unfocused current-line colors,
caret, gutter and line-number colors, matching-brace color, and colors for
keywords, commands, environments, comments, arguments, strings, math
delimiters, numbers, braces, escapes, and errors.

The palette uses a near-neutral dark background and restrained blue, mauve,
olive, and amber accents. Commands remain identifiable, comments recede, and
math delimiters remain distinct without assigning every token family a vivid
color. Compose code receives the theme object instead of embedding editor
content colors at individual call sites.

The caret is opaque white and exceeds a 7:1 contrast ratio against both the
editor background and current-line fill. The focused selection is translucent
blue; the unfocused selection retains the same hue with lower opacity. The
active line changes slightly with focus and stays close enough to the base
background not to overpower token colors. AeTeX currently exposes one dark
editor theme; there is no light editor theme to validate in this milestone.

## Incremental lexical highlighting

`IncrementalLatexLexer` is a tolerant line-state lexer, not a semantic parser.
It recognizes:

- ordinary commands and a restrained set of structural command keywords;
- percent comments, respecting escaped percent characters;
- `begin`/`end` environment names;
- optional and mandatory argument regions, including nested and multiline
  braces;
- `$`, `$$`, `\(`, `\)`, `\[`, and `\]` math delimiters;
- numbers, brace characters, common two-character escapes, strings, plain
  text, and mismatched closing-delimiter errors.

Each cached line records its incoming and outgoing lexical state plus tokens
relative to that line. On edit, exact unchanged prefix lines are reused. The
changed range is re-lexed, then state propagation stops as soon as an unchanged
suffix line has the same incoming state as before. This keeps ordinary
single-line typing local while still handling an inserted opening brace or
math delimiter that legitimately changes later lines. Unterminated arguments
and math regions carry stable state forward instead of making the lexer fail.

The Compose visual transformation caches its annotated result for identical
text. Styling rebuilds annotations after an edit, but lexical analysis does not
reparse the complete document. No syntax edit requests a build or mutates the
immutable `OpenDocument` identity; normal `AeTeXState.updateDocument` remains
the source-content path.

## Explicit non-goals

This milestone does **not** include autocompletion, semantic analysis, an LSP,
Tree-sitter, code folding, or diagnostics. Error color is a theme and lexical
extension point, not a promise of TeX correctness. Matching-brace color is also
an explicit theme foundation; interactive brace matching is future work.
