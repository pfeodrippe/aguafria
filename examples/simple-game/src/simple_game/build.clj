(ns simple-game.build
  "Zig-only dependency and standalone preparation for simple-game."
  (:require [aguafria.zig :as az]
            [aguafria.zig.build :as zig-build]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.awt AlphaComposite RenderingHints]
           [java.awt.image BufferedImage]
           [java.io BufferedOutputStream DataOutputStream]
           [javax.imageio ImageIO]))

(defn project-root
  []
  (or
   (some (fn [^java.io.File directory]
           (when (and (.isFile (io/file directory "deps.edn"))
                      (.isFile (io/file directory "vendor-lock.edn"))
                      (.isDirectory (io/file directory "vendor/flecs")))
             (.getCanonicalFile directory)))
         (take-while some?
                     (iterate #(.getParentFile ^java.io.File %)
                              (.getCanonicalFile
                               (io/file (System/getProperty "user.dir"))))))
   (throw (ex-info "Could not find the simple-game project root"
                   {:user-dir (System/getProperty "user.dir")}))))

(defn paths
  []
  (let [root (project-root)]
    {:root root
     :flecs-source (io/file root "vendor/flecs/distr/flecs.c")
     :flecs-include (io/file root "vendor/flecs/distr")
     :flecs-static-library (io/file root "build/native/libflecs.a")
     :flecs-web-object (io/file root "build/web/flecs-game.o")
     :flecs-shared-library (io/file root "build/native"
                                    (System/mapLibraryName "flecs"))
     :box3d-source-root (io/file root "vendor/box3d/src")
     :box3d-include (io/file root "vendor/box3d/include")
     :box3d-static-library (io/file root "build/native/libbox3d.a")
     :box3d-web-library (io/file root "build/web/libbox3d.a")
     :box3d-shared-library (io/file root "build/native"
                                    (System/mapLibraryName "box3d"))
     :miniaudio-source (io/file root "vendor/miniaudio/miniaudio.c")
     :miniaudio-include (io/file root "vendor/miniaudio")
     :miniaudio-static-library (io/file root "build/native/libminiaudio.a")
     :miniaudio-web-object (io/file root "build/web/miniaudio.o")
     :miniaudio-shared-library (io/file root "build/native"
                                        (System/mapLibraryName "miniaudio"))
     :stb-truetype-header (io/file root "vendor/box3d/samples/stb_truetype.h")
     :stb-truetype-native-source (io/file root "build/native/stb_truetype.c")
     :stb-truetype-static-library
     (io/file root "build/native/libstb_truetype.a")
     :stb-truetype-web-source (io/file root "build/web/stb_truetype.c")
     :stb-truetype-web-library (io/file root "build/web/libstb_truetype.a")
     :font-assets-root (io/file root "build/assets/fonts")
     :sprite-source (io/file root "resources/sprites/water-orb-sheet.png")
     :sprite-assets-root (io/file root "build/assets/sprites")
     :glfw-include (io/file root "vendor/glfw/include")
     :glfw-source-root (io/file root "vendor/glfw/src")
     :glfw-static-library (io/file root "build/native/libglfw.a")
     :glfw-shared-library (io/file root "build/native"
                                   (System/mapLibraryName "glfw"))
     :web-build-root (io/file root "build/web")}))

(declare run-command!)

(defn emscripten-root
  "Resolve the Emscripten SDK used only for web headers and final packaging."
  []
  (let [configured (or (System/getenv "EMSCRIPTEN")
                       (System/getenv "EMSDK"))
        configured (when configured (io/file configured))
        command-result (when-not (and configured (.isDirectory configured))
                         (run-command! ["em-config" "EMSCRIPTEN_ROOT"]
                                       (:root (paths))))
        resolved (if (and configured (.isDirectory configured))
                   configured
                   (io/file (str/trim (:output command-result))))]
    (when-not (.isDirectory resolved)
      (throw (ex-info
              "Could not find Emscripten; set EMSCRIPTEN or put em-config in PATH"
              {:resolved (.getAbsolutePath resolved)})))
    (.getCanonicalFile resolved)))

(defn emscripten-sysroot
  []
  (let [root (emscripten-root)
        sysroot (io/file root "cache/sysroot")]
    (when-not (.isDirectory sysroot)
      (throw (ex-info "Emscripten sysroot is missing"
                      {:root (.getAbsolutePath root)
                       :sysroot (.getAbsolutePath sysroot)})))
    (.getCanonicalFile sysroot)))

