(ns la-professeure.build
  "Embedded Zig builds, explicit Vulkan shaders, assets and packaging."
  (:refer-clojure :exclude [run!])
  (:require [aguafria.c :as ac] [aguafria.zig :as az]
            [aguafria.zig.build :as zig-build]
            [aguafria-examples-native.build :as native]
            [la-professeure.dialogue :as dialogue]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [java.awt Color RenderingHints Font]
           [java.awt.image BufferedImage]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files StandardCopyOption]
           [javax.imageio ImageIO]))

(defn root []
  (or (some #(when (.isFile (io/file % "src/la_professeure/build.clj")) %)
            (take-while some? (iterate #(.getParentFile %) (.getCanonicalFile (io/file ".")))))
      (throw (ex-info "Run from examples/la-professeure" {}))))

(defn run! [command]
  (let [p (-> (ProcessBuilder. ^java.util.List (mapv str command))
               (.directory (root)) (.redirectErrorStream true) .start)
        output (slurp (.getInputStream p)) code (.waitFor p)]
    (when-not (zero? code)
      (throw (ex-info "La Professeure build failed" {:command command :exit code :output output})))
    output))

(defn story-source
  "The explicit Markdown input shared by dev and standalone. No sample fallback."
  []
  (let [path (or (System/getProperty "la-professeure.dialogue")
                 (:source (edn/read-string (slurp (io/file (root) "dialogue.edn")))))]
    (when-not (and (string? path) (not (.isBlank ^String path)))
      (throw (ex-info "Set :source in dialogue.edn to a readable Markdown file" {:source path})))
    (let [file (.getCanonicalFile (if (.isAbsolute (io/file path)) (io/file path) (io/file (root) path)))]
      (when-not (and (.isFile file) (.canRead file))
        (throw (ex-info "Cannot read dialogue Markdown; check :source in dialogue.edn" {:source (str file)})))
      file)))

(defn compile-story! []
  (let [source (story-source)]
    (dialogue/compile! source
                      (io/file (root) "build/dialogue" (subs (dialogue/digest (str source)) 0 16))
                      (io/file (root) "resources/demo/story.lpdialogue"))))

(def miniaudio-revision "9634bedb5b5a2ca38c1ee7108a9358a4e233f14d")

(defn native-path [mode]
  (io/file (root) "build/native" (if (= mode :static) "libminiaudio.a" "libminiaudio.dylib")))

(def audio-frameworks
  ["-framework" "CoreAudio" "-framework" "AudioToolbox" "-framework" "CoreFoundation" "-lc"])

(defn native! [mode]
  (let [vendor (io/file (root) "build/vendor/miniaudio") output (native-path mode)]
    (when-not (.isDirectory (io/file vendor ".git"))
      (io/make-parents (io/file vendor ".keep"))
      (run! ["git" "clone" "--filter=blob:none" "https://github.com/mackron/miniaudio.git" vendor])
      (run! ["git" "-C" vendor "checkout" "--detach" miniaudio-revision]))
    (when-not (= miniaudio-revision (.trim (run! ["git" "-C" vendor "rev-parse" "HEAD"])))
      (throw (ex-info "Unexpected miniaudio checkout" {:path (str vendor)})))
    (when-not (and (.isFile output) (pos? (.length output)))
      (io/make-parents output)
      (run! (concat [(az/zig-executable) "build-lib" (if (= mode :static) "-static" "-dynamic")
                     "-OReleaseFast" "-fPIC" (str "-I" vendor) (str "-femit-bin=" output)
                     (io/file vendor "miniaudio.c")] audio-frameworks)))
    output))

(def audio-api
  '[ma_engine ma_sound ma_decoder ma_data_source MA_SUCCESS
    ma_engine_init ma_engine_uninit ma_sound_init_from_data_source
    ma_sound_set_looping ma_sound_set_volume ma_sound_start ma_sound_uninit ma_sound_at_end
    ma_sound_get_cursor_in_pcm_frames ma_sound_seek_to_pcm_frame
    ma_sound_stop ma_sound_is_playing
    ma_decoder_init_file ma_decoder_uninit])

(defn bindings! []
  ;; Keep the C implementation and layout authoritative in Zig's C importer.
  ;; These ordinary Vars expose the native API used by this stage without
  ;; expanding thousands of recursive C-type declarations into JVM metadata.
  (let [output (io/file (root) "generated/la_professeure/miniaudio.clj")
        source (str "(ns la-professeure.miniaudio\n"
                    "  (:require [aguafria.std] [aguafria.keyword :as ak] [aguafria.zig :as az]))\n\n"
                    "(az/defconst c-api (ak/cImport (ak/cInclude \"miniaudio.h\")))\n\n"
                    (apply str (for [name audio-api]
                                 (str "(az/defconst " name " {:attrs #{:public}} (az/field c-api " name "))\n\n"))))]
    (io/make-parents output)
    (when (not= source (when (.isFile output) (slurp output))) (spit output source))
    output))

(defn fixtures! []
  ;; Deliberately simple labelled demo assets, not production illustrations.
  (let [png (io/file (root) "resources/demo/animation.png")
        wav (io/file (root) "resources/demo/lesson.wav")]
    (io/make-parents png)
    (when-not (.isFile png)
      (let [image (BufferedImage. (* 8 96) 96 BufferedImage/TYPE_INT_ARGB)
            g (.createGraphics image)]
        (try
          (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
          (doseq [frame (range 8) drop (range 9)
                  :let [x (+ (* frame 96) (mod (* drop 37) 90))
                        y (mod (+ (* drop 19) (* frame 12)) 90)]]
            (.setColor g (Color. 182 205 200 75))
            (.drawLine g x y (- x 2) (+ y 6)))
          (ImageIO/write image "png" png)
          (finally (.dispose g)))))
    (when-not (.isFile wav)
      (let [rate 22050 samples (* rate 2) bytes (* samples 2)
            b (doto (ByteBuffer/allocate (+ 44 bytes)) (.order ByteOrder/LITTLE_ENDIAN))]
        (.put b (.getBytes "RIFF" "US-ASCII")) (.putInt b (+ 36 bytes))
        (.put b (.getBytes "WAVEfmt " "US-ASCII")) (.putInt b 16)
        (.putShort b (short 1)) (.putShort b (short 1)) (.putInt b rate) (.putInt b (* 2 rate))
        (.putShort b (short 2)) (.putShort b (short 16))
        (.put b (.getBytes "data" "US-ASCII")) (.putInt b bytes)
        (dotimes [i samples]
          (let [t (/ i (double rate)) envelope (* 0.10 (Math/pow (Math/sin (* Math/PI (/ t 2))) 2))]
            (.putShort b (short (* 32767 envelope (+ (* 0.6 (Math/sin (* 2 Math/PI 220 t)))
                                                     (* 0.4 (Math/sin (* 2 Math/PI 330 t)))))))))
        (with-open [out (io/output-stream wav)] (.write out (.array b)))))
    {:sprite (str png) :audio (str wav)}))


(defn animation-file []
  (io/file (or (System/getProperty "la-professeure.animation")
               (str (io/file (root) "resources/demo/animation.png")))))

(def french-glyphs
  "Use otherwise blank control-code atlas cells for French typography."
  {128 \œ 129 \Œ 130 \‘ 131 \’ 132 \“ 133 \” 134 \– 135 \— 136 \… 137 \ })

(defn atlas!
  "Pack a licensed serif font, measured advances, backdrop and reloadable animation."
  []
  (let [image (BufferedImage. 2048 1536 BufferedImage/TYPE_INT_ARGB)
        g (.createGraphics image) output (io/file (root) "resources/demo/atlas.rgba")
        font (.deriveFont (Font/createFont Font/TRUETYPE_FONT
                           (io/file (root) "resources/fonts/LibreBaskerville.ttf")) (float 44))
        advances (doto (ByteBuffer/allocate (* 256 4)) (.order ByteOrder/LITTLE_ENDIAN))]
    (try
      (.setRenderingHint g RenderingHints/KEY_TEXT_ANTIALIASING RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
      (.setFont g font)
      (.setColor g Color/WHITE)
      (doseq [code (range 256)]
        (let [glyph (str (get french-glyphs code (char code)))
              x (* (mod code 32) 64) y (* (quot code 32) 80)]
          (.putFloat advances (float (.getWidth (.getStringBounds font glyph (.getFontRenderContext g)))))
          (when (or (<= 32 code 126) (<= 160 code 255) (contains? french-glyphs code))
            ;; Guard each cell from oversized accent/italic outlines and retain
            ;; transparent gutters for the shader's bilinear footprint.
            (.setClip g (int (+ x 2)) (int (+ y 2)) 60 76)
            (.drawString g glyph (int (+ 4 (* (mod code 32) 64)))
                         (int (+ (* (quot code 32) 80) 56))))))
      (.setClip g nil)
      (.setRenderingHint g RenderingHints/KEY_INTERPOLATION RenderingHints/VALUE_INTERPOLATION_BICUBIC)
      (.drawImage g (ImageIO/read (io/file (root) "resources/art/corridor.png")) 0 640 1024 896 nil)
      (let [animation (ImageIO/read (animation-file))]
        (when-not (and animation (= 768 (.getWidth animation)) (= 96 (.getHeight animation)))
          (throw (ex-info "Animation export must be an 8-frame 768x96 PNG spritesheet"
                          {:path (str (animation-file))})))
        (.drawImage g animation 1024 640 nil))
      (with-open [out (io/output-stream (io/file (root) "resources/demo/glyph-advances.candidate.bin"))]
        (.write out (.array advances)))
      (let [b (byte-array (* 2048 1536 4))]
        (dotimes [i (* 2048 1536)]
          (let [argb (.getRGB image (mod i 2048) (quot i 2048))]
            (doseq [[offset shift] [[0 16] [1 8] [2 0] [3 24]]]
              (aset-byte b (+ (* i 4) offset) (unchecked-byte (bit-and 255 (bit-shift-right argb shift)))))))
        (with-open [out (io/output-stream (io/file (str output ".candidate")))] (.write out b)))
      (doseq [[source target] [[(io/file (str output ".candidate")) output]
                               [(io/file (root) "resources/demo/glyph-advances.candidate.bin")
                                (io/file (root) "resources/demo/glyph-advances.bin")]]]
        (Files/move (.toPath source) (.toPath target)
                    (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING])))
      (finally (.dispose g)))
    output))

(defn shaders! []
  ;; Compile both stages before replacing either published binary.
  (let [pairs (mapv (fn [name]
                      (let [source (io/file (root) "resources/shaders" name)]
                        [source (io/file (str source ".candidate.spv")) (io/file (str source ".spv"))]))
                    ["mesh.vert" "mesh.frag"])]
    (when (some (fn [[source _ target]]
                  (or (not (.isFile target)) (< (.lastModified target) (.lastModified source)))) pairs)
      (doseq [[source candidate _] pairs]
        (run! ["glslc" "--target-env=vulkan1.2" source "-o" candidate]))
      (doseq [[_ candidate target] pairs]
        (Files/move (.toPath candidate) (.toPath target)
                    (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING])))))
  :prepared)

(defn prepare! []
  (compile-story!)
  (native/prepare-shared!) (native! :shared) (bindings!) (fixtures!) (atlas!) (shaders!)
  :prepared)

(defonce native-loaded (atom false))

(defn load-native! []
  (when-not @native-loaded
    (prepare!)
    (ac/load-bindings! (bindings!))
    (az/configure! {:module-zig-args
                    (assoc (:module-zig-args (az/configuration)) "la-professeure.miniaudio"
                           [(str "-I" (io/file (root) "build/vendor/miniaudio"))])
                   :zig-args (into (vec (:zig-args (az/configuration)))
                                   (concat [(str (native-path :shared))
                                            (str "-I" (io/file (root) "build/vendor/miniaudio"))] audio-frameworks))})
    (reset! native-loaded true)))

(defn standalone! []
  (prepare!) (native! :static) (native/prepare-static!)
  (zig-build/load-source-only! 'la-professeure.scene)
  (binding [*ns* (the-ns 'la-professeure.scene)]
    (eval '(aguafria.zig/defconst live-voices? false)))
  (let [output (io/file (root) "build/standalone/la-professeure")]
    (io/make-parents output)
    (doseq [[name source] {"miniaudio" (io/file (root) "build/vendor/miniaudio/LICENSE")
                           "glfw" (io/file (:glfw-root (native/paths)) "LICENSE.md")
                           "flecs" (io/file (:flecs-root (native/paths)) "LICENSE")
                           "vulkan-headers" (io/file (:vulkan-root (native/paths)) "LICENSE.md")}]
      (let [target (io/file (root) "build/standalone/licenses" (str name ".txt"))]
        (io/make-parents target)
        (Files/copy (.toPath source) (.toPath target)
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
    (doseq [file (filter #(.isFile %) (file-seq (io/file (root) "resources")))]
      (let [relative (.relativize (.toPath (io/file (root) "resources")) (.toPath file))
            target (io/file (root) "build/standalone/resources" (str relative))]
        (io/make-parents target)
        (Files/copy (.toPath file) (.toPath target)
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
    (select-keys (az/build! 'la-professeure.scene
                   {:kind :exe :name "la-professeure" :output output :optimize "ReleaseFast"
                    :reloadable? false :async? false
                    :module-zig-args {"la-professeure.miniaudio"
                                      [(str "-I" (io/file (root) "build/vendor/miniaudio"))]}
                    :zig-args (vec (concat (native/standalone-link-arguments)
                                          [(str (native-path :static))
                                           (str "-I" (io/file (root) "build/vendor/miniaudio"))] audio-frameworks))})
                 [:output-path :duration-ms :optimize])))

(defn tool! []
  (let [output (io/file (root) "build/tools/dialogue-tool")]
    (io/make-parents output)
    (select-keys (az/build! 'la-professeure.recording-tool
                            {:kind :exe :name "dialogue-tool" :output output
                             :optimize "ReleaseFast" :reloadable? false :async? false
                             :zig-args ["-lc"]}) [:output-path :duration-ms])))

(defn -main [& [command]]
  (prn (case command "standalone" (standalone!) "tool" (tool!) (prepare!)))
  (shutdown-agents))
