#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build
(
  cd plugins/rewind
  clojure -M:build
)
clojure -M -e '(require (quote field-lab.build)) (clojure.java.io/copy (field-lab.build/prepare-host! :static-lib) (clojure.java.io/file "build/libpitoco_host_test.a")) (shutdown-agents)'
zig build-exe -OReleaseSafe -lc -lc++ build/libpitoco_host_test.a \
  test/native/extension_host_test.cpp -femit-bin=build/pitoco-host-test
zig build-lib -dynamic -OReleaseSafe -lc -lc++ \
  test/native/incompatible_plugin.cpp -femit-bin=build/incompatible-plugin.dylib
cd scripting
clojure -M:test
