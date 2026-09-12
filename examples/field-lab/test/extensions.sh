#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build
(
  cd plugins/rewind
  clojure -M:build
)
zig build-exe -OReleaseSafe -lc -lc++ native/extension_host.cpp \
  test/native/extension_host_test.cpp -femit-bin=build/pitoco-host-test
zig build-lib -dynamic -OReleaseSafe -lc -lc++ \
  test/native/incompatible_plugin.cpp -femit-bin=build/incompatible-plugin.dylib
cd scripting
clojure -M:test