(defn- run-command!
  [command directory]
  (let [builder (doto (ProcessBuilder. ^java.util.List command)
                  (.directory directory)
                  (.redirectErrorStream true))
        process (.start builder)
        output (slurp (.getInputStream process))
        exit (.waitFor process)
        result {:command (vec command)
                :directory (.getAbsolutePath ^java.io.File directory)
                :exit exit
                :output output}]
    (when-not (zero? exit)
      (throw (ex-info
              (str "error[simple-game::zig-build]: Zig command failed\n"
                   "  = command: " (str/join " " command) "\n\n" output)
              result)))
    result))

(defn- prepare-flecs-library!
  [mode output]
  (let [{:keys [root flecs-source flecs-include]} (paths)
        current? (and (.isFile output)
                      (>= (.lastModified output)
                          (.lastModified flecs-source)))]
    (if current?
      {:status :cached
       :mode mode
       :output-path (.getAbsolutePath output)}
      (do
        (io/make-parents output)
        (let [result
              (run-command!
               ["zig" "build-lib"
                (case mode :shared "-dynamic" :static "-static")
                "-OReleaseFast" "-fPIC"
                (str "-femit-bin=" (.getAbsolutePath output))
                (str "-I" (.getAbsolutePath flecs-include))
                (.getAbsolutePath flecs-source)
                "-lc"]
               root)]
          {:status :built
           :mode mode
           :output-path (.getAbsolutePath output)
           :command (:command result)})))))

(defn prepare-flecs!
  "Compile the unmodified vendored Flecs C amalgamation with Zig.
  Development uses one shared library so every hot-reload generation observes
  the same Flecs process globals. Standalone links retain a static archive."
  []
  (let [{:keys [flecs-static-library flecs-shared-library]} (paths)]
    {:shared (prepare-flecs-library! :shared flecs-shared-library)
     :static (prepare-flecs-library! :static flecs-static-library)}))

(defn- box3d-sources
  []
  (->> (.listFiles ^java.io.File (:box3d-source-root (paths)))
       (filter #(and (.isFile ^java.io.File %)
                     (str/ends-with? (.getName ^java.io.File %) ".c")))
       (sort-by #(.getName ^java.io.File %))
       vec))

