#!/bin/sh
# Renders the README images into docs/images/ (see Tests/ReadmeImages.swift).
set -eu
cd "$(dirname "$0")/.."
TEST_RUNNER_CAROM_README_IMAGES=1 xcodebuild -project Carom.xcodeproj -scheme Carom -configuration Debug \
    -only-testing:CaromTests/ReadmeImages test -quiet
ls -l ../../docs/images
