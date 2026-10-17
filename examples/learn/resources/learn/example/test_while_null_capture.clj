(ns learn.example.test-while-null-capture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defvar numbers-left :u32 undefined)

(a/defn- eventuallyNullSequence [:optional :u32] []
  (if (== numbers-left 0)
    nil
    (let []
      (-= numbers-left 1)
      numbers-left)))

(a/deftest while-null-capture
  (let [sum1 (k/var 0 :u32)]
    (= numbers-left 3)
    (a/while-loop {:payload [value]} (eventuallyNullSequence)
                  (+= sum1 value))
    (k/try (testing/expectEqual 3 sum1)))

  ;; null capture with an else block
  (let [sum2 (k/var 0 :u32)]
    (= numbers-left 3)
    (a/while-loop {:payload [value]
                   :else [(k/try (testing/expectEqual 3 sum2))]}
                  (eventuallyNullSequence)
                  (+= sum2 value)))

  ;; null capture with a continue expression
  (let [i (k/var 0 :u32)
        sum3 (k/var 0 :u32)]
    (= numbers-left 3)
    (a/while-loop {:payload [value]
                   :continue (a/assign-expr "+=" i 1)}
                  (eventuallyNullSequence)
                  (+= sum3 value))
    (k/try (testing/expectEqual 3 i))))

(comment
  (while-null-capture))
