(ns aguafria.zig.prepared-rejection-test
  (:require [aguafria.zig.rejection :as rejection]
            [aguafria.zig.toolchain :as toolchain]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(defn- fixture []
  (let [directory (str (Files/createTempDirectory "aguafria-rejection-contract-"
                                                 (make-array java.nio.file.attribute.FileAttribute 0)))
        path #(str (io/file directory %))
        root (path "root.zig") source (path "module.zig") authored (path "lesson.clj")
        support (path "support.dylib") support-source (path "support.zig") guard (path "guard.c")
        command [(toolchain/executable) "build-lib" "-dynamic" (str "-femit-bin=" (path "module.dylib"))
                 support "-Odebug" "--dep" "lesson" (str "-Mroot=" root) (str "-Mlesson=" source)]]
    (doseq [[file content] [[root "const owner = @import(\"lesson\");\n"]
                           [source "pub fn bad() void { const n: u32 = 1; n += 1; }\n"]
                           [authored "(ns lesson)\n"] [support "immutable native support"]
                           [support-source "support source"] [guard "guard source"]]]
      (spit file content))
    {:directory directory :authored authored :source source :support support
     :plan {:module-name "lesson" :command command :module-dir (io/file directory)
            :library-file (io/file directory "module.dylib") :rejection-context? true
            :rejection-source-files [authored] :artifact {}
            :compiler-options {:cache-safe? true :optimize "debug"}
            :panic-support {:path support :hash "support-identity"
                            :source "support source" :source-file (io/file support-source)
                            :guard-source "guard source" :guard-file (io/file guard)}}
     :result {:exit 1 :out "" :err "module.zig:1:45: error: cannot assign to constant\n"
              :command command :directory directory}
     :diagnostics [{:severity :error :message "cannot assign to constant" :file source :line 1
                    :aguafria/source {:file authored :line 2 :column 1}}]}))

(defn- frozen [plan]
  (assoc plan :rejection-inputs (rejection/freeze-inputs plan)))

(deftest separate-receipt-round-trip-and-integrity
  (let [{:keys [directory plan result diagnostics source authored]} (fixture)
        plan (frozen plan) recipe (rejection/recipe plan)
        receipt (rejection/publish! directory recipe result diagnostics)
        file (io/file directory "prepared-rejections" (str (rejection/recipe-id recipe) ".edn"))]
    (is (some? recipe))
    (is (= receipt (rejection/find-verified directory recipe)))
    (is (not (.exists (:library-file plan))))
    (is (not (.exists (io/file directory "bundles"))))
    (spit file (pr-str (assoc-in receipt [:compiler-result :err] "tampered")))
    (is (nil? (rejection/find-verified directory recipe)))
    (spit file (pr-str (assoc receipt :schema :unknown)))
    (is (nil? (rejection/find-verified directory recipe)))
    (spit file "{")
    (is (nil? (rejection/find-verified directory recipe)))
    (spit file (pr-str receipt))
    (spit (:library-file plan) "conflicting positive library")
    (is (nil? (rejection/find-verified directory recipe)))
    (spit source "// changed source, even if positive normalization ignored this comment\n" :append true)
    (is (nil? (rejection/recipe plan)))
    (is (nil? (rejection/find-verified directory recipe)))
    (is (contains? (get-in recipe [:inputs :files]) authored))))

(deftest source-path-configuration-and-support-identity-are-not-normalized-away
  (let [{:keys [directory plan authored support]} (fixture)
        plan (frozen plan) recipe (rejection/recipe plan)
        copy (str (io/file directory "other-name.clj"))]
    (spit copy (slurp authored))
    (is (nil? (rejection/recipe (assoc plan :rejection-source-files [copy]))))
    (is (nil? (rejection/recipe (assoc-in plan [:compiler-options :optimize] "fast"))))
    (is (nil? (rejection/recipe (assoc-in plan [:panic-support :hash] "changed"))))
    (spit support "different support bytes")
    (is (not= recipe (rejection/recipe plan)))
    (spit (get-in plan [:panic-support :source-file]) "changed generated support source")
    (is (nil? (rejection/recipe plan)))))

(deftest compiler-bytes-and-manifest-identity-invalidate-a-receipt
  (let [{:keys [directory plan result diagnostics]} (fixture)
        executable (str (io/file directory "compiler-identity-fixture"))
        information (assoc (toolchain/information) :executable executable)
        plan (assoc-in plan [:command 0] executable)
        result (assoc-in result [:command 0] executable)]
    (spit executable "unit-test compiler identity; never executed")
    (with-redefs [toolchain/information (constantly information)]
      (let [plan (frozen plan) recipe (rejection/recipe plan)]
        (is (some? (rejection/publish! directory recipe result diagnostics)))
        (with-redefs [toolchain/information (constantly (assoc information :version "different"))]
          (is (nil? (rejection/recipe plan))))
        (spit executable "changed executable bytes")
        (is (nil? (rejection/recipe plan)))
        (is (nil? (rejection/find-verified directory recipe)))))))

(deftest only-completed-ordinary-mapped-source-rejections-are-publishable
  (let [{:keys [directory plan result diagnostics]} (fixture)
        recipe (rejection/recipe (frozen plan))]
    (doseq [bad [(assoc result :exit 0) (assoc result :exit 137)
                 (dissoc result :out) (assoc result :directory "other")
                 (assoc-in result [:command 1] "test")
                 (assoc-in result [:command 3] "-fno-emit-bin")]]
      (is (nil? (rejection/publish! directory recipe bad diagnostics))))
    (doseq [bad [[] [(assoc (first diagnostics) :message "another error")]
                 [(dissoc (first diagnostics) :aguafria/source)]
                 [(dissoc (first diagnostics) :file)]
                 [(assoc-in (first diagnostics) [:aguafria/source :file] "other.clj")]]]
      (is (nil? (rejection/publish! directory recipe result bad))))
    (is (nil? (rejection/find-verified directory recipe)))
    (doseq [bad [(assoc plan :rejection-context? false)
                 (assoc-in plan [:artifact :native-test-context?] true)
                 (assoc-in plan [:artifact :jvm-adapter?] true)
                 (assoc-in plan [:command 1] "test-obj")
                 (update plan :command conj "-lc")
                 (assoc plan :rejection-source-files ["relative.clj"])]]
      (is (nil? (rejection/freeze-inputs bad))))))
