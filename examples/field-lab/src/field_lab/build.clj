(ns field-lab.build
  (:require [aguafria.zig :as az]
            [aguafria.zig.build :as zb]
            [aguafria-examples-native.build :as native]
            [aguafria-examples-native.vendor :as vendor]
            [clojure.java.io :as io]))

(defn root [] (.getCanonicalFile (io/file ".")))

(defn- compile-panel!
  [filename sources]
  (native/prepare-imgui-static!)
  (let [out (io/file (root) "build" filename)
        {:keys [imgui-root vulkan-root]} (native/paths)]
    (io/make-parents out)
    (vendor/run-command!
     (into ["zig" "build-lib" "-static" "-OReleaseSafe" "-fPIC" "-cflags"
            "-std=c++17" "--" (str "-I" imgui-root)
            (str "-I" (io/file vulkan-root "include"))]
           (concat sources ["-lc" "-lc++" (str "-femit-bin=" out)]))
     (root))
    out))

(defn prepare-panel!
  "Standalone/test archive includes the extension host; no shared host is required."
  []
  (compile-panel! "libfield_panel.a" ["native/panel.cpp" "native/extension_host.cpp"]))

(defn development-link-arguments!
  "All development generations share one native plugin registry and mailbox."
  []
  (let [sources ["native/extension_host.cpp" "native/extension_host.h"
                 "native/sdk/pitoco.h" "native/panel.h"]
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes (apply str (map slurp sources)) "UTF-8"))
        fingerprint (subs (apply str (map #(format "%02x" (bit-and % 255)) digest)) 0 20)
        host (io/file (root) "build" (str "libpitoco_host_" fingerprint ".dylib"))]
    (io/make-parents host)
    (when-not (.isFile host)
      (vendor/run-command! ["zig" "build-lib" "-dynamic" "-OReleaseSafe" "-fPIC"
                            "native/extension_host.cpp" "-lc" "-lc++"
                            (str "-femit-bin=" host)]
                           (root)))
    [(str (compile-panel! "libfield_panel_dev.a" ["native/panel.cpp"])) (str host)]))

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
  (let [panel (prepare-panel!)]
    (zb/load-source-only! 'field-lab.app)
    (az/build! 'field-lab.app
               {:kind :exe
                :name "pitoco"
                :output (io/file (root) "build/pitoco")
                :optimize "ReleaseSafe"
                :reloadable? false
                :async? false
                :zig-args (into [(str panel)] (native/imgui-standalone-link-arguments))})))

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
