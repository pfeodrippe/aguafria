(ns learn.example.test-while-null-capture
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defvar numbers-left :u32 ak/undefined)

(az/defn- eventuallyNullSequence [:optional :u32] []
  (if (ak/== numbers-left 0)
    nil
    (let []
      (ak/-= numbers-left 1)
      numbers-left)))

(az/deftest while-null-capture-test
  (let [sum (ak/var 0 :u32)]
    (ak/= numbers-left 3)
    (az/while-loop {:payload [number]} (eventuallyNullSequence)
      (ak/+= sum number))
    (try (testing/expectEqual 3 sum)))

  ;; An optional loop's else branch runs when its condition becomes null.
  (let [sum (ak/var 0 :u32)]
    (ak/= numbers-left 3)
    (az/while-loop {:payload [number]
                    :else [(try (testing/expectEqual 3 sum))]}
      (eventuallyNullSequence)
      (ak/+= sum number)))

  (let [iterations (ak/var 0 :u32)
        sum (ak/var 0 :u32)]
    (ak/= numbers-left 3)
    (az/while-loop {:payload [number]
                    :continue (az/assign-expr "+=" iterations 1)}
      (eventuallyNullSequence)
      (ak/+= sum number))
    (try (testing/expectEqual 3 iterations))))

(comment
  (while-null-capture-test))
