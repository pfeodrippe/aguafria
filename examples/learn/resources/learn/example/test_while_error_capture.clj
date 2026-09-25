(ns learn.example.test-while-error-capture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defvar numbers-left :u32 k/undefined)

(az/defn- eventuallyErrorSequence [:error-union :anyerror :u32] []
  (if (k/== numbers-left 0)
    (az/error-value :ReachedZero)
    (let []
      (k/-= numbers-left 1)
      numbers-left)))

(az/deftest while-error-capture-test
  (let [sum (k/var 0 :u32)]
    (k/= numbers-left 3)
    (az/while-loop {:payload [number]
                    :error [error]
                    :else [(try (testing/expectEqual
                                 (az/error-value :ReachedZero) error))]}
      (eventuallyErrorSequence)
      (k/+= sum number))))

(comment
  (while-error-capture-test))
