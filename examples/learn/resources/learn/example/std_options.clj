(ns learn.example.std-options
  (:require [aguafria.keyword :as ak]
            [aguafria.std.log :as log]
            [aguafria.zig :as az]))

(az/defconst std-options
  "Override standard-library behavior through std.Options."
  {:attrs #{:public}}
  aguafria.std/Options
  {:enable_segfault_handler true
   :logFn write-log})

(az/defn- write-log :void
  [[level {:zig/prefix "comptime"} log/Level]
   [scope {:zig/prefix "comptime"} (ak/EnumLiteral)]
   [format {:zig/prefix "comptime"} [:slice-const :u8]]
   [arguments :anytype]]
  ;; A custom logger can replace this delegation to the default implementation.
  (log/defaultLog level scope format arguments))
