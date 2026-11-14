#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
build_dir="${ASTRA_NATIVE_BUILD_DIR:-build/host}"
cmake -S app/src/main/cpp -B "$build_dir" -DCMAKE_BUILD_TYPE=Release
cmake --build "$build_dir" --parallel 2
ctest --test-dir "$build_dir" --output-on-failure
