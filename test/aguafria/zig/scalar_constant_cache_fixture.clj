(ns aguafria.zig.scalar-constant-cache-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.math :as math]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defconst inferred-integer (i32 1060))
(a/defconst inferred-boolean (== 1 2))
(a/defconst inferred-float (f32 1.5))
(a/defconst wide-nan (math/nan :f128))
(a/defconst infinity (math/inf :f32))
(a/defconst negative-infinity (- (math/inf :f64)))
(a/defconst precise-float
  (f128 (a/number-literal "0x1.0000000000000000000000000001p0")))

(a/deftest inferred-integer-check
  (try (testing/expectEqual 1060 inferred-integer)))

(a/deftest inferred-boolean-check
  (try (testing/expectEqual false inferred-boolean)))

(a/deftest inferred-float-check
  (try (testing/expectEqual 1.5 inferred-float)))

(a/deftest wide-float-check
  (try (testing/expectEqual true (math/isNan wide-nan)))
  (try (testing/expectEqual true (> precise-float (f128 1.0)))))

(a/deftest infinity-check
  (try (testing/expectEqual true (math/isInf infinity)))
  (try (testing/expectEqual true (math/isInf negative-infinity))))
