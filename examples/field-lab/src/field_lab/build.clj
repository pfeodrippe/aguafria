(ns field-lab.build
  (:require [aguafria.zig :as az]
            [aguafria.zig.build :as zb]
            [aguafria.zig.runtime :as runtime]
            [aguafria-examples-native.build :as native]
            [aguafria-examples-native.vendor :as vendor]
            [clojure.java.io :as io]))

(defn root [] (.getCanonicalFile (io/file ".")))

(def ccd-dependencies
  {:tight-inclusion {:url "https://github.com/Continuous-Collision-Detection/Tight-Inclusion.git"
                     :commit "6f84001a790a9d3362b7c13d410037140d83fa2c"}
   :eigen {:url "https://gitlab.com/libeigen/eigen.git"
           :commit "3147391d946bb4b6c68edd901f2add6ac1f31f8c"}
   :spdlog {:url "https://github.com/gabime/spdlog.git"
            :commit "6fa36017cfd5731d617e1a934f0e5ea9c4445b13"}})

(defn prepare-geometry!
  "Compile the strict AguaFria geometry predicates for C++ library adapters."
  []
  (let [sources ["src/field_lab/geometry.clj" "src/field_lab/physics.clj"]
        contents (mapv slurp sources)
        input (str (az/zig-executable) "ReleaseSafe -fPIC strict" (pr-str contents))
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes input "UTF-8"))
        hash (subs (apply str (map #(format "%02x" (bit-and % 255)) digest)) 0 24)
        output (io/file (root) "build/geometry" hash "pitoco_geometry.o")]
    (locking #'prepare-geometry!
      (when-not (.isFile output)
        (io/make-parents output)
        (binding [runtime/*source-only-registration?* true]
          (require 'field-lab.geometry :reload))
        (az/build! 'field-lab.geometry {:kind :object :output output :optimize "ReleaseSafe"
                                       :reloadable? false :async? false :zig-args ["-lc" "-fPIC"]})
        (when-not (= contents (mapv slurp sources))
          (.delete output)
          (throw (ex-info "Geometry changed during compilation; retry" {})))))
    output))

(defn prepare-ccd!
  "Compile pinned conservative CCD with strict floating-point arithmetic."
  []
  (let [geometry (prepare-geometry!)
        directories
        (into {}
              (for [[name {:keys [url commit]}] ccd-dependencies]
                (let [directory (io/file (root) "build/vendor" (clojure.core/name name))]
                  (when-not (.isDirectory (io/file directory ".git"))
                    (io/make-parents (io/file directory ".keep"))
                    (vendor/run-command! ["git" "clone" "--filter=blob:none" url (str directory)] (root)))
                  (when-not (= commit (.trim (:output (vendor/run-command! ["git" "rev-parse" "HEAD"] directory))))
                    (vendor/run-command! ["git" "fetch" "--depth" "1" "origin" commit] directory)
                    (vendor/run-command! ["git" "checkout" "--detach" commit] directory))
                  [name directory])))
        settings "#pragma once\n#define TIGHT_INCLUSION_WITH_DOUBLE_PRECISION\n"
        wrapper-source (slurp (io/file (root) "native/ccd.cpp"))
        geometry-header (slurp (io/file (root) "native/geometry.h"))
        fingerprint-input (str (pr-str ccd-dependencies) settings (az/zig-executable)
                               wrapper-source geometry-header (str geometry)
                               "c++17 strict-fp -OReleaseSafe FMT_HEADER_ONLY EIGEN_MPL2_ONLY v3")
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes fingerprint-input "UTF-8"))
        fingerprint (subs (apply str (map #(format "%02x" (bit-and % 255)) digest)) 0 24)
        destination (io/file (root) "build/ccd" fingerprint)
        archive (io/file destination "libpitoco_ccd.a")
        source (io/file (:tight-inclusion directories) "src/tight_inclusion")]
    (when-not (.isFile archive)
      (.mkdirs destination)
      (let [temporary (.toFile (java.nio.file.Files/createTempDirectory
                               (.toPath destination) "compile-"
                               (make-array java.nio.file.attribute.FileAttribute 0)))
            config (io/file temporary "tight_inclusion/config.hpp")
            wrapper (io/file temporary "pitoco-ccd.cpp")
            pending (io/file temporary "libpitoco_ccd.a")]
        (try
          (io/make-parents config)
          (spit config settings)
          (spit wrapper wrapper-source)
          (spit (io/file temporary "geometry.h") geometry-header)
          (vendor/run-command!
           (into ["zig" "build-lib" "-static" "-OReleaseSafe" "-fPIC" "-cflags"
                  "-std=c++17" "-fno-fast-math" "-ffp-contract=off" "-DFMT_HEADER_ONLY" "-DEIGEN_MPL2_ONLY" "--"
                  (str "-I" temporary) (str "-I" (io/file (:tight-inclusion directories) "src"))
                  (str "-I" (:eigen directories)) (str "-I" (io/file (:spdlog directories) "include"))
                  (str wrapper) (str geometry)]
                 (concat (map #(str (io/file source %))
                              ["avx.cpp" "ccd.cpp" "interval.cpp" "interval_root_finder.cpp" "logger.cpp"])
                         ["-lc" "-lc++" (str "-femit-bin=" pending)]))
           (root))
          ;; Concurrent REPLs may compile the same fingerprint. Readers must
          ;; see a complete archive, and compilation uses the hashed snapshot.
          (java.nio.file.Files/move (.toPath pending) (.toPath archive)
                                   (into-array java.nio.file.CopyOption
                                               [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                                java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
          (finally
            (doseq [file (reverse (file-seq temporary))] (.delete ^java.io.File file))))))

    archive))

(defn configure-ccd!
  []
  (let [archive (str (prepare-ccd!))
        arguments (vec (:zig-args (az/configuration)))
        prefix (str (io/file (root) "build/ccd") "/")]
    (when-not (some #{archive} arguments)
      (let [kept (remove #(and (string? %) (.startsWith ^String % prefix)
                              (.endsWith ^String % "/libpitoco_ccd.a")) arguments)]
        (az/configure! {:zig-args (into (vec kept) [archive "-lc" "-lc++"])})))
    archive))

(def ipc-dependency
  {:url "https://github.com/ipc-sim/ipc-toolkit.git"
   :commit "478876f30bf8ea768772dd8983c26a1a801ad976"})

(defn prepare-variational!
  "Publish a distinct contact-backend dylib for each source generation."
  []
  (let [geometry (prepare-geometry!)
        sources ["native/variational.cpp" "native/geometry.h" "native/CMakeLists.txt"]
        contents (mapv #(slurp (io/file (root) %)) sources)
        compiler (:output (vendor/run-command! ["clang++" "--version"] (root)))
        input (str (pr-str ipc-dependency) compiler (str geometry) (apply str contents))
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes input "UTF-8"))
        fingerprint (subs (apply str (map #(format "%02x" (bit-and % 255)) digest)) 0 24)
        library (io/file (root) "build/variational-libraries" (str "libpitoco_variational_" fingerprint ".dylib"))
        directory (io/file (root) "build/vendor/ipc-toolkit")
        lock-file (io/file (root) "build/variational-build.lock")]
    (io/make-parents lock-file)
    (locking #'prepare-variational!
      (with-open [file (java.io.RandomAccessFile. lock-file "rw")
                  channel (.getChannel file)
                  lock (.lock channel)]
        (when-not (.isFile library)
          (when-not (.isDirectory (io/file directory ".git"))
            (vendor/run-command! ["git" "clone" "--filter=blob:none" (:url ipc-dependency) (str directory)] (root)))
          (when-not (= (:commit ipc-dependency) (.trim (:output (vendor/run-command! ["git" "rev-parse" "HEAD"] directory))))
            (vendor/run-command! ["git" "fetch" "--depth" "1" "origin" (:commit ipc-dependency)] directory)
            (vendor/run-command! ["git" "checkout" "--detach" (:commit ipc-dependency)] directory))
          (vendor/run-command! ["cmake" "-S" "native" "-B" "build/variational" "-G" "Ninja"
                                (str "-DPITOCO_IPC_SOURCE=" directory) (str "-DPITOCO_GEOMETRY_OBJECT=" geometry) "-DCMAKE_BUILD_TYPE=Release"] (root))
          (vendor/run-command! ["cmake" "--build" "build/variational" "--target" "pitoco_variational" "-j" "6"] (root))
          ;; Never label a binary with a hash from an earlier source generation.
          ;; A concurrent edit requires a fresh build request before publication.
          (when-not (= contents (mapv #(slurp (io/file (root) %)) sources))
            (throw (ex-info "Variational sources changed during compilation; retry the build" {})))
          (io/make-parents library)
          (let [pending (io/file (.getParentFile library) (str ".pending-" (java.util.UUID/randomUUID) ".dylib"))]
            (try
              (io/copy (io/file (root) "build/variational/libpitoco_variational.dylib") pending)
              (vendor/run-command! ["install_name_tool" "-id" (str library) (str pending)] (root))
              (java.nio.file.Files/move (.toPath pending) (.toPath library)
                                       (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
              (finally (.delete pending)))))))
    library))

(defn configure-variational!
  []
  (let [library (str (prepare-variational!))
        prefix (str (io/file (root) "build/variational-libraries") "/")
        arguments (vec (:zig-args (az/configuration)))
        kept (vec (remove #(and (string? %) (.startsWith ^String % prefix)) arguments))
        links (cond-> (conj kept library)
                (not (some #{"Accelerate"} kept)) (into ["-framework" "Accelerate"]))]
    (when (not= arguments links)
      (az/configure! {:zig-args links}))
    library))

(defn prepare-host!
  "A stable AguaFria native host owns the registry across UI hot reloads."
  [kind]
  (let [sources ["src/field_lab/host.clj" "native/extension_host.h"
                 "native/sdk/pitoco.h" "native/panel.h"]
        contents (mapv slurp sources)
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes (str kind (az/zig-executable) "ReleaseSafe" (pr-str contents)) "UTF-8"))
        fingerprint (subs (apply str (map #(format "%02x" (bit-and % 255)) digest)) 0 20)
        extension (case kind :dynamic-lib ".dylib" :static-lib ".a")
        output (io/file (root) "build" (str "libpitoco_host_" fingerprint extension))]
    (locking #'prepare-host!
      (when-not (.isFile output)
        (binding [runtime/*source-only-registration?* true]
          (require 'field-lab.host :reload))
        (az/build! 'field-lab.host {:kind kind :output output :optimize "ReleaseSafe"
                                   :reloadable? false :async? false :zig-args ["-lc"]})
        (when-not (= contents (mapv slurp sources))
          (.delete output)
          (throw (ex-info "Host sources changed during compilation; retry" {})))))
    output))

(defn development-link-arguments!
  []
  [(str (prepare-host! :dynamic-lib))])

(defn prepare-shaders!
  []
  (doseq [name ["mesh.vert" "mesh.frag"]]
    (vendor/run-command! ["glslc" (str "resources/shaders/" name) "-o"
                          (str "resources/shaders/" name ".spv")]
                         (root))))

(defn build!
  []
  (native/prepare-static!)
  (prepare-shaders!)
  (let [host (prepare-host! :static-lib)]
    (zb/load-source-only! 'field-lab.app)
    (az/build! 'field-lab.app
               {:kind :exe
                :name "pitoco"
                :output (io/file (root) "build/pitoco")
                :optimize "ReleaseSafe"
                :reloadable? false
                :async? false
                :zig-args (into [(str host) (str (prepare-ccd!))
                                 (str (:output (native/prepare-imgui-controls! :static)))]
                                (native/imgui-standalone-link-arguments))})))

(defn package-variational!
  "Bundle the native contact backend and notices independently of the JVM host."
  [contents]
  (let [library (prepare-variational!)
        target (io/file contents "Frameworks/libpitoco_variational.dylib")
        notices (io/file contents "Resources/licenses/variational")
        cache (slurp (io/file (root) "build/variational/CMakeCache.txt"))
        dependencies (into {"ipc-toolkit" (str (io/file (root) "build/vendor/ipc-toolkit"))}
                           (for [[_ name directory]
                                 (re-seq #"(?m)^CPM_PACKAGE_(.+)_SOURCE_DIR:INTERNAL=(.+)$" cache)]
                             [name directory]))
        dependencies (assoc dependencies "predicates" (str (io/file (root) "build/variational/_deps/predicates-src")))
        manifest (into (sorted-map)
                       (for [[name directory] dependencies]
                         [name {:commit (when (.exists (io/file directory ".git"))
                                          (.trim (:output (vendor/run-command! ["git" "rev-parse" "HEAD"] (io/file directory)))))}]))]
    (io/make-parents target)
    (io/copy library target)
    (vendor/run-command! ["install_name_tool" "-id" "@rpath/libpitoco_variational.dylib" (str target)] (root))
    (.mkdirs notices)
    (doseq [[name directory] dependencies
            file (.listFiles (io/file directory))
            :when (and (.isFile file) (re-find #"^(LICENSE|COPYING|NOTICE)" (.getName file)))]
      (io/copy file (io/file notices (str name "-" (.getName file)))))
    (spit (io/file notices "sources.edn") (str (pr-str {:toolkit ipc-dependency :dependencies manifest}) "\n"))
    target))

(defn package-app!
  "Produce a launchable local macOS bundle with its shaders and license notices."
  []
  (let [app (io/file (root) "build/Pitoco.app")
        contents (io/file app "Contents")
        binary (io/file contents "MacOS/pitoco")
        resources (io/file contents "Resources")]
    (io/make-parents binary)
    (io/copy (io/file (root) "build/pitoco") binary)
    (.setExecutable binary true)
    (package-variational! contents)
    (doseq [name ["mesh.vert.spv" "mesh.frag.spv"]]
      (let [target (io/file resources "resources/shaders" name)]
        (io/make-parents target)
        (io/copy (io/file (root) "resources/shaders" name) target)))
    (doseq [[directory filename] [["flecs" "LICENSE"] ["glfw" "LICENSE.md"]
                                  ["imgui" "LICENSE.txt"] ["vulkan-headers" "LICENSE.md"]]]
      (let [source (io/file (:root (native/paths)) "build/vendor" directory filename)
            target (io/file resources "licenses" (str directory ".txt"))]
        (io/make-parents target)
        (io/copy source target)))
    (doseq [[directory filename] [["tight-inclusion" "LICENSE"] ["eigen" "COPYING.MPL2"]
                                  ["eigen" "COPYING.BSD"] ["eigen" "COPYING.MINPACK"]
                                  ["eigen" "COPYING.APACHE"] ["spdlog" "LICENSE"]]]
      (let [source (io/file (root) "build/vendor" directory filename)
            target (io/file resources "licenses" (str directory "-" filename ".txt"))]
        (io/make-parents target)
        (io/copy source target)))
    (let [header (slurp (io/file (root) "build/vendor/spdlog/include/spdlog/fmt/bundled/format.h"))]
      (spit (io/file resources "licenses/fmt.txt") (subs header 0 (+ 2 (.indexOf header "*/")))))
    (spit (io/file resources "licenses/ccd-sources.edn") (str (pr-str ccd-dependencies) "\n"))
    (spit (io/file contents "Info.plist")
          (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
               "<plist version=\"1.0\"><dict>"
               "<key>CFBundleIdentifier</key><string>dev.aguafria.pitoco</string>"
               "<key>CFBundleName</key><string>Pitoco</string>"
               "<key>CFBundleExecutable</key><string>pitoco</string>"
               "<key>CFBundlePackageType</key><string>APPL</string>"
               "<key>CFBundleVersion</key><string>1</string>"
               "<key>NSHighResolutionCapable</key><true/>" "</dict></plist>\n"))
    app))

(defn -main
  [& _]
  (build!)
  (prn {:binary (str (io/file (root) "build/pitoco")) :app (str (package-app!))})
  (shutdown-agents))
