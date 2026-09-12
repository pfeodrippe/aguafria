(ns pitoco.frame
  "Read Pitoco's tagged Vulkan PPM files and encode lossless PNG previews."
  (:require [clojure.java.io :as io])
  (:import [java.awt.image BufferedImage]
           [javax.imageio ImageIO]
           [java.security MessageDigest]))

(defn- header-line [stream]
  (loop [chars []]
    (let [value (.read stream)]
      (cond
        (= value -1) (throw (ex-info "Truncated frame header" {}))
        (= value 10) (apply str chars)
        (>= (count chars) 256) (throw (ex-info "Oversized frame header" {}))
        :else (recur (conj chars (char value)))))))

(defn read-frame
  "Read the native writer's exact format; reject truncation, trailing bytes and oversized images."
  [path]
  (with-open [stream (io/input-stream path)]
    (let [magic (header-line stream)
          tag (header-line stream)
          dimensions (header-line stream)
          maximum (header-line stream)
          [_ frame revision tick format]
          (re-matches #"# pitoco frame=(\d+) revision=(\d+) tick=(\d+) vk_format=(\d+)" tag)
          [_ width height] (re-matches #"(\d+) (\d+)" dimensions)]
      (when-not (and (= "P6" magic) (= "255" maximum) frame width)
        (throw (ex-info "Not a tagged Pitoco RGB frame" {:path (str path)})))
      (let [[frame revision tick format width height]
            (mapv parse-long [frame revision tick format width height])]
        (when-not (and (every? some? [frame revision tick format width height])
                       (pos? width) (pos? height) (<= width 16777216)
                       (<= height (quot 16777216 width)))
          (throw (ex-info "Invalid or oversized frame dimensions/tag" {})))
        (let [size (* width height 3)
              pixels (.readNBytes stream (int size))]
          (when-not (and (= size (alength pixels)) (= -1 (.read stream)))
            (throw (ex-info "Frame payload length does not match its dimensions" {})))
          {:frame frame :revision revision :tick tick :vk-format format
           :width width :height height :pixels pixels})))))

(defn png!
  "Encode exactly the captured RGB bytes; keep frame provenance in an EDN sidecar."
  [source destination]
  (let [{:keys [width height pixels] :as capture} (read-frame source)
        image (BufferedImage. (int width) (int height) BufferedImage/TYPE_INT_RGB)
        row (int-array width)
        target (io/file destination)
        digest (.digest (MessageDigest/getInstance "SHA-256") pixels)
        metadata (assoc (dissoc capture :pixels)
                        :source (.getCanonicalPath (io/file source))
                        :rgb-sha256 (apply str (map #(format "%02x" (bit-and 255 %)) digest)))]
    (dotimes [y height]
      (dotimes [x width]
        (let [offset (* 3 (+ x (* y width)))
              red (bit-and 255 (aget ^bytes pixels offset))
              green (bit-and 255 (aget ^bytes pixels (inc offset)))
              blue (bit-and 255 (aget ^bytes pixels (+ offset 2)))]
          (aset-int row x (bit-or (bit-shift-left red 16) (bit-shift-left green 8) blue))))
      (.setRGB image 0 y width 1 row 0 width))
    (io/make-parents target)
    (when-not (ImageIO/write image "png" target)
      (throw (ex-info "PNG encoder unavailable" {})))
    (spit (str target ".edn") (str (pr-str metadata) "\n"))
    metadata))
