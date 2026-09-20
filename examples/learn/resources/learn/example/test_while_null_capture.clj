(ns learn.example.test-while-null-capture
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defvar numbers-left :u32 ak/undefined)

(az/defn- next-number [:optional :u32] []
  (if (== numbers-left 0)
    nil
    (let []
      (ak/-= numbers-left 1)
      numbers-left)))

(az/deftest while-null-capture-test
  (let [^:var sum (ak/u32 0)]
    (set! numbers-left 3)
    (az/while-loop {:payload [number]} (next-number)
      (ak/+= sum number))
    (try (testing/expectEqual 3 sum)))

  ;; An optional loop's else branch runs when its condition becomes null.
  (let [^:var sum (ak/u32 0)]
    (set! numbers-left 3)
    (az/while-loop {:payload [number]
                    :else [(try (testing/expectEqual 3 sum))]}
      (next-number)
      (ak/+= sum number)))

  (let [^:var iterations (ak/u32 0)
        ^:var sum (ak/u32 0)]
    (set! numbers-left 3)
    (az/while-loop {:payload [number]
                    :continue (az/assign-expr "+=" iterations 1)}
      (next-number)
      (ak/+= sum number))
    (try (testing/expectEqual 3 iterations))))

(comment
  (while-null-capture-test))