(defn- prepare-box3d-library!
  [mode output]
  (let [{:keys [root box3d-source-root box3d-include]} (paths)
        sources (box3d-sources)
        newest-input (reduce max (.lastModified (io/file box3d-include
                                                         "box3d/box3d.h"))
                             (map #(.lastModified ^java.io.File %) sources))
        current? (and (.isFile output)
                      (>= (.lastModified output) newest-input))]
    (if current?
      {:status :cached :mode mode :output-path (.getAbsolutePath output)}
      (do
        (io/make-parents output)
        (let [command
              (vec
               (concat
                ["zig" "build-lib"
                 (case mode :shared "-dynamic" :static "-static")
                 "-OReleaseFast" "-fPIC"
                 (str "-I" (.getAbsolutePath box3d-include))
                 (str "-I" (.getAbsolutePath box3d-source-root))
                 (str "-femit-bin=" (.getAbsolutePath output))]
                (when (= :shared mode) ["-Dbox3d_EXPORTS"])
                ["-cflags" "-std=c17" "--"]
                (map #(.getAbsolutePath ^java.io.File %) sources)
                ["-lc"]))
              result (run-command! command root)]
          {:status :built
           :mode mode
           :output-path (.getAbsolutePath output)
           :command (:command result)})))))

(defn prepare-box3d!
  "Compile unmodified Box3D C sources with Zig for dev and standalone use."
  []
  (let [{:keys [box3d-static-library box3d-shared-library]} (paths)]
    {:shared (prepare-box3d-library! :shared box3d-shared-library)
     :static (prepare-box3d-library! :static box3d-static-library)}))

(defn- prepare-miniaudio-library!
  [mode output]
  (let [{:keys [root miniaudio-source miniaudio-include]} (paths)
        newest-input (max (.lastModified miniaudio-source)
                          (.lastModified (io/file miniaudio-include
                                                  "miniaudio.h")))
        current? (and (.isFile output)
                      (>= (.lastModified output) newest-input))]
    (if current?
      {:status :cached :mode mode :output-path (.getAbsolutePath output)}
      (do
        (io/make-parents output)
        (let [command
              (vec
               (concat
                ["zig" "build-lib"
                 (case mode :shared "-dynamic" :static "-static")
                 "-OReleaseFast" "-fPIC"
                 (str "-I" (.getAbsolutePath miniaudio-include))
                 (str "-femit-bin=" (.getAbsolutePath output))
                 (.getAbsolutePath miniaudio-source)
                 "-lc"]
                (when (= "Mac OS X" (System/getProperty "os.name"))
                  ["-framework" "CoreAudio"
                   "-framework" "AudioToolbox"
                   "-framework" "CoreFoundation"])))
              result (run-command! command root)]
          {:status :built
           :mode mode
           :output-path (.getAbsolutePath output)
           :command (:command result)})))))

(defn prepare-miniaudio!
  "Compile unmodified miniaudio C implementation with Zig."
  []
  (let [{:keys [miniaudio-static-library miniaudio-shared-library]} (paths)]
    {:shared (prepare-miniaudio-library! :shared miniaudio-shared-library)
     :static (prepare-miniaudio-library! :static miniaudio-static-library)}))

(def ^:private font-assets
  [{:name "source-sans.ttf"
    :source "vendor/fonts/source-sans/TTF/SourceSans3-Regular.ttf"}
   {:name "source-serif.ttf"
    :source "vendor/fonts/source-serif/TTF/SourceSerif4-Regular.ttf"}
   {:name "source-code-pro.ttf"
    :source "vendor/fonts/source-code-pro/TTF/SourceCodePro-Regular.ttf"}])

(defn- copy-current-file!
  [source output]
  (let [current? (and (.isFile output)
                      (= (.length source) (.length output))
                      (>= (.lastModified output) (.lastModified source)))]
    (if current?
      :cached
      (do
        (io/make-parents output)
        (with-open [input (io/input-stream source)
                    stream (io/output-stream output)]
          (io/copy input stream))
        :copied))))

(defn prepare-font-assets!
  "Copy the three OFL font files into the target-independent asset directory."
  []
  (let [{:keys [root font-assets-root]} (paths)]
    (mapv
     (fn [{:keys [name source]}]
       (let [input (io/file root source)
             output (io/file font-assets-root name)]
         (when-not (.isFile input)
           (throw (ex-info "Vendored font file is missing"
                           {:font name :source (.getAbsolutePath input)})))
         {:font name
          :status (copy-current-file! input output)
          :output-path (.getAbsolutePath output)}))
     font-assets)))

(defn- read-image!
  [file]
  (or (ImageIO/read ^java.io.File file)
      (throw (ex-info "Could not decode animation image"
                      {:image (.getAbsolutePath ^java.io.File file)}))))

(defn- scaled-image
  [^BufferedImage source width height]
  (let [output (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
        graphics (.createGraphics output)]
    (try
      (.setComposite graphics AlphaComposite/Clear)
      (.fillRect graphics 0 0 width height)
      (.setComposite graphics AlphaComposite/SrcOver)
      (.setRenderingHint graphics
                         RenderingHints/KEY_INTERPOLATION
                         RenderingHints/VALUE_INTERPOLATION_BILINEAR)
      (.setRenderingHint graphics
                         RenderingHints/KEY_RENDERING
                         RenderingHints/VALUE_RENDER_QUALITY)
      (.drawImage graphics source 0 0 width height nil)
      output
      (finally
        (.dispose graphics)))))

(defn- spritesheet-frames
  [file columns rows]
  (let [image (read-image! file)
        width (.getWidth image)
        height (.getHeight image)]
    (when-not (and (pos? columns)
                   (pos? rows)
                   (zero? (mod width columns))
                   (zero? (mod height rows)))
      (throw (ex-info "Spritesheet dimensions must divide into a regular grid"
                      {:image (.getAbsolutePath ^java.io.File file)
                       :width width
                       :height height
                       :columns columns
                       :rows rows})))
    (let [frame-width (quot width columns)
          frame-height (quot height rows)]
      (vec
       (for [row (range rows)
             column (range columns)]
         (.getSubimage image
                       (* column frame-width)
                       (* row frame-height)
                       frame-width
                       frame-height))))))

(defn- individual-frames
  [files]
  (mapv read-image! files))

(defn- quantized-pixel
  [^BufferedImage image x y]
  (let [argb (.getRGB image x y)
        alpha (bit-and 255 (unsigned-bit-shift-right argb 24))]
    (when (>= alpha 72)
      (mapv (fn [shift]
              (let [channel (bit-and 255 (unsigned-bit-shift-right argb shift))]
                (min 255 (+ 16 (bit-and channel 224)))))
            [16 8 0]))))

(defn- frame-spans
  [^BufferedImage source width height]
  (let [image (scaled-image source width height)]
    (vec
     (mapcat
      (fn [y]
        (loop [x 0
               spans []]
          (if (>= x width)
            spans
            (if-let [[red green blue :as color] (quantized-pixel image x y)]
              (let [end (loop [candidate (inc x)]
                          (if (and (< candidate width)
                                   (= color (quantized-pixel image candidate y)))
                            (recur (inc candidate))
                            candidate))]
                (recur end
                       (conj spans {:x x
                                    :y y
                                    :width (- end x)
                                    :red red
                                    :green green
                                    :blue blue
                                    :alpha 255})))
              (recur (inc x) spans)))))
      (range height)))))

(defn- write-u16-le!
  [^DataOutputStream output value]
  (.writeByte output (bit-and value 255))
  (.writeByte output (bit-and (unsigned-bit-shift-right value 8) 255)))

(defn- write-animation-pack!
  [output-file frame-width frame-height frames]
  (let [spans (mapv #(frame-spans % frame-width frame-height) frames)]
    (when (> (count frames) 255)
      (throw (ex-info "An Aguafria animation pack supports at most 255 frames"
                      {:frames (count frames)})))
    (doseq [[index frame] (map-indexed vector spans)]
      (when (> (count frame) 65535)
        (throw (ex-info "A sprite frame contains too many horizontal spans"
                        {:frame index :spans (count frame)}))))
    (io/make-parents output-file)
    (with-open [output (DataOutputStream.
                       (BufferedOutputStream.
                        (io/output-stream output-file)))]
      (.writeBytes output "AGAN")
      (.writeByte output 1)
      (.writeByte output (count frames))
      (.writeByte output frame-width)
      (.writeByte output frame-height)
      (doseq [frame spans]
        (write-u16-le! output (count frame))
        (doseq [{:keys [x y width red green blue alpha]} frame]
          (.writeByte output x)
          (.writeByte output y)
          (.writeByte output width)
          (.writeByte output red)
          (.writeByte output green)
          (.writeByte output blue)
          (.writeByte output alpha))))
    {:frames (count frames)
     :width frame-width
     :height frame-height
     :spans (reduce + (map count spans))
     :bytes (.length ^java.io.File output-file)}))

(defn- source-signature
  [files options]
  {:options options
   :files (mapv (fn [^java.io.File file]
                  {:path (.getCanonicalPath file)
                   :bytes (.length file)
                   :modified (.lastModified file)})
                files)})

(defn prepare-animation-pack!
  "Build one compact span pack from either a regular spritesheet or a list of
  individual sprite images. The native animation API consumes the same format
  for both authoring styles."
  [{:keys [spritesheet columns rows sprites output frame-width frame-height]
    :or {frame-width 48 frame-height 48}}]
  (let [sheet? (some? spritesheet)
        files (if sheet? [spritesheet] (vec sprites))
        source-kind (if sheet? :spritesheet :sprites)
        output (io/file output)
        metadata-file (io/file (str (.getAbsolutePath output) ".edn"))
        options {:source-kind source-kind
                 :columns columns
                 :rows rows
                 :frame-width frame-width
                 :frame-height frame-height}
        signature (source-signature files options)
        cached? (and (.isFile output)
                     (.isFile metadata-file)
                     (= signature (edn/read-string (slurp metadata-file))))]
    (when-not (seq files)
      (throw (ex-info "Animation input requires :spritesheet or :sprites"
                      {:source-kind source-kind})))
    (doseq [^java.io.File file files]
      (when-not (.isFile file)
        (throw (ex-info "Animation image is missing"
                        {:image (.getAbsolutePath file)}))))
    (if cached?
      {:status :cached
       :source-kind source-kind
       :output-path (.getAbsolutePath output)
       :bytes (.length output)}
      (let [frames (if sheet?
                     (spritesheet-frames spritesheet columns rows)
                     (individual-frames files))
            report (write-animation-pack!
                    output frame-width frame-height frames)]
        (spit metadata-file (pr-str signature))
        (assoc report
               :status :built
               :source-kind source-kind
               :output-path (.getAbsolutePath output))))))

(defn- write-frame-images!
  [frames source-file output-directory]
  (.mkdirs ^java.io.File output-directory)
  (mapv
   (fn [index ^BufferedImage frame]
     (let [output (io/file output-directory (format "frame-%d.png" index))]
       (when (or (not (.isFile output))
                 (< (.lastModified output)
                    (.lastModified ^java.io.File source-file)))
         (ImageIO/write (scaled-image frame 192 192) "png" output))
       output))
   (range)
   frames))

(defn prepare-sprite-assets!
  "Prepare the generated water-orb animation through both supported inputs:
  one 3x2 spritesheet and six independent sprite files."
  []
  (let [{:keys [sprite-source sprite-assets-root]} (paths)
        frames (spritesheet-frames sprite-source 3 2)
        frame-files (write-frame-images!
                     frames sprite-source
                     (io/file sprite-assets-root "water-orb-frames"))
        sheet-pack (io/file sprite-assets-root "water-orb-sheet.agan")
        list-pack (io/file sprite-assets-root "water-orb-sprites.agan")]
    {:sheet (prepare-animation-pack!
             {:spritesheet sprite-source
              :columns 3
              :rows 2
              :frame-width 48
              :frame-height 48
              :output sheet-pack})
     :sprites (prepare-animation-pack!
               {:sprites frame-files
                :frame-width 48
                :frame-height 48
                :output list-pack})
     :frame-paths (mapv #(.getAbsolutePath ^java.io.File %) frame-files)}))

(defn- prepare-stb-truetype-library!
  [target source output]
  (let [{:keys [root stb-truetype-header]} (paths)
        current? (and (.isFile output)
                      (>= (.lastModified output)
                          (.lastModified stb-truetype-header)))]
    (if current?
      {:status :cached :target target :output-path (.getAbsolutePath output)}
      (do
        ;; stb_truetype is a single-header implementation. Copying the exact
        ;; upstream header with a .c extension lets Zig compile it directly;
        ;; there is no application bridge or hand-written C wrapper.
        (copy-current-file! stb-truetype-header source)
        (let [command
              (vec
               (concat
                ["zig" "build-lib" "-static"]
                (when target ["-target" target])
                ["-OReleaseFast" "-fPIC"
                 "-DSTB_TRUETYPE_IMPLEMENTATION"]
                (when target
                  ["-isystem"
                   (.getAbsolutePath (io/file (emscripten-sysroot) "include"))])
                [(str "-femit-bin=" (.getAbsolutePath output))
                 (.getAbsolutePath source)
                 "-lc"]))
              result (run-command! command root)]
          {:status :built
           :target target
           :output-path (.getAbsolutePath output)
           :command (:command result)})))))

(defn prepare-stb-truetype!
  "Compile the exact upstream stb_truetype header for native targets with Zig."
  []
  (let [{:keys [stb-truetype-native-source
                stb-truetype-static-library]} (paths)]
    (prepare-stb-truetype-library!
     nil stb-truetype-native-source stb-truetype-static-library)))

(defn prepare-web-stb-truetype!
  "Compile the same upstream stb_truetype header for WebAssembly with Zig."
  []
  (let [{:keys [stb-truetype-web-source
                stb-truetype-web-library]} (paths)]
    (prepare-stb-truetype-library!
     "wasm32-emscripten" stb-truetype-web-source stb-truetype-web-library)))

(defn prepare-web-flecs!
  "Compile unmodified Flecs for the browser with only the addons the game uses."
  []
  (let [{:keys [root flecs-source flecs-include flecs-web-object]} (paths)
        include (io/file (emscripten-sysroot) "include")
        newest-input (max (.lastModified flecs-source)
                          (.lastModified (io/file flecs-include "flecs.h")))
        current? (and (.isFile flecs-web-object)
                      (>= (.lastModified flecs-web-object) newest-input))]
    (if current?
      {:status :cached
       :output-path (.getAbsolutePath flecs-web-object)}
      (do
        (io/make-parents flecs-web-object)
        (let [command ["zig" "build-obj"
                       "-target" "wasm32-emscripten"
                       "-OReleaseFast"
                       (str "-femit-bin=" (.getAbsolutePath flecs-web-object))
                       (str "-I" (.getAbsolutePath flecs-include))
                       "-isystem" (.getAbsolutePath include)
                       "-DFLECS_CUSTOM_BUILD"
                       "-DFLECS_PIPELINE"
                       "-DFLECS_QUERY_DSL"
                       (.getAbsolutePath flecs-source)
                       "-lc"]
              result (run-command! command root)]
          {:status :built
           :output-path (.getAbsolutePath flecs-web-object)
           :command (:command result)})))))

(defn prepare-web-box3d!
  "Compile unmodified Box3D C sources to a cached WebAssembly archive with Zig."
  []
  (let [{:keys [root box3d-source-root box3d-include
                box3d-web-library]} (paths)
        sources (box3d-sources)
        include (io/file (emscripten-sysroot) "include")
        newest-input (reduce max (.lastModified (io/file box3d-include
                                                         "box3d/box3d.h"))
                             (map #(.lastModified ^java.io.File %) sources))
        current? (and (.isFile box3d-web-library)
                      (>= (.lastModified box3d-web-library) newest-input))]
    (if current?
      {:status :cached
       :output-path (.getAbsolutePath box3d-web-library)}
      (do
        (io/make-parents box3d-web-library)
        (let [command
              (vec
               (concat
                ["zig" "build-lib"
                 "-target" "wasm32-emscripten"
                 "-OReleaseFast" "-fPIC"
                 (str "-femit-bin=" (.getAbsolutePath box3d-web-library))
                 (str "-I" (.getAbsolutePath box3d-include))
                 (str "-I" (.getAbsolutePath box3d-source-root))
                 "-isystem" (.getAbsolutePath include)
                 "-DBOX3D_DISABLE_SIMD"
                 "-D_POSIX_C_SOURCE=199309L"
                 "-cflags" "-std=c17" "--"]
                (map #(.getAbsolutePath ^java.io.File %) sources)
                ["-lc"]))
              result (run-command! command root)]
          {:status :built
           :output-path (.getAbsolutePath box3d-web-library)
           :command (:command result)})))))

(defn prepare-web-miniaudio!
  "Compile unmodified miniaudio to a cached WebAudio-capable WASM object with Zig."
  []
  (let [{:keys [root miniaudio-source miniaudio-include
                miniaudio-web-object]} (paths)
        include (io/file (emscripten-sysroot) "include")
        newest-input (max (.lastModified miniaudio-source)
                          (.lastModified (io/file miniaudio-include
                                                  "miniaudio.h")))
        current? (and (.isFile miniaudio-web-object)
                      (>= (.lastModified miniaudio-web-object) newest-input))]
    (if current?
      {:status :cached
       :output-path (.getAbsolutePath miniaudio-web-object)}
      (do
        (io/make-parents miniaudio-web-object)
        (let [command
              ["zig" "build-obj"
               "-target" "wasm32-emscripten"
               "-OReleaseFast" "-fPIC"
               (str "-femit-bin=" (.getAbsolutePath miniaudio-web-object))
               (str "-I" (.getAbsolutePath miniaudio-include))
               "-isystem" (.getAbsolutePath include)
               (.getAbsolutePath miniaudio-source)
               "-lc"]
              result (run-command! command root)]
          {:status :built
           :output-path (.getAbsolutePath miniaudio-web-object)
           :command (:command result)})))))

(def ^:private glfw-common-sources
  ["context.c" "init.c" "input.c" "monitor.c" "platform.c" "vulkan.c"
   "window.c" "egl_context.c" "osmesa_context.c" "null_init.c"
   "null_joystick.c" "null_monitor.c" "null_window.c"])

(def ^:private glfw-macos-sources
  ["cocoa_init.m" "cocoa_joystick.m" "cocoa_monitor.m" "cocoa_window.m"
   "nsgl_context.m" "posix_module.c" "posix_poll.c" "posix_thread.c"
   "macos_time.c"])

(defn- prepare-glfw-library!
  [mode output]
  (let [{:keys [root glfw-include glfw-source-root]} (paths)
        macos? (= "Mac OS X" (System/getProperty "os.name"))
        source-names (if macos?
                       (concat glfw-common-sources glfw-macos-sources)
                       (throw (ex-info
                               "The first GLFW build recipe currently targets macOS"
                               {:os (System/getProperty "os.name")})))
        sources (mapv #(io/file glfw-source-root %) source-names)
        newest-source (reduce max 0 (map #(.lastModified ^java.io.File %) sources))
        current? (and (.isFile output)
                      (>= (.lastModified output) newest-source))]
    (if current?
      {:status :cached
       :mode mode
       :output-path (.getAbsolutePath output)}
      (do
        (io/make-parents output)
        (let [command
              (vec
               (concat
                ["zig" "build-lib"
                 (case mode :shared "-dynamic" :static "-static")
                 "-OReleaseFast" "-fPIC"
                 "-D_GLFW_COCOA"
                 (str "-I" (.getAbsolutePath glfw-include))
                 (str "-I" (.getAbsolutePath glfw-source-root))
                 (str "-femit-bin=" (.getAbsolutePath output))]
                (map #(.getAbsolutePath ^java.io.File %) sources)
                ["-framework" "Cocoa" "-framework" "IOKit"
                 "-framework" "CoreFoundation" "-framework" "QuartzCore"
                 "-lc"]))
              result (run-command! command root)]
          {:status :built
           :mode mode
           :output-path (.getAbsolutePath output)
           :command (:command result)})))))

(defn prepare-glfw!
  "Compile vendored GLFW master directly with Zig for hot-reload development."
  []
  (prepare-glfw-library! :shared (:glfw-shared-library (paths))))

(defn prepare-static-glfw!
  "Compile vendored GLFW master directly with Zig for standalone linking."
  []
  (prepare-glfw-library! :static (:glfw-static-library (paths))))

(defn vulkan-loader
  "Resolve the desktop Vulkan loader without baking it into generated code."
  []
  (let [sdk (System/getenv "VULKAN_SDK")
        candidates (remove nil?
                           [(when sdk (io/file sdk "lib/libvulkan.dylib"))
                            (io/file "/opt/homebrew/lib/libvulkan.dylib")
                            (io/file "/usr/local/lib/libvulkan.dylib")])]
    (or (some #(when (.isFile ^java.io.File %) (.getAbsolutePath ^java.io.File %))
              candidates)
        (throw (ex-info
                "Could not find a Vulkan loader; set VULKAN_SDK"
                {:candidates (mapv str candidates)})))))

(defn link-arguments
  "Development links. Each upstream dylib owns its long-lived native globals."
  []
  (let [{:keys [flecs-shared-library box3d-shared-library
                miniaudio-shared-library stb-truetype-static-library]} (paths)]
    [(.getAbsolutePath flecs-shared-library)
     (.getAbsolutePath box3d-shared-library)
     (.getAbsolutePath miniaudio-shared-library)
     (.getAbsolutePath stb-truetype-static-library)
     "-lc"]))

(defn standalone-link-arguments
  "Release/standalone link arguments; no development shared library required."
  []
  (let [{:keys [flecs-static-library glfw-static-library box3d-static-library
                miniaudio-static-library stb-truetype-static-library]} (paths)]
    [(.getAbsolutePath flecs-static-library)
     (.getAbsolutePath glfw-static-library)
     (.getAbsolutePath box3d-static-library)
     (.getAbsolutePath miniaudio-static-library)
     (.getAbsolutePath stb-truetype-static-library)
     (vulkan-loader)
     "-framework" "Cocoa"
     "-framework" "IOKit"
     "-framework" "CoreFoundation"
     "-framework" "QuartzCore"
     "-framework" "CoreAudio"
     "-framework" "AudioToolbox"
     "-lc"]))

(defn desktop-link-arguments
  "Development links for game logic, GLFW, and the system Vulkan loader."
  []
  (let [{:keys [flecs-shared-library glfw-shared-library box3d-shared-library
                miniaudio-shared-library stb-truetype-static-library]} (paths)]
    [(.getAbsolutePath flecs-shared-library)
     (.getAbsolutePath glfw-shared-library)
     (.getAbsolutePath box3d-shared-library)
     (.getAbsolutePath miniaudio-shared-library)
     (.getAbsolutePath stb-truetype-static-library)
     (vulkan-loader)
     "-lc"]))

(defn build-standalone!
  "Build the complete game as a ReleaseFast native executable.
  Clojure is only the build frontend; the artifact has no JVM dependency."
  []
  (prepare-flecs!)
  (prepare-box3d!)
  (prepare-miniaudio!)
  (prepare-stb-truetype!)
  (prepare-font-assets!)
  (prepare-sprite-assets!)
  (prepare-static-glfw!)
  (zig-build/load-source-only! 'simple-game.standalone)
  (let [output (io/file (:root (paths)) "build/standalone/simple-game")]
    (az/build!
     'simple-game.standalone
     {:kind :exe
      :name "simple-game"
      :output output
      :optimize "ReleaseFast"
      :reloadable? false
      :async? false
      :zig-args (standalone-link-arguments)})))

(defn run-behavior-probe!
  "Compile and run the Debug-tested gameplay contract as ReleaseFast native code."
  []
  (prepare-flecs!)
  (prepare-box3d!)
  (prepare-miniaudio!)
  (prepare-stb-truetype!)
  (prepare-static-glfw!)
  (zig-build/load-source-only! 'simple-game.behavior-probe)
  (let [output (io/file (:root (paths))
                        "build/standalone/simple-game-behavior-probe")
        artifact
        (az/build!
         'simple-game.behavior-probe
         {:kind :exe
          :name "simple-game-behavior-probe"
          :output output
          :optimize "ReleaseFast"
          :reloadable? false
          :async? false
          :zig-args (standalone-link-arguments)})
        execution (run-command! [(.getAbsolutePath output)] (:root (paths)))]
    {:artifact artifact
     :execution (select-keys execution [:command :exit :output])}))

(defn- copy-web-assets!
  [output-directory]
  (doseq [name ["index.html" "app.js"]]
    (let [source (io/file (:root (paths)) "resources/web" name)
          output (io/file output-directory name)]
      (when-not (.isFile source)
        (throw (ex-info "Web asset is missing" {:asset (.getAbsolutePath source)})))
      (io/make-parents output)
      (with-open [input (io/input-stream source)
                  stream (io/output-stream output)]
        (io/copy input stream))))
  output-directory)

(defn build-web!
  "Build the shared game and its unmodified C dependencies to WebAssembly."
  []
  (let [{:keys [root web-build-root flecs-web-object box3d-web-library
                miniaudio-web-object stb-truetype-web-library
                font-assets-root sprite-assets-root]} (paths)
        generated-directory (io/file root "generated/simple_game/bindings")]
    (when-not (every? #(.isFile ^java.io.File %)
                      [(io/file generated-directory "webgl.clj")
                       (io/file generated-directory "emscripten.clj")
                       (io/file generated-directory "stb_truetype.clj")
                       (io/file generated-directory "stdio.clj")])
      ((requiring-resolve 'simple-game.generate/generate-web!)))
    (let [flecs (prepare-web-flecs!)
          box3d (prepare-web-box3d!)
          miniaudio (prepare-web-miniaudio!)
          stb-truetype (prepare-web-stb-truetype!)
          font-assets (prepare-font-assets!)
          sprite-assets (prepare-sprite-assets!)]
      (zig-build/load-source-only! 'simple-game.web)
      (let [game-object (io/file web-build-root "simple-game.o")
            native-object
            (az/build! 'simple-game.web
                       {:kind :object
                        :name "simple-game-web"
                        :output game-object
                        :target "wasm32-emscripten"
                        :optimize "ReleaseFast"
                        :reloadable? false
                        :async? false
                        :zig-args []})
            javascript (io/file web-build-root "simple-game.js")
            emcc-command
            ["emcc"
             (.getAbsolutePath game-object)
             (.getAbsolutePath flecs-web-object)
             (.getAbsolutePath box3d-web-library)
             (.getAbsolutePath miniaudio-web-object)
             (.getAbsolutePath stb-truetype-web-library)
             "-O3"
             "-o" (.getAbsolutePath javascript)
             "-sMODULARIZE=1"
             "-sEXPORT_NAME=createAguafriaGame"
             "-sENVIRONMENT=web"
             "-sALLOW_MEMORY_GROWTH=1"
             "-sMIN_WEBGL_VERSION=2"
             "-sMAX_WEBGL_VERSION=2"
             "-sFULL_ES3=1"
             "-sUSE_GLFW=3"
             "-sNO_EXIT_RUNTIME=1"
             "--no-entry"
             "--preload-file"
             (str (.getAbsolutePath font-assets-root) "@/build/assets/fonts")
             "--preload-file"
             (str (.getAbsolutePath sprite-assets-root) "@/build/assets/sprites")
             "-sEXPORTED_FUNCTIONS=['_web_start','_web_stop']"]
            package (run-command! emcc-command root)]
        (copy-web-assets! web-build-root)
        {:flecs flecs
         :box3d box3d
         :miniaudio miniaudio
         :stb-truetype stb-truetype
         :font-assets font-assets
         :sprite-assets sprite-assets
         :native-object native-object
         :package {:command (:command package)
                   :javascript-path (.getAbsolutePath javascript)
                   :wasm-path (.getAbsolutePath
                               (io/file web-build-root "simple-game.wasm"))
                   :html-path (.getAbsolutePath
                               (io/file web-build-root "index.html"))}}))))

(defn -main
  [& [command]]
  (case (or command "prepare")
    "prepare" (prn {:flecs (prepare-flecs!)
                    :box3d (prepare-box3d!)
                    :miniaudio (prepare-miniaudio!)
                    :stb-truetype (prepare-stb-truetype!)
                    :font-assets (prepare-font-assets!)
                    :sprite-assets (prepare-sprite-assets!)
                    :glfw (prepare-glfw!)
                    :vulkan-loader (vulkan-loader)})
    "desktop" (prn {:flecs (prepare-flecs!)
                     :box3d (prepare-box3d!)
                     :miniaudio (prepare-miniaudio!)
                     :stb-truetype (prepare-stb-truetype!)
                     :font-assets (prepare-font-assets!)
                     :sprite-assets (prepare-sprite-assets!)
                     :glfw (prepare-glfw!)
                     :vulkan-loader (vulkan-loader)})
    "standalone" (prn {:flecs (prepare-flecs!)
                        :box3d (prepare-box3d!)
                        :miniaudio (prepare-miniaudio!)
                        :stb-truetype (prepare-stb-truetype!)
                        :font-assets (prepare-font-assets!)
                        :sprite-assets (prepare-sprite-assets!)
                        :glfw (prepare-static-glfw!)
                        :vulkan-loader (vulkan-loader)
                        :artifact (build-standalone!)})
    "behavior" (prn (run-behavior-probe!))
    "web" (prn (build-web!))
    (throw (ex-info "Unknown simple-game build command"
                    {:command command
                     :supported ["prepare" "desktop" "standalone"
                                 "behavior" "web"]})))
  (shutdown-agents))
