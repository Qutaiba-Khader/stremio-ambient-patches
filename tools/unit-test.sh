#!/usr/bin/env bash
# Unit tests of the extension's pure parts on a plain JVM (no Android, no Gradle), and the page check.
set -euo pipefail
cd "$(dirname "$0")/.."
python3 tools/gen-page.py --check || { echo "Page.java is not what page.html gives: run tools/gen-page.py"; exit 1; }
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
src=extensions/extension/src/main/java/app/subsense/ambient
javac -d "$out" -Xlint:all -Werror $src/Settings.java $src/Json.java $src/Edges.java $src/Fb.java $src/Net.java $src/Formats.java \
    test/java/app/subsense/ambient/AmbientTest.java
java -cp "$out" app.subsense.ambient.AmbientTest
