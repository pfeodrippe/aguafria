(ns ghostty-agua.live
  "Small hand-written native hooks for the Ghostty hot-reload walkthrough."
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defn title-version :u32
  "Version rendered into the title of an existing native Ghostty session."
  []
  1)
