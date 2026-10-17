(ns learn.example.slicing-by-length
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest example
  (let [array (k/var (a/array [1 2 3 4] :i32))
        runtime-start (k/var 1 :usize)]
    (= :_ (k/& runtime-start))
    (let [length 2
          array-ptr-len (a/slice (a/slice array runtime-start) 0 length)]
      (k/try (testing/expectEqual (a/type [:* [:array length :i32]]) (TypeOf array-ptr-len))))))

(comment
  (example))
