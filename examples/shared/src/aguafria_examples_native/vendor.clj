(ns aguafria-examples-native.vendor
  "Pinned, regenerable upstream sources shared by native examples."
  (:require [clojure.java.io :as io]))

(def dependencies
  {:flecs {:url "https://github.com/SanderMertens/flecs.git"
           :commit "14ad7136f550079b9c1efe15cc669b737a4bd1fd"}
   :glfw {:url "https://github.com/glfw/glfw.git"
          :commit "d9d6f0f1f967807ffade6598ea9a631ebaf37a56"}
   :imgui {:url "https://github.com/ocornut/imgui.git"
           :commit "6d910d5487d11ca567b61c7824b0c78c569d62f0"}
   :vulkan-headers {:url "https://github.com/KhronosGroup/Vulkan-Headers.git"
                    :commit "11d6898377797e07dbd543aaaa367e4465074597"}})

(defn project-root
  []
  (or
   (some (fn [^java.io.File directory]
           (when (and (.isFile (io/file directory "deps.edn"))
                      (.isDirectory (io/file directory "src/aguafria_examples_native")))
             (.getCanonicalFile directory)))
         (take-while some?
                     (iterate #(.getParentFile ^java.io.File %)
                              (.getCanonicalFile
                               (io/file (System/getProperty "user.dir"))))))
   (let [resource (io/resource "aguafria_examples_native/vendor.clj")]
     (when (= "file" (some-> resource .getProtocol))
       (some (fn [^java.io.File directory]
               (when (.isFile (io/file directory "deps.edn"))
                 (.getCanonicalFile directory)))
             (take-while some?
                         (iterate #(.getParentFile ^java.io.File %)
                                  (io/file (.toURI resource)))))))
   (throw (ex-info "Could not locate examples/shared"
                   {:user-dir (System/getProperty "user.dir")}))))

(defn run-command!
  [command directory]
  (let [process (-> (ProcessBuilder. ^java.util.List command)
                    (doto (.directory directory)
                          (.redirectErrorStream true))
                    .start)
        output (slurp (.getInputStream process))
        exit (.waitFor process)]
    (when-not (zero? exit)
      (throw (ex-info "Native example dependency command failed"
                      {:command command
                       :directory (.getAbsolutePath ^java.io.File directory)
                       :exit exit
                       :output output})))
    {:command command :exit exit :output output}))

(defn checkout!
  [name]
  (let [{:keys [url commit]} (get dependencies name)
        root (project-root)
        target (io/file root "build/vendor" (clojure.core/name name))]
    (when-not url
      (throw (ex-info "Unknown shared native dependency" {:name name})))
    (when-not (.isDirectory (io/file target ".git"))
      (io/make-parents (io/file target ".keep"))
      (run-command! ["git" "clone" "--filter=blob:none"
                     url (.getAbsolutePath target)]
                    root))
    (let [current (some-> (run-command! ["git" "rev-parse" "HEAD"] target)
                          :output .trim)]
      (when-not (= current commit)
        (run-command! ["git" "fetch" "--depth" "1" "origin" commit] target)
        (run-command! ["git" "checkout" "--detach" commit] target)))
    target))

(defn ensure!
  []
  (into {} (map (fn [name] [name (checkout! name)]) (keys dependencies))))
