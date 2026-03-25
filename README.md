# maymay-reactor

Local-first reaction image retrieval for macOS.

The project now has two stages:

1. Index time:
   each image is analyzed once and stored as structured metadata.
2. Query time:
   typed text is matched against that metadata, then a rerank model picks the best reaction image from the shortlist.

## Requirements

- macOS
- `clojure`
- `tesseract`
- `ollama`

Recommended Ollama model setup:

```bash
ollama pull qwen2.5vl:3b
```

Defaults:

- vision model: `qwen2.5vl:3b`
- rank model: same as the vision model unless overridden
- vision max side: `512`
- checkpoint every: `10` changed images
- host: `OLLAMA_HOST` or `http://localhost:11434`

## Indexed Shape

Each image is stored with metadata similar to:

```json
{
  "id": "HZgWYgWDCFgYDAF1IN1pQEgY.jpg",
  "caption": "long screenshot of a forum post about Emacs",
  "reaction_tags": ["rant", "nerdy", "overexplaining", "argumentative"],
  "scene_tags": ["screenshot", "text-heavy", "forum"],
  "visible_text": "Emacs ...",
  "people": [],
  "emotions": ["frustrated", "smug"],
  "notes": "works as a reaction to long-winded technical arguments"
}
```

`id` is always the image filename including its extension.

## Behavior

- The first build is slow because it creates the semantic index.
- Before each semantic call, the app resizes the image for vision inference instead of sending the full original file.
- The index is stored at `.maymay-reactor/index.edn`.
- The index is checkpointed during long runs so partial progress is written to disk.
- On later runs, the app checks the indexed `images-dir` automatically.
- Only new files or files whose size / modification time changed are reprocessed.
- Removed files disappear from the refreshed index.
- OCR is still stored, but it is now only one signal among several.
- Ollama requests keep the model warm for longer so large indexing runs do not repeatedly cold-start.

## Usage

Build or refresh the index:

```bash
clojure -M:run index --images-dir "/path/to/images"
```

Query with typed text:

```bash
clojure -M:run query --text "bro what" --images-dir "/path/to/images"
```

Override models:

```bash
clojure -M:run index \
  --images-dir "/path/to/images" \
  --vision-model "qwen2.5vl:3b" \
  --rank-model "qwen2.5vl:3b" \
  --vision-max-side "512" \
  --checkpoint-every "10"
```

## Tests

Run:

```bash
clojure -M:test
```

Current tests cover:

- semantic search-text construction
- unchanged-file reuse
- changed-file reprocessing
- lexical shortlist behavior over semantic metadata

## Next

- screenshot input
- clipboard image input
- stronger shortlist retrieval beyond lexical metadata matching
