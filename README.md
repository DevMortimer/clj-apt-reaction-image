# clj-apt-reaction-image

**On-device reaction image organiser** — rename and tag your maymays using
Apple's built-in Vision and Foundation Models.

No cloud. No API keys. No Ollama. 100% on-device.

Tested on my M5 MacBook on 400+ images. Works.

## What it does

Walks a directory of images and clips (GIFs, mp4/mov/m4v/webm/mkv videos), then for each one:

1. **Frame sampling** — ffmpeg extracts N still frames for analysis (clips only; default 6)
2. **OCR** — extracts visible text (`auge --ocr`)
3. **Classify** — identifies objects in the scene (`auge --classify`)
4. **Detect** — counts faces (`auge --faces`)
5. **Tag** — generates Finder tags for Spotlight search (`apfel-tag`)
6. **Rename** — creates a short kebab-case filename (`apfel`)
7. **Apply** — renames the file and sets Finder tags

```bash
# Before:     distracted-bf.jpg   doge.jpg   screenshot.png
# After:      people-adult-clothing-jacket-c.jpg  corgi-dog.jpg  telegram-edit-view-window.png
```

## Requirements

- macOS 26+ (Tahoe) on Apple Silicon
- [Homebrew](https://brew.sh)
- Apple Intelligence enabled in System Settings

## Install

```bash
# Install on-device tools
make install

# Compile the Finder-tag helper
make compile
```

## Usage

**Dry run** (preview changes without touching files):

```bash
make run IMAGES_DIR="~/iCloud/Pictures/maymays" ARGS="--dry-run"
```

**Actually rename and tag**:

```bash
make run IMAGES_DIR="~/iCloud/Pictures/maymays"
```

Or run directly with Clojure:

```bash
clojure -M:run organize --images-dir "~/iCloud/Pictures/maymays" --dry-run
```

**Re-runs skip finished files.** A file that already carries Finder tags is
treated as categorized and skipped, so re-running on the same directory only
picks up new additions. Force a full reprocess with `-a` / `--all`:

```bash
clojure -M:run organize --images-dir "~/iCloud/Pictures/maymays" --all
```

**JSON output** for scripting:

```bash
clojure -M:run organize --images-dir "~/iCloud/Pictures/maymays" --output json --dry-run
```

**More frames for dense clips:**

```bash
clojure -M:run organize --images-dir "~/iCloud/Videos/gifs" --frames 12 --dry-run
```

## How it works

| Step | Tool | What it produces |
|---|---|---|
| Frame sampling | `ffmpeg` | N still frames from each clip (default 6) |
| OCR | `auge --ocr` | visible text from the image |
| Classification | `auge --classify` | object/scene labels (top 5) |
| Face detection | `auge --faces` | number of faces |
| Content tagging | `apfel-tag` | Finder tags for search |
| Smart naming | `apfel` (Apple Foundation Model) | short kebab-case filename |
| Read/set tags | `mac/tags/main.swift` | reads and writes Finder tags via xattr |

All tools run Apple's on-device frameworks — Vision (`VNRecognizeTextRequest`,
`VNClassifyImageRequest`, `VNDetectFaceRectanglesRequest`) and FoundationModels.

## Tests

```bash
make test
# or: clojure -M:test
```

## Project structure

```
├── Makefile                     — install, compile, run, test
├── deps.edn                    — Clojure deps (just data.json)
├── src/
│   └── clj_apt_reaction_image/
│       └── core.clj            — pipeline orchestrator
├── mac/
│   └── tags/
│       └── main.swift          — Finder tag helper
└── test/
    └── clj_apt_reaction_image/
        └── core_test.clj       — unit tests
```

## Caveats

- **Apple Intelligence must be enabled** in System Settings for `apfel` and `apfel-tag`.
- **Safety guardrails** on Apple's Foundation Model may block images with people,
  falling back to label-based naming.
- Images with visible text (screenshots, memes, chat images) get the best filenames.
- Plain reaction images without text get descriptive-but-generic names from
  classification labels.
- Clips are judged by sampled frames only — a punchline that appears between
  samples can be missed (raise `--frames` for dense clips).
- webm and mkv have no native macOS support and are handled entirely through
  ffmpeg frame extraction.
