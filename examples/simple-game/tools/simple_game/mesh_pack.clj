(ns simple-game.mesh-pack
  "Pack Kenney OBJ geometry and palette textures into a tiny native mesh format."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import (java.awt.image BufferedImage)
           (java.io BufferedOutputStream FileOutputStream)
           (java.nio ByteBuffer ByteOrder)
           (javax.imageio ImageIO)))

(def default-bundle-root
  "/Users/pfeodrippe/Downloads/Kenney Game Assets All-in-1 3.6.0/3D assets")

(def models
  [{:id :recife/house-a :pack "City Kit - Commercial" :model "building-c"}
   {:id :recife/house-b :pack "City Kit - Commercial" :model "building-e"}
   {:id :recife/house-c :pack "City Kit - Commercial" :model "building-k"}
   {:id :recife/palm :pack "Pirate Kit" :model "palm-detailed-straight"}
   {:id :recife/lamp :pack "City Kit - Roads" :model "light-square"}
   {:id :recife/bench :pack "Coaster Kit" :model "bench"}
   {:id :recife/plaza :pack "City Kit - Roads" :model "tile-low"}
   {:id :recife/road :pack "City Kit - Roads" :model "road-straight"}
   {:id :cart/frame :pack "Fantasy Town Kit" :model "cart-high"}
   {:id :cart/parasol :pack "City Kit - Commercial" :model "detail-parasol-a"}
   {:id :cart/coconut :pack "Food Kit" :model "coconut"}
   {:id :cart/display :pack "Mini Market" :model "display-fruit"}
   {:id :people/customer-a :pack "Mini Characters" :model "character-female-a"}
   {:id :people/customer-b :pack "Mini Characters" :model "character-male-b"}
   {:id :factory/conveyor :pack "Factory Kit" :model "conveyor"}
   {:id :factory/machine :pack "Factory Kit" :model "machine"}])

(defn- parse-number [s]
  (Double/parseDouble s))

(defn- obj-index [size raw]
  (let [value (Long/parseLong raw)]
    (if (neg? value) (+ size value) (dec value))))

(defn- clamp [value low high]
  (max low (min high value)))

(defn- sample-color [^BufferedImage image [u v]]
  (if image
    (let [x (int (Math/round (* (clamp u 0.0 1.0) (dec (.getWidth image)))))
          y (int (Math/round (* (- 1.0 (clamp v 0.0 1.0))
                                (dec (.getHeight image)))))
          argb (.getRGB image x y)]
      [(/ (bit-and (bit-shift-right argb 16) 0xff) 255.0)
       (/ (bit-and (bit-shift-right argb 8) 0xff) 255.0)
       (/ (bit-and argb 0xff) 255.0)])
    [0.8 0.8 0.8]))

(defn- bundle-root [argument]
  (let [path (or argument
                 (System/getProperty "simple-game.kenney-root")
                 (System/getenv "KENNEY_ASSETS_ROOT")
                 default-bundle-root)
        directory (io/file path)]
    (when-not (.isDirectory directory)
      (throw
       (ex-info
        "Kenney 3D asset root does not exist; pass it as the first argument or set KENNEY_ASSETS_ROOT."
        {:path (.getAbsolutePath directory)})))
    (.getAbsolutePath directory)))

(defn- texture-file [root pack]
  (let [directory (io/file root pack "Models" "OBJ format" "Textures")]
    (first (filter #(str/ends-with? (.getName %) ".png")
                   (or (seq (.listFiles directory)) [])))))

(defn- model-file [root {:keys [pack model]}]
  (io/file root pack "Models" "OBJ format" (str model ".obj")))

