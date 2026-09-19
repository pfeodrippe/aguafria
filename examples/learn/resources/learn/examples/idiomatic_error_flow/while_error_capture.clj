(ns learn.examples.idiomatic-error-flow.while-error-capture
  "Converted from test_while_error_capture.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defvar numbers-left :u32 ak/undefined)

(az/defn- next-number [:error-union :anyerror :u32] []
  (if (== numbers-left 0)
    (az/error-value :ReachedZero)
    (let []
      (ak/-= numbers-left 1)
      numbers-left)))

(az/deftest while-error-capture-test
  (let [^{:var :u32} sum 0]
    (set! numbers-left 3)
    (az/while-loop {:payload [number]
                    :error [error]
                    :else [(try (testing/expectEqual
                                  (az/error-value :ReachedZero) error))]}
      (next-number)
      (ak/+= sum number))))
