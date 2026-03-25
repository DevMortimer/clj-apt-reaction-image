# maymay-reactor

Local-first reaction image retrieval for macOS.

Task 1 MVP:

- Build a local OCR index from a folder of reaction images.
- Query that index with typed text.
- Return the best-matching image paths.
- OCR is powered by the locally installed `tesseract` CLI.
- Matching is lexical against extracted image text, not semantic yet.

Usage:

- `clojure -M:run index --images-dir "/path/to/images"`
- `clojure -M:run query --text "microwave time" --top 5`

Planned later:

- Screenshot OCR input
- Clipboard image paste
- Finder tag integration
- Better semantic ranking
