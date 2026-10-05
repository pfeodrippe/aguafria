(ns aguafria.zig.jvm-metadata-parity-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.meta :as meta]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defenum Tag [:ok :not_ok])
(a/defunion Result {:type Tag} [[:ok :u8] [:not_ok :void]])

(a/deftest captured-native-parameters
  (let [result (Result {:ok 42})]
    (try (testing/expectEqual (:ok Tag) (k/as result Tag)))
    (a/switch-stmt result
                   (case [:.ok] [payload] (try (testing/expectEqual 42 payload)))
                   (case [:.not_ok] (k/unreachable)))
    (a/switch-stmt result
                   (case [:.ok] [_ tag]
                         (k/comptime (debug/assert (k/== tag :.ok))))
                   (case [:.not_ok] (k/unreachable)))))

(a/deftest retained-native-type-expression
  (try (testing/expectEqual Tag (meta/Tag Result))))
