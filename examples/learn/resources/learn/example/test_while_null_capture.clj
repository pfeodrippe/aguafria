(ns learn.example.test-while-null-capture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defvar numbers-left :u32 k/undefined)

(az/defn- eventuallyNullSequence [:optional :u32] []
  (if (k/== numbers-left 0)
    nil
    (let []
      (k/-= numbers-left 1)
      numbers-left)))

(az/deftest while-null-capture-test
  (let [sum (k/var 0 :u32)]
    (k/= numbers-left 3)
    (az/while-loop {:payload [number]} (eventuallyNullSequence)
      (k/+= sum number))
    (try (testing/expectEqual 3 sum)))

  ;; An optional loop's else branch runs when its condition becomes null.
  (let [sum (k/var 0 :u32)]
    (k/= numbers-left 3)
    (az/while-loop {:payload [number]
                    :else [(try (testing/expectEqual 3 sum))]}
      (eventuallyNullSequence)
      (k/+= sum number)))

  (let [iterations (k/var 0 :u32)
        sum (k/var 0 :u32)]
    (k/= numbers-left 3)
    (az/while-loop {:payload [number]
                    :continue (az/assign-expr "+=" iterations 1)}
      (eventuallyNullSequence)
      (k/+= sum number))
    (try (testing/expectEqual 3 iterations))))

(comment
  (while-null-capture-test))
