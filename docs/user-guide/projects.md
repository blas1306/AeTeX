# AeTeX projects

AeTeX can open any readable directory, but only a configured project can be
built.

- A **directory** is any opened filesystem directory.
- A **configured project** contains a valid `.aetex/project.toml`.
- An **unconfigured directory** does not contain that file and offers
  **Initialize Project**.
- An **invalid project** contains the file, but it cannot be parsed or
  validated. AeTeX shows the configuration path and diagnostic and does not
  overwrite it.

## Create, open, and initialize

**New Project...** creates a project in a destination that does not exist or is
an existing empty ordinary directory. Non-empty destinations, files, and
symbolic links are rejected. The new project contains `.aetex/project.toml` and
`src/main.tex`, is validated, opened, and immediately eligible for Build.

**Open Project...** opens a directory and classifies it using the canonical
configuration loader. A valid project created manually, by a script, or by
another AeTeX-compatible tool is handled exactly like an IDE-created project.

**Initialize Project** first lists the paths AeTeX will create. It preserves
unrelated files. AeTeX reuses one unambiguously detected LaTeX main document,
or creates `src/main.tex` when that path is free. An existing configuration is
never overwritten: a valid one returns `AlreadyConfigured`, while an invalid
or unsupported one is reported as a conflict.

## Schema 1

The configuration is UTF-8 TOML at exactly `.aetex/project.toml`.

```toml
schema = 1
main = "src/main.tex"
engine = "pdflatex"
strategy = "latexmk"
output = "build"
```

| Field | Required | Values and behavior |
| --- | --- | --- |
| `schema` | Yes | Integer `1` |
| `main` | No | Project-relative readable `.tex` main document; Build requires it to be confirmed |
| `engine` | No | `pdflatex`, `xelatex`, or `lualatex`; inferred from a confirmed main and then defaults to `pdflatex` |
| `strategy` | No | `latexmk`; defaults to `latexmk` |
| `output` | No | Safe project-relative directory; defaults to `build` |

Paths use `/`, remain inside the project root, and may not place the main file
under metadata, generated output, or another scanner exclusion. Unknown fields
in schema 1 are warning-level for reading. AeTeX provisioning never rewrites an
existing configuration, so it cannot discard unknown fields, comments, or
formatting.

Build is available only after this file parses and validates and the effective
configuration has one confirmed main document, engine, strategy, and output
directory. Merely creating the file is not sufficient.
