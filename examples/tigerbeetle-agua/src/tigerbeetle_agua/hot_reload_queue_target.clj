(ns tigerbeetle-agua.hot-reload-queue-target
  "A concrete native caller of TigerBeetle's real generic QueueType."
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [tigerbeetle.src.queue :as queue]))

(a/defstruct QueueItem
  {:layout :extern}
  [[:link (:Link (queue/QueueType QueueItem))]
   [:value :u32]])

(a/defn queue-size :usize
  []
  (sizeOf (queue/QueueType QueueItem)))
