(ns learn.example.std-options
  (:require [aguafria.keyword :as k]
            [aguafria.std :as std]
            [aguafria.std.log :as log]
            [aguafria.zig :as az]))

(az/defn- myLogFn :void
  [[level {:attrs #{k/comptime}} log/Level]
   [scope {:attrs #{k/comptime}} (k/EnumLiteral)]
   [format {:attrs #{k/comptime}} [:slice-const :u8]]
   [args :anytype]]
  ;; We could do anything we want here!
  ;; ...but actually, let's just call the default implementation.
  (log/defaultLog level scope format args))

(az/defconst std-options
  "The presence of this declaration allows the program to override certain behaviors of the standard library.
For a full list of available options, see the documentation for `std.Options`."
  {:attrs #{k/pub}}
  std/Options
  {;; By default, in safe build modes, the standard library will attach a segfault handler to the program to
   ;; print a helpful stack trace if a segmentation fault occurs. Here, we can disable this, or even enable
   ;; it in unsafe build modes.
   :enable_segfault_handler true
   ;; This is the logging function used by `std.log`.
   :logFn myLogFn})
