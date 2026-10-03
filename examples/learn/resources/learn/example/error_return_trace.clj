(ns learn.example.error-return-trace
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- bang1 [:error-union :void] []
  (a/error-value :FileNotFound))

(a/defn- bang2 [:error-union :void] []
  (a/error-value :PermissionDenied))

(a/defn- baz [:error-union :void] []
  (try (bang1)))

(a/defn- quux [:error-union :void] []
  (try (bang2)))

(a/defn- hello [:error-union :void] []
  (try (bang2)))

(a/defn- bar [:error-union :void] []
  (a/if-capture-stmt {:error [err]} (baz)
                     (try (quux))
                     (a/switch-stmt err
                                    (case [(a/error-value :FileNotFound)] (try (hello))))))

(a/defn- foo [:error-union :void] [[x :i32]]
  (if (k/>= x 5)
    (try (bar))
    (try (bang2))))

(a/defn main [:error-union :void] []
  (try (foo 12)))

(comment
  (main))
