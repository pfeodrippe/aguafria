(ns learn.example.test-src-builtin
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

;; ak/src describes the generated Zig location, not the Clojure source line.
;; Adjacent let bindings become adjacent declarations in the generated file.
(az/defn- doTheTest :void
  {:zig/qualifiers "!"}
  []
  (let [first-location (ak/src)
        next-location (ak/src)]
    (try (testing/expectEqual (+ (az/field first-location :line) 1)
                              (az/field next-location :line)))
    ;; The longer binding name shifts the builtin's source column.
    (try (testing/expectEqual (- (az/field first-location :column) 1)
                              (az/field next-location :column)))
    (try (testing/expect (mem/endsWith :u8 (az/field first-location :fn_name) "doTheTest")))
    (try (testing/expect (mem/endsWith :u8 (az/field first-location :file) "test_src_builtin.zig")))))

(az/deftest source-location-test
  (try (doTheTest)))
