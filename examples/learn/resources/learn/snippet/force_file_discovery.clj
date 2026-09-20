(ns learn.snippet.force-file-discovery
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defimport builtin "builtin" [[os-tag "os.tag"] [is-test "is_test"]])

(az/defcomptime discover-api-files
  (ak/= :_ (ak/import "api.zig"))
  (when (ak/== builtin/os-tag :.windows)
    (ak/= :_ (ak/import "windows_api.zig"))))

;; A test-mode comptime guard replaces Zig's unnamed discovery test, so
;; importing these test modules does not depend on the test-name filter.
(az/defcomptime discover-test-files
  (when builtin/is-test
    (ak/= :_ (ak/import "tests.zig"))
    (when (ak/== builtin/os-tag :.windows)
      (ak/= :_ (ak/import "windows_tests.zig")))))
