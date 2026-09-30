(ns learn.snippet.force-file-discovery
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime discover-api-files
  (k/= :_ (k/import "api.zig"))
  (when (k/== (:tag builtin/os) :.windows)
    (k/= :_ (k/import "windows_api.zig"))))

;; A test-mode comptime guard replaces Zig's unnamed discovery test, so
;; importing these test modules does not depend on the test-name filter.
(az/defcomptime discover-test-files
  (when builtin/is_test
    (k/= :_ (k/import "tests.zig"))
    (when (k/== (:tag builtin/os) :.windows)
      (k/= :_ (k/import "windows_tests.zig")))))
