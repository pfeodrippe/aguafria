(ns learn.example.test-while-error-capture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defvar numbers-left :u32 undefined)

(a/defn- eventuallyErrorSequence [:error-union :anyerror :u32] []
  (if (== numbers-left 0)
    (a/error-value :ReachedZero)
    (let []
      (-= numbers-left 1)
      numbers-left)))

(a/deftest while-error-union-capture
  (let [sum1 (k/var 0 :u32)]
    (= numbers-left 3)
    (a/while-loop {:payload [value]
                   :error [err]
                   :else [(k/try (testing/expectEqual
                                  (a/error-value :ReachedZero) err))]}
                  (eventuallyErrorSequence)
                  (+= sum1 value))))

(comment
  (while-error-union-capture))
