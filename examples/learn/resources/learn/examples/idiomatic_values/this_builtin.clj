(ns learn.examples.idiomatic-values.this-builtin
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest this-type-test
  (let [^:var items (az/array-init [:array _ :i32] [1 2 3 4])
        list (az/init (List :i32) {:items (az/slice items 0)})]
    (try (testing/expectEqual 4 ((az/field list :length))))))

(az/defn- List :type
  [[T {:zig/prefix "comptime"} :type]]
  (az/container {:kind :struct}
    (az/const-decl Self (ak/This))
    (az/field-decl :items [:slice T])
    (az/fn-decl length
      :- :usize
      [[self Self]]
      (az/field (az/field self :items) :len))))
