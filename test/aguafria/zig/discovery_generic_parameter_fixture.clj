(ns aguafria.zig.discovery-generic-parameter-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn parse-value T
  {:zig/qualifiers "!"}
  [[T {:attrs #{comptime}} :type]
   [number T]
   [options {:attrs #{comptime}}
    (a/struct [[:base {:default 10} :u8]
               [:allow-separators {:default false} :bool]])]]
  (= :_ options)
  (if (== number 0)
    (a/error-value :InvalidNumber)
    number))

(a/deftest do-not-execute
  (let [number (u16 42)]
    (= :_ (try (parse-value :u16 number (a/object [])))))
  (unreachable))
