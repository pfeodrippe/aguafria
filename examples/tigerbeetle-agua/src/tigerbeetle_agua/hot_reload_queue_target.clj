(ns tigerbeetle-agua.hot-reload-queue-target
  "A concrete native caller of TigerBeetle's real generic QueueType."
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]
            [tigerbeetle.src.queue :as queue]))

(az/defstruct QueueItem
  {:layout :extern}
  [[:link queue/QueueLink]
   [:value :u32]])

(az/defn queue-size
  :- :usize
  []
  (ak/sizeOf (queue/QueueType QueueItem)))
