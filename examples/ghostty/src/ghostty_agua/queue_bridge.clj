(ns ghostty-agua.queue-bridge
  "Native behavior checks for Ghostty's converted generic queue."
  (:require [aguafria.keyword :as k]
            [aguafria.std.Io :as io]
            [aguafria.zig :as a]
            [ghostty.src.datastruct.blocking-queue :as blocking-queue]))

(a/defconst Queue (blocking-queue/BlockingQueue :u64 4))

(a/defn fill-count :u32
  "Return how many values the queue accepts before its nonblocking limit."
  []
  (let [queue (k/var {} Queue)]
    (k/for [value (a/array [1 2 3 4] :u64)]
      (k/= :_ ((:push queue) io/failing value :.instant)))
    (:len queue)))
