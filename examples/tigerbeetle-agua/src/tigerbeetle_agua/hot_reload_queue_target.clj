(ns tigerbeetle-agua.hot-reload-queue-target
  "A concrete native caller of TigerBeetle's real generic QueueType."
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as a]
            [tigerbeetle.src.queue :as queue]))

(a/defstruct QueueItem
  {:layout :extern}
  [[:link queue/QueueLink]
   [:value :u32]])

(a/defn queue-size :usize
  []
  (ak/sizeOf (queue/QueueType QueueItem)))
