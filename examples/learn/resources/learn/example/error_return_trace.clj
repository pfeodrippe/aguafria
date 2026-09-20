(ns learn.example.error-return-trace
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

;; Retain the original function names so the propagated-error trace is legible
;; beside the Zig version: FileNotFound is handled, PermissionDenied escapes.
(az/defn- bang1 [:error-union :void] []
  (ak/return (az/error-value :FileNotFound)))

(az/defn- bang2 [:error-union :void] []
  (ak/return (az/error-value :PermissionDenied)))

(az/defn- baz [:error-union :void] []
  (try (bang1)))

(az/defn- quux [:error-union :void] []
  (try (bang2)))

(az/defn- hello [:error-union :void] []
  (try (bang2)))

(az/defn- bar [:error-union :void] []
  (az/if-capture-stmt {:error [error]} (baz)
                      (try (quux))
                      (az/switch-stmt error
                        (case [(az/error-value :FileNotFound)] (try (hello))))))

(az/defn- foo [:error-union :void] [[value :i32]]
  (if (>= value 5)
    (try (bar))
    (try (bang2))))

(az/defn main [:error-union :void] []
  (try (foo 12)))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
