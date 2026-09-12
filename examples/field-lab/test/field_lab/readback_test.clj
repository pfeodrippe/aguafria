(ns field-lab.readback-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [aguafria-examples-native.readback :as readback]
            [aguafria-examples-native.bindings.glfw :as vk]
            [pitoco.frame :as frame])
  (:import [java.lang.foreign Arena]
           [java.nio.file Files]
           [javax.imageio ImageIO]))

;; These tests run in a separate test JVM, never in the live renderer's REPL.
(az/defn write-fixture!
  :- :bool
  [[path [:pointer {:size :c :const? true} :u8]] [bgra :bool]]
  (let [^:var pixels (az/array-init [:array 16 :u8]
                                   [0 0 255 255, 0 255 0 255,
                                    255 0 0 255, 255 255 255 255])]
    (when (ak/! (readback/request! path)) (ak/return false))
    (az/set-many! readback/width 2 readback/height 2
                  readback/mapped (ak/& (az/index pixels 0)))
    (let [saved (readback/write-frame!
                 (if bgra vk/VK_FORMAT_B8G8R8A8_UNORM vk/VK_FORMAT_R8G8B8A8_UNORM)
                 42 32 100)]
      (set! readback/mapped null)
      (readback/reject!)
      (set! _ (readback/acknowledge!))
      saved)))

(az/defn format-checks :- :bool []
  (and (ak/! (readback/supported-format? vk/VK_FORMAT_UNDEFINED))
       (readback/supported-format? vk/VK_FORMAT_B8G8R8A8_SRGB)
       (readback/supported-format? vk/VK_FORMAT_R8G8B8A8_SRGB)))

(deftest native-rgb-orientation-and-provenance
  (let [directory (.toFile (Files/createTempDirectory "pitoco-frame-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (doseq [bgra [true false]]
        (let [ppm (io/file directory (str bgra ".ppm"))
              png (io/file directory (str bgra ".png"))]
          (with-open [arena (Arena/ofConfined)]
            (is (write-fixture! (.allocateFrom arena (.getPath ppm)) bgra)))
          (let [captured (frame/read-frame ppm)
                colors (if bgra [0xff0000 0x00ff00 0x0000ff 0xffffff]
                                [0x0000ff 0x00ff00 0xff0000 0xffffff])
                metadata (frame/png! ppm png)
                image (ImageIO/read png)]
            (is (= {:frame 42 :revision 32 :tick 100 :width 2 :height 2}
                   (select-keys captured [:frame :revision :tick :width :height])))
            (is (= colors (vec (for [y (range 2) x (range 2)]
                                (bit-and 0xffffff (.getRGB image x y))))))
            (is (= 64 (count (:rgb-sha256 metadata))))
            (is (= 12 (alength ^bytes (:pixels captured)))))))
      (finally
        (doseq [file (reverse (file-seq directory))] (io/delete-file file))))))

(deftest bounded-mailbox-and-file-errors
  (with-open [arena (Arena/ofConfined)]
    (let [empty-path (.allocateFrom arena "")
          long-path (.allocateFrom arena (apply str (repeat 1024 "x")))
          first-path (.allocateFrom arena "/tmp/queued-frame.ppm")
          other-path (.allocateFrom arena "/tmp/other-frame.ppm")
          missing-directory (.allocateFrom arena "/nonexistent-pitoco-directory/frame.ppm")]
      (is (false? (readback/request! empty-path)))
      (is (false? (readback/request! long-path)))
      (is (readback/request! first-path))
      (is (= 2 (readback/status)))
      (is (false? (readback/request! other-path)))
      (is (false? (readback/acknowledge!)))
      (readback/reject!)
      (is (readback/acknowledge!))
      (is (= 0 (readback/status)))
      (is (false? (write-fixture! missing-directory true)))))
  (is (= 0 (readback/status)))
  (is (format-checks)))

(deftest malformed-frame-files-are-rejected
  (let [path (.toFile (Files/createTempFile "pitoco-bad-frame" ".ppm" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (doseq [contents ["P6\n"
                        "P6\n# missing provenance\n1 1\n255\nabc"
                        "P6\n# pitoco frame=0 revision=0 tick=0 vk_format=44\n1 1\n255\nab"
                        "P6\n# pitoco frame=0 revision=0 tick=0 vk_format=44\n1 1\n255\nabcd"
                        "P6\n# pitoco frame=0 revision=0 tick=0 vk_format=44\n999999999 1\n255\n"]]
        (spit path contents)
        (is (thrown? clojure.lang.ExceptionInfo (frame/read-frame path))))
      (finally (io/delete-file path)))))
