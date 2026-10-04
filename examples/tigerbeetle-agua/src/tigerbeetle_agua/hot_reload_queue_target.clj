(ns tigerbeetle-agua.hot-reload-queue-target
  "A concrete native caller of TigerBeetle's real generic QueueType."
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [tigerbeetle.src.queue :as queue]))

(a/defstruct QueueItem
  {:layout :extern}
  [[:link (:Link (queue/QueueType QueueItem))]
   [:value :u32]])

(a/defn queue-size :usize
  []
  (k/sizeOf (queue/QueueType QueueItem)))
