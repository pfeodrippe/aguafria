(ns learn.example.error-return-trace
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- bang1 [:error-union :void] []
  (a/error-value :FileNotFound))

(a/defn- bang2 [:error-union :void] []
  (a/error-value :PermissionDenied))

(a/defn- baz [:error-union :void] []
  (k/try (bang1)))

(a/defn- quux [:error-union :void] []
  (k/try (bang2)))

(a/defn- hello [:error-union :void] []
  (k/try (bang2)))

(a/defn- bar [:error-union :void] []
  (a/if-capture-stmt {:error [err]} (baz)
                     (k/try (quux))
                     (k/switch err
                       (a/error-value :FileNotFound) (k/try (hello)))))

(a/defn- foo [:error-union :void] [[x :i32]]
  (if (k/>= x 5)
    (k/try (bar))
    (k/try (bang2))))

(a/defn main [:error-union :void] []
  (k/try (foo 12)))

(comment
  (main))
