(ns learn.example.test-src-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.builtin.SourceLocation :as source-location]
            [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

;; k/src describes the generated Zig location, not the Clojure source line.
;; Adjacent let bindings become adjacent declarations in the generated file.
(az/defn- doTheTest :!void
  []
  (let [first-location (k/src)
        next-location (k/src)]
    (try (testing/expectEqual (k/+ (source-location/-line first-location) 1)
                              (source-location/-line next-location)))
    ;; The longer binding name shifts the builtin's source column.
    (try (testing/expectEqual (k/- (source-location/-column first-location) 1)
                              (source-location/-column next-location)))
    (try (testing/expect (mem/endsWith :u8 (source-location/-fn_name first-location) "doTheTest")))
    (try (testing/expect (mem/endsWith :u8 (source-location/-file first-location) "test_src_builtin.zig")))))

(az/deftest source-location-test
  (try (doTheTest)))

(comment
  (source-location-test))
