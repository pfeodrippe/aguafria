(require '[aguafria.keyword :as k]
         '[aguafria.zig :as a]
         '[aguafria.zig.explain :as explain]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str])

(import '[java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
          MemoryLayout SymbolLookup ValueLayout]
        '[java.nio.file Files CopyOption]
        '[java.util ArrayList])

(let [events (atom [])
      started (System/nanoTime)]
  (binding [explain/*reporter* #(swap! events conj %)]
    (with-open [left (k/u32 3) right (k/u32 4) result (k/+ left right)]
      (assert (= 7 (a/value result)))))
  (let [template (some #(when (and (:path %)
                                   (str/starts-with? (:module %) "aguafria.jvm.expression-"))
                          (:path %))
                       (reverse @events))
        _ (assert template "The ordinary JVM call must load a real adapter")
        sections (shell/sh "otool" "-l" template)
        symbols (shell/sh "nm" "-gU" template)
        _ (assert (zero? (:exit sections)) (:err sections))
        _ (assert (zero? (:exit symbols)) (:err symbols))
        _ (assert (not (str/includes? (:out sections) "__thread_vars"))
                  "JVM-only adapters must not allocate per-image TLS")
        getter (second (re-find #"(?m)^\S+\s+T\s+_(__aguafria_[0-9a-f]+_active_call_count)$"
                                (:out symbols)))
        _ (assert getter "The adapter must retain its active-call safety counter")
        directory (Files/createTempDirectory
                   (.toPath (doto (io/file ".tmp") .mkdirs))
                   "adapter-images-" (make-array java.nio.file.attribute.FileAttribute 0))
        image-count 640
        paths (mapv #(.resolve directory (str "adapter-" % ".dylib")) (range image-count))
        linker (Linker/nativeLinker)
        descriptor (FunctionDescriptor/of ValueLayout/JAVA_LONG (make-array MemoryLayout 0))]
    (with-open [arena (Arena/ofShared)]
      (let [addresses
            (mapv (fn [index]
                    ;; Distinct copies load distinct images without compiling
                    ;; hundreds of equivalent handlers just to test dyld's limit.
                    (let [path (nth paths index)
                          _ (Files/copy (.toPath (io/file template)) path
                                        (make-array CopyOption 0))
                          lookup (SymbolLookup/libraryLookup path arena)
                          address (.orElseThrow (.find lookup getter))
                          handle (.downcallHandle linker address descriptor
                                                  (make-array Linker$Option 0))]
                      (assert (zero? (long (.invokeWithArguments handle (ArrayList.)))))
                      (.address address)))
                  (range image-count))]
        (assert (= image-count (count (set addresses)))
                "Every lookup must resolve to a separately loaded native image")
        (prn {:status :passed :loaded-images image-count
              :unique-symbol-addresses (count (set addresses))
              :tls-sections 0 :active-counter getter
              :copy-bytes (* image-count (.length (io/file template)))
              :directory (str directory)
              :duration-ms (/ (- (System/nanoTime) started) 1e6)})))
    ;; The arena has released every test-owned image before removing its copy.
    (assert (not (Files/isSymbolicLink directory)))
    (with-open [entries (Files/list directory)]
      (assert (= (set paths) (set (iterator-seq (.iterator entries))))))
    (doseq [path paths]
      (assert (not (Files/isSymbolicLink path)))
      (assert (Files/isRegularFile path
                                   (into-array java.nio.file.LinkOption
                                               [java.nio.file.LinkOption/NOFOLLOW_LINKS]))))
    (doseq [path paths] (Files/delete path))
    (Files/delete directory)))
(shutdown-agents)
