(ns aguafria.zig.jvm-enum-coercion-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defenum Tag {:type :u8}
  [:number :ratio :ready-now])

(a/defunion Payload {:type Tag}
  [[:number :i32]
   [:ratio :f32]
   [:ready-now :void]])

(a/defenum State
  [:ready :waiting])

(a/defunion Flag {:type State}
  [[:ready :void]
   [:waiting :void]])

(a/defstruct Constants
  [[:ready {:const :.ready_now} Tag]
   [:changing {:var :.ready_now} Tag]])

(a/defn echo-tag Tag [[tag Tag]]
  tag)

(a/deftest known-enum-to-union
  (let [tag (:ready-now Tag)
        payload (k/as tag Payload)]
    (try (testing/expectEqual (:ready-now Tag) payload)))
  (let [payload (k/as :.ready_now Payload)]
    (try (testing/expectEqual (:ready-now Tag) payload)))
  (let [payload (k/as (:ready Constants) Payload)]
    (try (testing/expectEqual (:ready-now Tag) payload)))
  (let [payload (Payload {:ratio 0.25})]
    (try (testing/expectEqual 0.25 (:ratio payload)))))

(a/deftest aliased-and-shadowed-enum-sources
  (let [tag (:ready-now Tag)
        alias tag
        payload (k/as alias Payload)]
    (try (testing/expectEqual alias payload)))
  (let [tag (:ready-now Tag)
        tag (echo-tag tag)]
    (try (testing/expectEqual tag (:ready-now Tag))))
  (let [tag (:number Tag)
        returned (echo-tag tag)]
    (try (testing/expectEqual tag returned))))