(defn- parse-ref [positions texcoords normals token]
  (let [[position texture normal] (str/split token #"/" -1)]
    {:position (nth positions (obj-index (count positions) position))
     :texture (when-not (str/blank? texture)
                (nth texcoords (obj-index (count texcoords) texture)))
     :normal (when-not (str/blank? normal)
               (nth normals (obj-index (count normals) normal)))}))

(defn- parse-obj [file image]
  (let [state
        (reduce
         (fn [{:keys [positions texcoords normals] :as state} line]
           (let [[kind & values] (str/split (str/trim line) #"\s+")]
             (case kind
               "v" (update state :positions conj (mapv parse-number (take 3 values)))
               "vt" (update state :texcoords conj (mapv parse-number (take 2 values)))
               "vn" (update state :normals conj (mapv parse-number (take 3 values)))
               "f" (let [references (mapv #(parse-ref positions texcoords normals %)
                                            values)
                         triangles (for [index (range 1 (dec (count references)))]
                                     [(first references)
                                      (nth references index)
                                      (nth references (inc index))])]
                     (update state :triangles into triangles))
               state)))
         {:positions [] :texcoords [] :normals [] :triangles []}
         (str/split-lines (slurp file)))
        sorted-triangles
        (sort-by (fn [triangle]
                   (/ (reduce + (mapcat :position triangle)) 3.0))
                 (:triangles state))
        vertices
        (mapcat
         (fn [triangle]
           (map (fn [{:keys [position texture normal]}]
                  (into [] (concat position
                                   (or normal [0.0 1.0 0.0])
                                   (sample-color image texture))))
                triangle))
         sorted-triangles)]
    (vec vertices)))

(defn- little-float-bytes [value]
  (-> (ByteBuffer/allocate 4)
      (.order ByteOrder/LITTLE_ENDIAN)
      (.putFloat (float value))
      (.array)))

(defn- little-int-bytes [value]
  (-> (ByteBuffer/allocate 4)
      (.order ByteOrder/LITTLE_ENDIAN)
      (.putInt (int value))
      (.array)))

(defn- bounds [vertices]
  (reduce
   (fn [{:keys [min max]} [x y z]]
     {:min (mapv clojure.core/min min [x y z])
      :max (mapv clojure.core/max max [x y z])})
   {:min [Double/POSITIVE_INFINITY Double/POSITIVE_INFINITY Double/POSITIVE_INFINITY]
    :max [Double/NEGATIVE_INFINITY Double/NEGATIVE_INFINITY Double/NEGATIVE_INFINITY]}
   (map #(subvec % 0 3) vertices)))

(defn- pack-model! [root output-root descriptor]
  (let [file (model-file root descriptor)
        texture (texture-file root (:pack descriptor))
        image (when texture (ImageIO/read texture))
        vertices (parse-obj file image)
        filename (str (str/replace (subs (str (:id descriptor)) 1)
                                   #"[^a-zA-Z0-9_-]" "-")
                      ".agmesh")
        output (io/file output-root filename)]
    (.mkdirs (.getParentFile output))
    (with-open [stream (BufferedOutputStream. (FileOutputStream. output))]
      (.write stream (.getBytes "AGM1" "US-ASCII"))
      (.write stream (little-int-bytes (count vertices)))
      (doseq [vertex vertices
              component vertex]
        (.write stream (little-float-bytes component))))
    (merge descriptor
           {:resource (str "kenney/packed/" filename)
            :vertices (count vertices)
            :triangles (quot (count vertices) 3)
            :bounds (bounds vertices)})))

(defn -main [& [root-argument]]
  (let [root (bundle-root root-argument)
        resource-root (io/file "resources" "kenney")
        output-root (io/file resource-root "packed")
        packed (mapv #(pack-model! root output-root %) models)
        manifest {:format :aguafria-mesh-v1
                  :vertex-layout [:position-xyz :normal-xyz :color-rgb]
                  :models packed}]
    (spit (io/file resource-root "meshes.edn")
          (with-out-str (pprint/pprint manifest)))
    (doseq [{:keys [id vertices triangles resource]} packed]
      (println id vertices "vertices" triangles "triangles" resource))))
