(ns learn.example.error-return-trace
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- bang1 [:error-union :void] []
  (az/error-value :FileNotFound))

(az/defn- bang2 [:error-union :void] []
  (az/error-value :PermissionDenied))

(az/defn- baz [:error-union :void] []
  (try (bang1)))

(az/defn- quux [:error-union :void] []
  (try (bang2)))

(az/defn- hello [:error-union :void] []
  (try (bang2)))

(az/defn- bar [:error-union :void] []
  (az/if-capture-stmt {:error [err]} (baz)
                      (try (quux))
                      (az/switch-stmt err
                                      (case [(az/error-value :FileNotFound)] (try (hello))))))

(az/defn- foo [:error-union :void] [[x :i32]]
  (if (k/>= x 5)
    (try (bar))
    (try (bang2))))

(az/defn main [:error-union :void] []
  (try (foo 12)))

(comment
  (main))
