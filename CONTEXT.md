# clj-apt-reaction-image — Domain glossary

## Core concept

**Maymay organisation** — the act of taking a flat directory of reaction images,
memes, and screenshots, then renaming each file to describe its content and
tagging it with macOS Finder tags for search.

## Glossary

| Term | Meaning |
|---|---|
| **organise** | Walk an images directory, run the rename+tag pipeline on each file. |
| **dry-run** | Preview mode — show what would change without touching any file. |
| **kebab-case** | Lowercase filename using hyphens as word separators, no spaces, max 30 chars. |
| **Finder tags** | macOS extended attribute (`_kMDItemUserTags`) visible as coloured dots in Finder and searchable via Spotlight. |
| **on-device** | All processing runs locally on the Mac — no network, no cloud, no API keys. Uses Apple's built-in Vision framework and Foundation Models. |
| **fallback name** | When the LLM can't generate a name (e.g. safety guardrails), construct one from the first classification label + face count + abbreviated OCR text. |
| **clip** | A moving-picture file — an animated GIF or a video. Organised like a still image, but described via frame sampling. |
| **frame sampling** | Analysing a clip by extracting N still frames at evenly-spaced fractions of its duration, skipping the very start and end. Deterministic: the same clip always yields the same frames, so a dry-run preview matches the real run. |
| **frame merge** | Collapsing N sampled frames into the single description the rest of the pipeline expects: OCR text concatenated in temporal order with repeats deduped, class labels unioned and ranked by max confidence, face count taken as the max seen in any one frame. |

## Pipeline stages

1. **OCR** — `auge --ocr` extracts visible text via `VNRecognizeTextRequest`.
2. **Classification** — `auge --classify` labels objects/scenes via `VNClassifyImageRequest`.
3. **Face detection** — `auge --faces` counts faces via `VNDetectFaceRectanglesRequest`.
4. **Tag generation** — `apfel-tag` classifies the combined OCR+label text into content tags.
5. **Filename generation** — `apfel` (Apple Foundation Model) with `--schema` generates a short kebab-case name.
6. **Sanitise** — trim to 30 chars, collapse hyphens, strip non-alphanumeric chars.
7. **Apply** — rename the file and set Finder tags via the Swift helper.

## Removed concepts (from the Ollama era)

- **Semantic index** — pre-built metadata store with captions, reaction tags, scene tags.
- **Query** — search the index with typed text or an image.
- **Reranking** — LLM reorders candidate matches by relevance.
- **Vision model** — an external multimodal LLM (qwen2.5vl) running via Ollama.
- **Checkpoint** — incremental index save during long runs.
