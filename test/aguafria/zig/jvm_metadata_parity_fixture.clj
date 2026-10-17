(ns aguafria.zig.jvm-metadata-parity-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.std.meta :as meta]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defenum Tag [:ok :not_ok])
(a/defunion Result {:type Tag} [[:ok :u8] [:not_ok :void]])

(a/deftest captured-native-parameters
  (let [result (Result {:ok 42})]
    (try (testing/expectEqual (:ok Tag) (as result Tag)))
    (switch result
      :ok (a/with-block :_ [payload] (try (testing/expectEqual 42 payload)))
      :not_ok (unreachable))
    (switch result
      :ok
      (a/with-block :_ [_ tag] (comptime (debug/assert (== tag :ok))))
      :not_ok (unreachable))))

(a/deftest retained-native-type-expression
  (try (testing/expectEqual Tag (meta/Tag Result))))
