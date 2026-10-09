(ns aguafria.zig.source-scanner-test
  (:require [aguafria.zig.runtime :as runtime]
            [aguafria.zig.source-scanner :as scanner]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest native-source-uses-its-namespace-path
  (let [source (io/resource "aguafria/native_tools/source_scanner.clj")]
    (is (some? source))
    (is (= 'aguafria.native-tools.source-scanner
           (second (read-string (slurp source)))))))

(deftest tokenizer-facts-and-byte-spans-preserve-source
  (let [source (str "// π🙂 __aguafria_a @import(\"root\")\n"
                    "const text = \"extern export __aguafria_a\";\n"
                    "const lines = \\\\__aguafria_a @import(\"root\")\n;\n"
                    "const @\"__aguafria_a\" = 1;\n"
                    "export fn __aguafria_a() void { __aguafria_a(); }\n"
                    "const actual = @import( // comment\n\"root\",);\n"
                    "const std = @import(\"std\");\n")
        facts (scanner/analyze! source)
        expected (-> source
                     (str/replace "export fn __aguafria_a() void { __aguafria_a(); }"
                                  "export fn pack_a() void { pack_a(); }")
                     (str/replace "\"root\",);" "\"handler_root\",);"))]
    (is (= ["__aguafria_a"] (:exports facts)))
    (is (false? (:external-exports? facts)))
    (is (false? (:dynamic-exports? facts)))
    (is (false? (:external-declaration-syntax? facts)))
    (is (= [["import" "\"root\""] ["import" "\"std\""]] (:imports facts)))
    (is (= 2 (count (:identifier-spans facts))))
    (is (= expected (scanner/rewrite source facts {"__aguafria_a" "pack_a"} "handler_root")))))

(deftest dynamic-imports-and-exports-do-not-look-like-owned-declarations
  (let [sources ["comptime { @export(&f, .{.name = \"f\"}); }"
                 "export fn application() void {}"
                 "extern fn application() void;"
                 "const x = @import(\"root\" ++ \".zig\");"
                 "const x = @embedFile(@import(\"std\").name);"
                 "const x = @import(if (true) \"root\" else \"std\");"]
        facts (scanner/analyze-many! sources)]
    (is (true? (:dynamic-exports? (nth facts 0))))
    (is (empty? (:exports (nth facts 0))))
    (is (true? (:external-exports? (nth facts 1))))
    (is (true? (:external-declaration-syntax? (nth facts 2))))
    (is (= [["import" nil]] (:imports (nth facts 3))))
    (is (= [["embedFile" nil] ["import" "\"std\""]] (:imports (nth facts 4))))
    (is (= [["import" nil]] (:imports (nth facts 5))))
    (doseq [index [3 4 5]]
      (is (= (nth sources index)
             (scanner/rewrite (nth sources index) (nth facts index) {} "other_root"))))))

(deftest native-resource-limits-and-invalid-tokens-fail-explicitly
  (is (empty? (scanner/analyze-many! [])))
  (is (= 16 (count (scanner/analyze-many! (repeat 16 "")))))
  (let [status (fn [source]
                 (try (scanner/analyze! source) nil
                      (catch clojure.lang.ExceptionInfo error (:status (ex-data error)))))]
    (is (= 3 (status (apply str (map #(str "export fn __aguafria_" % "() void {}\n")
                                     (range 65))))))
    (is (= 4 (status (str (char 1)))))
    (is (= 4 (status (str (char 0) "const x = 1;"))))
    (is (= 6 (status (apply str (repeat 1025 "const x = @import(\"std\");\n")))))
    (is (= 7 (status (apply str (repeat 16385 "__aguafria_a;\n"))))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds capacity"
                        (scanner/analyze-many! (repeat 17 ""))))
  ;; Capacity failures do not poison the reusable native workspace.
  (is (= ["__aguafria_valid"]
         (:exports (scanner/analyze! "export fn __aguafria_valid() void {}")))))

(deftest scanning-is-thread-safe-and-does-not-register-a-tool-in-the-application
  (let [snapshot #(into {} (map (fn [module] [module (runtime/registered-declarations module)]))
                        (runtime/registered-modules))
        before (snapshot)
        source "const x = @import(\"root\"); export fn __aguafria_a() void {}"
        expected (scanner/analyze! source)]
    (is (every? #(= expected %)
                (mapv deref (repeatedly 24 #(future (scanner/analyze! source))))))
    (is (= (#'scanner/program-source) (#'scanner/program-source)))
    (is (= before (snapshot)))))
