(ns learn.snippet.force-file-discovery
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defimport builtin "builtin" [[os-tag "os.tag"] [is-test "is_test"]])

(az/defcomptime discover-api-files
  (k/= :_ (k/import "api.zig"))
  (when (k/== builtin/os-tag :.windows)
    (k/= :_ (k/import "windows_api.zig"))))

;; A test-mode comptime guard replaces Zig's unnamed discovery test, so
;; importing these test modules does not depend on the test-name filter.
(az/defcomptime discover-test-files
  (when builtin/is-test
    (k/= :_ (k/import "tests.zig"))
    (when (k/== builtin/os-tag :.windows)
      (k/= :_ (k/import "windows_tests.zig")))))
