(ns learn.example.test-while-error-capture
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defvar numbers-left :u32 ak/undefined)

(az/defn- eventuallyErrorSequence [:error-union :anyerror :u32] []
  (if (ak/== numbers-left 0)
    (az/error-value :ReachedZero)
    (let []
      (ak/-= numbers-left 1)
      numbers-left)))

(az/deftest while-error-capture-test
  (let [sum (ak/var 0 :u32)]
    (ak/= numbers-left 3)
    (az/while-loop {:payload [number]
                    :error [error]
                    :else [(try (testing/expectEqual
                                 (az/error-value :ReachedZero) error))]}
      (eventuallyErrorSequence)
      (ak/+= sum number))))

(comment
  (while-error-capture-test))
