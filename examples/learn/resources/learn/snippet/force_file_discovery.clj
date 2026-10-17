(ns learn.snippet.force-file-discovery
  (:refer-clojure :only [])
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defcomptime discover-api-files
  (= :_ (import "api.zig"))
  (when (== (:tag builtin/os) :windows)
    (= :_ (import "windows_api.zig"))))

;; A test-mode comptime guard replaces Zig's unnamed discovery test, so
;; importing these test modules does not depend on the test-name filter.
(a/defcomptime discover-test-files
  (when builtin/is_test
    (= :_ (import "tests.zig"))
    (when (== (:tag builtin/os) :windows)
      (= :_ (import "windows_tests.zig")))))
