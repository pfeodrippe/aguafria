(ns racing-game.geometry
  "Blender-exported triangles embedded into Zig; no asset runtime or JVM required."
  (:require [aguafria.zig :as az]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn mesh-revision
  "Content fingerprint for invalidating immutable GPU uploads after asset edits."
  [vertices]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str vertices) java.nio.charset.StandardCharsets/UTF_8))]
    (bigint (java.math.BigInteger. 1 (java.util.Arrays/copyOf digest 8)))))

(let [data (edn/read-string (slurp (io/resource "geometry/racing.edn")))
      axles (edn/read-string (slurp (io/resource "geometry/wheel-axes.edn")))]
  (doseq [[name vertices] data]
    (when-not (and (seq vertices) (zero? (mod (count vertices) 3))
                   (every? #(and (= 10 (count %)) (every? number? %)) vertices))
      (throw (ex-info "Invalid Blender triangle export" {:mesh name})))
    (eval `(az/defconst ~(symbol (str (clojure.core/name name) "-vertices"))
             [:array ~(count vertices) [:array 10 :f32]] ~vertices))
    (eval `(az/defconst ~(symbol (str (clojure.core/name name) "-revision"))
             :u64 ~(mesh-revision vertices)))
    (when (.startsWith (clojure.core/name name) "wheel-")
      ;; An asymmetric hub must not move the axle. Blender exports the actual
      ;; authored pivot; bounds are insufficient for independently rotating parts.
      (let [{:keys [center radius]} (get axles name)]
        (when-not (and (= 3 (count center)) (every? number? center)
                       (number? radius) (pos? radius))
          (throw (ex-info "Missing Blender wheel axle" {:mesh name})))
        (eval `(az/defconst ~(symbol (str (clojure.core/name name) "-axle"))
                 [:array 4 :f32] ~(conj center radius)))))))
