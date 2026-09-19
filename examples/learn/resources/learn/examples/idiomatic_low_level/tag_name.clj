(ns learn.examples.idiomatic-low-level.tag-name
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Small2
  (az/container {:kind :union :attrs #{:enum}}
    (az/field-decl :a :i32)
    (az/field-decl :b :bool)
    (az/field-decl :c :u8)))

(az/deftest tag-name-test
  (try (testing/expectEqualSlices
         (az/type :u8) "a" (ak/tagName (az/field Small2 :a)))))
