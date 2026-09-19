(ns ghostty-agua.live
  "Small hand-written native hooks for the Ghostty hot-reload walkthrough."
  (:require [aguafria.zig :as az]))

(az/defn title-version :u32
  "Version rendered into the title of an existing native Ghostty session."
  []
  1)
