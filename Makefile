# clj-apt-reaction-image — on-device maymay organiser
#
# Dependencies: Homebrew
#   make install    — install auge, apfel, apfel-tag
#   make compile   — compile the tags Swift helper
#   make run       — run the Clojure app (pass ARGS="--help" for usage)
#   make test      — run Clojure tests
#   make clean     — remove compiled artifacts

INSTALL ?= brew install
CLOJURE ?= clojure
SWIFTC ?= swiftc

# Default images directory (override with IMAGES_DIR=...)
IMAGES_DIR ?= $(HOME)/iCloud/Pictures/maymays

.PHONY: install compile run test clean

install:
	$(INSTALL) Arthur-Ficial/tap/auge
	$(INSTALL) apfel
	$(INSTALL) Arthur-Ficial/tap/apfel-tag
	$(INSTALL) ffmpeg

compile:
	mkdir -p .clj-apt-reaction-image
	$(SWIFTC) -o .clj-apt-reaction-image/tags mac/tags/main.swift

run: compile
	$(CLOJURE) -M:run organize --images-dir "$(IMAGES_DIR)" $(ARGS)

test:
	$(CLOJURE) -M:test

clean:
	rm -rf .clj-apt-reaction-image/tags
