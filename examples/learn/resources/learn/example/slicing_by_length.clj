(ns learn.example.slicing-by-length
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest example
  (let [array (k/var (az/array [1 2 3 4] :i32))
        runtime-start (k/var 1 :usize)]
    (k/= :_ (k/& runtime-start))
    (let [length 2
          array-ptr-len (az/slice (az/slice array runtime-start) 0 length)]
      (try (testing/expectEqual (az/type [:* [:array length :i32]]) (k/TypeOf array-ptr-len))))))

(comment
  (example))
