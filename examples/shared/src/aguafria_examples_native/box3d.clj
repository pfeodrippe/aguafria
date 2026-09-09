(ns aguafria-examples-native.box3d
  "Pinned Box3D source, Zig-built binaries and ordinary generated C bindings.
  Opt-in: examples that do not use physics need not link this library."
  (:require [aguafria.c :as ac]
            [aguafria.zig :as az]
            [aguafria-examples-native.vendor :as vendor]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def wheel-motor-fix "wheel-motor-v1")

(defn patched-wheel-joint-source!
  "Build-only overlay for the pinned upstream wheel-joint warm-start bug.
  The non-steering branch adds the spin impulse twice. Keep one application,
  exactly as the steerable branch does. Never modify the vendor checkout.
  Fail on upstream drift so this fix must be reviewed when the pin changes."
  [root source]
  (let [file (io/file source "src/wheel_joint.c")
        original (slurp file)
        before (str "angularImpulse = b3Add(\n\t\t\tangularImpulse,\n"
                    "\t\t\tb3Blend3( angularImpulseX, perpAxisX, angularImpulseY, perpAxisY, joint->spinImpulse, spinAxis ) );")
        after "angularImpulse = b3Blend3( angularImpulseX, perpAxisX, angularImpulseY, perpAxisY, joint->spinImpulse, spinAxis );"
        at (.indexOf original before)]
    (when (or (neg? at) (not= at (.lastIndexOf original before)))
      (throw (ex-info "Box3D wheel-motor patch no longer matches exactly once; review the upstream source"
                      {:file (str file) :revision wheel-motor-fix})))
    (let [patched (str/replace-first original before after)
          output (io/file root "build/native" wheel-motor-fix "wheel_joint.c")]
      (when-not (and (.isFile output) (= patched (slurp output)))
        (io/make-parents output)
        (spit output patched))
      output)))

(defn paths []
  (let [root (vendor/project-root)
        source (io/file root "build/vendor/box3d")]
    {:root root :source source
     :include (io/file source "include")
     :header (io/file source "include/box3d/box3d.h")
     :bindings (io/file root "generated/aguafria_examples_native/bindings/box3d.clj")
     ;; Native dependency revisions get new paths: never overwrite a dylib
     ;; already mapped into a live development JVM.
     :shared (io/file root "build/native" (System/mapLibraryName (str "aguafria_box3d_" wheel-motor-fix)))
     :static (io/file root "build/native" (str "libaguafria_box3d_" wheel-motor-fix ".a"))}))

(defn build!
  "Compile pinned C17 plus the audited wheel-motor fix with embedded Zig."
  [mode]
  (when-not (#{:shared :static} mode)
    (throw (ex-info "Expected :shared or :static Box3D build" {:mode mode})))
  (vendor/checkout! :box3d)
  (let [{:keys [root source include] :as p} (paths)
        output (get p mode)
        wheel-source (patched-wheel-joint-source! root source)
        inputs (filter #(.isFile ^java.io.File %)
                       (concat [wheel-source]
                               (mapcat file-seq [(io/file source "src") include])))
        sources (->> (.listFiles (io/file source "src"))
                     (filter #(.endsWith (.getName ^java.io.File %) ".c"))
                     (sort-by #(.getName ^java.io.File %))
                     (map #(if (= "wheel_joint.c" (.getName ^java.io.File %)) wheel-source %)))
        flags ["-OReleaseFast" "-fPIC" "-DB3_ENABLE_ASSERT=1"]
        key (pr-str {:commit (get-in vendor/dependencies [:box3d :commit])
                     :wheel-motor-fix wheel-motor-fix
                     :mode mode :flags flags :zig (az/zig-executable)
                     :newest (reduce max 0 (map #(.lastModified ^java.io.File %) inputs))})
        stamp (io/file (str output ".build-key"))]
    (if (and (.isFile output) (.isFile stamp) (= key (slurp stamp)))
      {:status :cached :output output}
      (do
        (io/make-parents output)
        (vendor/run-command!
         (vec (concat ["zig" "build-lib" (if (= mode :shared) "-dynamic" "-static")]
                      flags
                      [(str "-I" include) (str "-I" (io/file source "src"))
                       (str "-femit-bin=" output)]
                      (when (= mode :shared) ["-Dbox3d_EXPORTS"])
                      ["-cflags" "-std=c17" "--"]
                      (map str sources) ["-lc"])) root)
        (spit stamp key)
        {:status :built :output output}))))

(defonce loaded? (atom false))

(defn ensure-loaded! []
  (when-not @loaded?
    (build! :shared)
    (let [{:keys [root header include bindings shared]} (paths)
          stamp (io/file (str bindings ".upstream"))
          commit (get-in vendor/dependencies [:box3d :commit])]
      (when-not (and (.isFile bindings) (.isFile stamp) (= commit (slurp stamp)))
        (ac/translate-header!
         header bindings
         {:namespace 'aguafria-examples-native.bindings.box3d
          :include-dirs [include]
          :cache-dir (str (io/file root ".aguafria/c-bindings"))
          :overwrite? true})
        (spit stamp commit))
      (ac/load-bindings! bindings)
      (az/configure! {:zig-args (vec (distinct (concat (:zig-args (az/configuration))
                                                     [(str shared) "-lc"])))})
      (reset! loaded? true)))
  {:loaded? @loaded? :commit (get-in vendor/dependencies [:box3d :commit])})

(ensure-loaded!)
