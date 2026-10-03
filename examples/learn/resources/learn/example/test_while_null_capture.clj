(ns learn.example.test-while-null-capture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defvar numbers-left :u32 k/undefined)

(a/defn- eventuallyNullSequence [:optional :u32] []
  (if (k/== numbers-left 0)
    nil
    (let []
      (k/-= numbers-left 1)
      numbers-left)))

(a/deftest while-null-capture
  (let [sum1 (k/var 0 :u32)]
    (k/= numbers-left 3)
    (a/while-loop {:payload [value]} (eventuallyNullSequence)
                  (k/+= sum1 value))
    (try (testing/expectEqual 3 sum1)))

  ;; null capture with an else block
  (let [sum2 (k/var 0 :u32)]
    (k/= numbers-left 3)
    (a/while-loop {:payload [value]
                   :else [(try (testing/expectEqual 3 sum2))]}
                  (eventuallyNullSequence)
                  (k/+= sum2 value)))

  ;; null capture with a continue expression
  (let [i (k/var 0 :u32)
        sum3 (k/var 0 :u32)]
    (k/= numbers-left 3)
    (a/while-loop {:payload [value]
                   :continue (a/assign-expr "+=" i 1)}
                  (eventuallyNullSequence)
                  (k/+= sum3 value))
    (try (testing/expectEqual 3 i))))

(comment
  (while-null-capture))
