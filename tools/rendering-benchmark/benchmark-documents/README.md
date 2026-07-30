# Synthetic benchmark documents

The `generated/` directory is created by `generateCorpus` or `benchmark`.
Every PDF is produced deterministically by the experiment's `CorpusGenerator`;
no personal or copyrighted input is used.

The generated `manifest.tsv` records category, page count, byte size and
SHA-256 for each file. Invalid robustness fixtures are kept below
`generated/invalid/`.
