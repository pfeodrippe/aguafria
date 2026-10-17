(ns aguafria.zig.discovery-nested-fields-fixture
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.zig.discovery-nested-fields-types-fixture :as types]
            [aguafria.keyword :as k :refer :all]))

(a/defn make-exchange types/Exchange
  []
  (types/Exchange {:result {:request {:parent nil :actor 7}}}))

(a/defn actor :u8
  [[exchange types/Exchange]]
  (let [result (:result exchange)
        request (:request result)]
    (:actor request)))
