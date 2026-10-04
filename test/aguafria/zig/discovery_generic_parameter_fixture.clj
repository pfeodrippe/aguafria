(ns aguafria.zig.discovery-generic-parameter-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn parse-value T
  {:zig/qualifiers "!"}
  [[T {:attrs #{k/comptime}} :type]
   [number T]
   [options {:attrs #{k/comptime}}
    (a/struct [[:base {:default 10} :u8]
               [:allow-separators {:default false} :bool]])]]
  (k/= :_ options)
  (if (k/== number 0)
    (a/error-value :InvalidNumber)
    number))

(a/deftest do-not-execute
  (let [number (k/u16 42)]
    (k/= :_ (try (parse-value :u16 number (a/object [])))))
  (k/unreachable))
