(ns learn.reference-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [learn.inline :as inline]
            [learn.reference :as ref]))

(deftest inline-references-use-real-catalog-and-emitter
  (let [catalog (inline/references)]
    (doseq [[zig aguafria] [["u8" ":u8"] ["i114" ":i114"]
                           ["undefined" "ak/undefined"] ["null" "ak/null"]
                           ["@memcpy" "ak/memcpy"] ["a_len" "a_len"]]]
      (is (= aguafria (:clojure-source (inline/equivalent catalog zig)))))
    (is (= "ak/memcpy"
           (:clojure-source
            (inline/equivalent catalog "@memcpy(noalias dest, noalias source) void"))))
    (testing "Keyword/builtin name collisions must not resolve to the wrong Var"
      (is (not= (:clojure-source (inline/equivalent catalog "extern"))
                (:clojure-source (inline/equivalent catalog "@extern")))))
    (testing "Calls and unknown syntax are not relabeled as catalog references"
      (is (nil? (inline/equivalent catalog "@memcpy(dest, source)")))
      (is (nil? (inline/equivalent catalog "@unknownBuiltin")))
      (is (nil? (inline/equivalent catalog "a + b"))))))

(deftest inline-html-pairing-preserves-the-whole-published-reference
  (let [snippets (filterv #(= "syntax" (:kind %)) (:snippets (ref/inventory)))
        translations (inline/translate snippets)
        original (slurp "resources/upstream/index.html")
        annotated (inline/annotate original translations ref/decode-html ref/escape-html)
        drift (first (filter :upstream-difference translations))]
    (is (= 1362 (count (:matched-ids annotated))))
    (is (= (mapv :id snippets) (:matched-ids annotated)))
    (is (empty? (:unmatched annotated)))
    (is (= (re-seq #"(?s)<figure>.*?</figure>" original)
           (re-seq #"(?s)<figure>.*?</figure>" (:html annotated))))
    (is (= "std.debug.dumpErrorReturnTrace" (:source drift)))
    (is (= "std.debug.dumpStackTrace" (:rendered-source drift)))
    (is (= :translated (:status drift)))
    (is (= "aguafria.std.debug/dumpStackTrace" (:clojure-source drift)))
    (is (every? #(not= :zig-only (:status %)) translations))))

(deftest inline-html-does-not-guess-or-interpret-markup
  (let [snippet {:id "test" :source "<x>" :status :reference-mapped
                 :clojure-source "(ak/< x 1)" :note "Syntax only"}
        html "<figure><code>&lt;x&gt;</code></figure><code>&lt;x&gt;</code>"
        annotated (inline/annotate html [snippet] ref/decode-html ref/escape-html)]
    (is (= ["test"] (:matched-ids annotated)))
    (is (str/includes? (:html annotated) "(ak/&lt; x 1)"))
    (is (str/includes? (:html annotated) "data-language=\"aguafria\" hidden"))
    (is (str/includes? (:html annotated) "aria-describedby=\"learn-test-note\""))
    (is (= [snippet]
           (:unmatched (inline/annotate "<code>different</code>" [snippet]
                                        ref/decode-html ref/escape-html))))))

(deftest pinned-complete-inventory
  (let [{:keys [examples code-references sections snippets]} (ref/inventory)]
    (is (= 292 (count examples)))
    (is (= (set (map :file examples)) (set code-references)))
    (is (= 356 (count sections)))
    (is (= 1402 (count snippets)))
    (is (every? :manifest examples))
    (is (every? #(= :pending (:status %))
                (remove #(= "shell_samp" (:kind %)) snippets)))))

(deftest manifest-preserves-repeated-options
  (let [source "const x = 1;\n\n// obj\n// additional_option=-fno-entry\n// additional_option=--export=x\n"
        manifest (ref/manifest source)]
    (is (= "obj" (:kind manifest)))
    (is (= ["additional_option=-fno-entry" "additional_option=--export=x"]
           (:options manifest)))
    (is (str/ends-with? source (:text manifest)))))

(deftest html-preserves-the-entire-original-document
  (ref/build!)
  (let [original (slurp "resources/upstream/index.html")
        output (slurp "build/site/index.html")]
    (doseq [pattern [#"(?s)<h[1-6]\b.*?</h[1-6]>"
                     #"(?s)<figure>.*?</figure>"]]
      (is (= (vec (re-seq pattern original))
             (vec (re-seq pattern output)))))
    (testing "Removing only our additions recovers every byte of the original HTML"
      (is (= (ref/text-sha256 original)
             (ref/text-sha256 (ref/original-document output)))))
    (testing "No editorial banners or annotations are inserted into the documentation"
      (is (not (str/includes? output "learn-notice")))
      (is (not (str/includes? output "learn-status")))
      (is (not (str/includes? output "learn-context"))))
    (is (str/includes? output "code class=\"language-clojure\""))
    (is (str/includes? output "window.Prism = {manual: true}"))
    (is (str/includes? output "aria-controls=\"learn-example-1-a\""))
    (is (str/includes? output "aria-controls=\"learn-example-1-z learn-example-1-a\""))
    (is (str/includes? output ">Side by side</button>"))
    (is (str/includes? output "id=\"learn-example-1-a\" aria-labelledby=\"learn-example-1-at\" hidden"))))

(deftest original-document-check-detects-rewritten-prose-and-shell-output
  (let [original (str "<p>The original explanation.</p>"
                      "<figure><figcaption class=\"shell-cap\">Shell</figcaption>"
                      "<pre><samp>$ zig test example.zig\nAll 1 tests passed.\n</samp></pre>"
                      "</figure>")
        output (ref/example-panel [original "example.zig"] nil 1)]
    (is (= original (ref/original-document output)))
    (doseq [[before after] [["original explanation" "rewritten explanation"]
                            ["$ zig test" "$ clojure"]
                            ["All 1 tests passed." "All 2 tests passed."]]]
      (is (not= original (ref/original-document (str/replace output before after)))))))

(deftest markup-does-not-interpret-code-as-html
  (is (= "&lt;script&gt;&amp;&quot;" (ref/escape-html "<script>&\"")))
  (let [markup (ref/example-panel ["<figure>original</figure>" "a.zig"] nil 1)]
    (is (str/includes? markup "Translation pending"))
    (is (str/includes? markup "not a ZIG_ONLY exclusion"))
    (is (str/includes? markup "aria-selected=\"true\">Zig"))))

(deftest strict-verification-requires-complete-current-evidence
  (let [catalog {:examples [{:file "example.zig"}]
                 :snippets [{:id "block" :kind "syntax_block"}
                            {:id "inline" :kind "syntax"}]}
        translations [{:file "example.zig" :status :translated
                       :verification :output-matched :repl-transcript {:stdout "actual"}}]
        blocks [{:id "block" :status :translated :verification :syntax-roundtrip-passed
                 :context-review "The original is an incomplete illustration without output."}]
        inlines [{:id "inline" :status :translated :verification :output-matched}]
        overrides {"example.zig" {:source "learn/example/example.clj"}}
        inputs [catalog translations blocks inlines overrides]]
    (is (empty? (apply ref/acceptance-problems inputs)))
    (doseq [[path value expected]
            [[[1] [] :missing-file-translations]
             [[1 0 :status] :compiler-gap :unresolved-translations]
             [[1 0 :verification] nil :unverified-file-outcomes]
             [[1 0 :verification] :output-mismatch :unverified-file-outcomes]
             [[1 0 :repl-transcript] nil :missing-repl-transcripts]
             [[2] [] :missing-block-translations]
             [[2 0 :verification] nil :unverified-block-translations]
             [[2 0 :context-review] nil :unverified-block-translations]
             [[3] [] :missing-inline-translations]
             [[3 0 :verification] :emission-checked :unverified-inline-translations]
             [[3 0 :status] :pending :unverified-inline-translations]
             [[4] {} :hand-written-examples-pending]]]
      (is (some #{expected} (apply ref/acceptance-problems (assoc-in inputs path value)))
          (str path " must fail as " expected)))))

(deftest handwritten-lessons-do-not-regress-to-converter-binding-names
  (doseq [[file {:keys [source]}] (ref/read-edn "resources/learn/overrides.edn")
          :when source
          :let [code (slurp (clojure.java.io/resource source))]]
    (testing file
      (is (nil? (re-find #"zig-[a-z-]+-[0-9a-f]{12,}" code)))
      (is (nil? (re-find #"\(ak/(?:const|var)\s" code)))
      (is (not (str/includes? code ":zig/test-name")))
      (is (not (str/includes? code ":explicit-return")))
      (is (not (str/includes? code "Generated from")))
      (is (nil? (re-find #"\(az/(?:defn-?|deftest)\s*\n" code)))
      (is (nil? (re-find #"\(az/deftest\s+\"" code))))))

(deftest named-map-initializers-use-constructors
  (doseq [file (file-seq (io/file "resources/learn"))
          :when (and (.isFile file) (str/ends-with? (str file) ".clj"))]
    (with-open [reader (java.io.PushbackReader. (io/reader file))]
      (binding [*read-eval* false]
        (loop []
          (let [form (read {:eof ::eof} reader)]
            (when-not (= ::eof form)
              (doseq [node (tree-seq coll? seq form)
                      :when (and (seq? node) (= 'let (first node)))
                      [binding value] (partition 2 (second node))
                      :let [type (or (:zig/type (meta binding))
                                     (:var (meta binding)))]]
                (is (not (and (symbol? type) (map? value)))
                    (str file ": use (" type " ...) for " binding)))
              (recur))))))))

(deftest authored-examples-and-snippets-use-standard-clojure-namespaces
  (let [files (for [[file {:keys [source]}] (ref/read-edn "resources/learn/overrides.edn")
                    :when source]
                [source file "example"])
        fragments (ref/read-edn "resources/learn/fragment-overrides.edn")
        blocks (for [{:keys [id attributes]} (:snippets (ref/inventory))
                     :let [source (:source (get fragments id))]
                     :when source]
                 [source (second (str/split attributes #"\|" 2)) "snippet"])]
    (is (= 305 (count (concat files blocks))))
    (doseq [[group entries] [["example" files] ["snippet" blocks]]
            :let [declared (set (map (comp #(.getName %) io/file first) entries))
                  on-disk (filter #(.isFile %)
                                  (file-seq (io/file "resources/learn" group)))]]
      (is (= declared (set (map #(.getName %) on-disk)))))
    (doseq [[source original group] (concat files blocks)
            :let [code (slurp (io/resource source))
                  form (read-string code)
                  basename (.getName (io/file source))]]
      (testing source
        (is (= 'ns (first form)))
        (is (= (str "learn/" group) (.getParent (io/file source))))
        (is (re-matches #"[A-Za-z0-9_]+\.clj" basename))
        (is (= source
               (str (-> (str (second form))
                        (str/replace "." "/")
                        (str/replace "-" "_")) ".clj")))
        (when (str/ends-with? original ".zig")
          (is (= (str (-> original
                          (str/replace #"\.zig$" "")
                          (str/replace #"[^A-Za-z0-9_]" "_")) ".clj")
                 basename)))
        (is (= (symbol (str "learn." group "."
                            (-> basename
                                (str/replace #"\.clj$" "")
                                (str/replace #"[^A-Za-z0-9-]+" "-"))))
               (second form)))
        (is (not (str/includes? code "Converted from ")))
        (is (not (str/includes? code ":zig/qualifiers \"!\"")))
        (let [requires (mapcat rest (filter #(and (seq? %) (= :require (first %)))
                                           (drop 2 form)))
              libraries (mapv #(if (vector? %) (first %) %) requires)]
          (doseq [library libraries
                  :when (str/starts-with? (str library) "aguafria.std.")]
            (is (some? (io/resource (str (str/replace (str library) "." "/") ".clj")))
                "Every nested std import must have a real classpath entry point."))))))
  (is (str/starts-with?
       (nth (read-string (slurp (io/resource "learn/example/tldoc_comments.clj"))) 2)
       "This module provides functions")))

(deftest aguafria-panel-shows-its-actual-source-filename
  (let [path "resources/learn/example/test_comptime_variables.clj"
        translation {:status :translated :clojure-path path
                     :authored-source "learn/example/test_comptime_variables.clj"}
        panel (ref/example-panel ["<figure>original</figure>" "test_comptime_variables.zig"]
                                 translation 1)]
    (is (str/includes? panel
                      "<figcaption class=\"clojure-cap\"><cite class=\"file\">test_comptime_variables.clj</cite></figcaption>"))
    (is (str/includes? panel "(ns learn.example.test-comptime-variables"))
    (is (not (str/includes? panel "Converted from")))
    (is (= "<figure>original</figure>" (ref/original-document panel)))
    (is (str/includes? (ref/example-panel ["original" "file.zig"]
                                         (assoc translation :authored-source "example/<file>.clj") 1)
                       "&lt;file&gt;.clj</cite>"))))

(deftest examples-show-code-without-work-log-comments
  (doseq [file ["draft.zig" "hello.zig"]
          verification [:pending :output-matched :diagnostics-matched
                        :compile-only-matched :syntax-roundtrip-passed]]
    (let [markup (ref/example-panel ["<figure>original</figure>" file]
                                    {:status :translated
                                     :verification verification
                                     :clojure-path "resources/learn/example/hello.clj"}
                                    1)]
      (is (not (str/includes? markup "learn-status")))
      (is (not (str/includes? markup "learn-authorship")))
      (is (not (str/includes? markup "pending")))
      (is (not (str/includes? markup "Hand-written")))
      (is (str/includes? markup "language-clojure")))))

(deftest example-names-are-readable-and-unique
  (is (= "test-unresolved-comptime-value"
         (ref/example-id "test_unresolved_comptime_value.zig")))
  (let [names (map (comp ref/example-id :file) (:examples (ref/inventory)))]
    (is (= (count names) (count (set names))))
    (is (every? #(re-matches #"[A-Za-z][A-Za-z0-9-]*" %) names))))

(deftest displayed-examples-do-not-contain-converter-boilerplate
  (doseq [{:keys [file clojure-path]} (ref/read-edn "build/translations.edn")
          :when clojure-path]
    (let [code (slurp clojure-path)]
      (testing file
        (is (not (str/includes? code "Generated from")))
        (is (not (str/includes? code "Edit and reevaluate")))
        (is (not (str/includes? code ":explicit-return")))
        (is (nil? (re-find #"learn\.example\.[^\s)]+_[0-9a-f]{8}" code)))))))

(deftest verified-examples-show-code-without-success-badges
  (let [markup (ref/example-panel ["<figure>original</figure>" "hello.zig"]
                                  {:status :translated
                                   :verification :output-matched
                                   :clojure-path "resources/learn/example/hello.clj"}
                                  1)]
    (is (not (str/includes? markup "learn-status")))
    (is (not (str/includes? markup "learn-authorship")))
    (is (str/includes? markup "language-clojure"))))

(deftest exact-displayed-clojure-is-evaluated
  (let [namespace-symbol 'learn.example.displayed-source-test
        source (str "(ns " namespace-symbol " (:require [aguafria.zig :as az]))\n"
                    "(az/defconst answer 42)")
        emitted (ref/emit-clojure source namespace-symbol {})]
    (is (str/includes? emitted "const answer = 42;"))
    (is (nil? (find-ns namespace-symbol)))
    (is (not (contains? (loaded-libs) namespace-symbol)))
    (is (thrown? Exception
                 (ref/emit-clojure (str source "\n(no-such-macro)") namespace-symbol {})))
    (is (nil? (find-ns namespace-symbol)))
    (is (not (contains? (loaded-libs) namespace-symbol)))))

(deftest a-verified-lesson-can-be-required-and-called-normally
  (let [lesson 'learn.example.test-integer-pointer-conversion
        source (slurp (io/resource "learn/example/test_integer_pointer_conversion.clj"))
        result (atom nil)]
    (ref/emit-clojure source lesson {})
    (try
      (require lesson)
      (is (nil? (:doc (meta (the-ns lesson)))))
      (let [test-var (ns-resolve lesson 'integer-pointer-conversion-test)
            output (with-out-str
                     (binding [*err* *out*]
                       (reset! result (test-var))))]
        (is (fn? (var-get test-var)))
        (is (= :passed (:status @result)))
        (is (= 0 (:exit @result)))
        (is (str/includes? output "integer-pointer-conversion-test...OK"))
        (is (str/includes? output "All 1 tests passed.")))
      (finally
        (remove-ns lesson)
        (dosync (alter @#'clojure.core/*loaded-libs* disj lesson))))))

(deftest lessons-load-and-run-in-a-clean-jvm
  (let [code '(do
                (assert (nil? (find-ns 'aguafria.std)))
                (require 'learn.example.hello)
                (load-file "resources/learn/example/hello.clj")
                (require '[aguafria.zig.host :as host])
                (assert (= [:error-union :void]
                           (get-in (meta #'learn.example.hello/main)
                                   [:aguafria/declaration :return])))
                (let [result (host/await! (host/start! #'learn.example.hello/main []))]
                  (assert (= 0 (:exit-code result)) (pr-str result)))
                (require 'learn.example.hello-again)
                (learn.example.hello-again/main)
                (require 'learn.example.test-integer-pointer-conversion)
                (assert (= :passed
                           (:status (learn.example.test-integer-pointer-conversion/integer-pointer-conversion-test))))
                (println "fresh-lesson-repl-passed")
                (shutdown-agents))
        command [(str (io/file (System/getProperty "java.home") "bin" "java"))
                 "--enable-native-access=ALL-UNNAMED"
                 "-cp" (System/getProperty "java.class.path")
                 "clojure.main" "-e" (pr-str code)]
        process (.start (.redirectErrorStream (ProcessBuilder. ^java.util.List command) true))
        output (future (slurp (.getInputStream process)))
        finished? (.waitFor process 120 java.util.concurrent.TimeUnit/SECONDS)]
    (when-not finished?
      (.destroyForcibly process))
    (is finished? "Clean lesson process must finish without reference-runner bootstrap.")
    (let [text (deref output 5000 "Timed out collecting clean REPL output")]
      (is (and finished? (zero? (.exitValue process))) text)
      (is (str/includes? text "Hello, World!") text)
      (is (str/includes? text "fresh-lesson-repl-passed") text))))

(deftest conversion-drafts-do-not-change-handwritten-namespace-defaults
  (doseq [file ["comments.zig" "base64.zig"]
          :let [example (first (filter #(= file (:file %)) (:examples (ref/inventory))))
                translation (ref/translate-one! example)
                emitted (slurp (str "build/emitted/" file))]]
    (is (= :translated (:status translation)))
    (is (false? (aguafria.zig.project/converted-module?
                 (symbol (str "learn.example." (str/replace file #"\.zig$" ""))))))
    (if (= file "comments.zig")
      (is (str/includes? emitted "pub fn main()"))
      (is (str/includes? emitted "return decoded_length;")))))

(deftest larger-blocks-have-exact-source-syntax-checks
  (let [block (first (filter #(= "snippet-646" (:id %))
                            (:snippets (ref/inventory))))
        result (ref/translate-block! block)]
    (is (= :translated (:status result)))
    (is (= :output-matched (:verification result)))
    (is (= :shared-context-fixture (:verification-scope result)))
    (is (= :output-matched (get-in result [:comparison :status])))
    (is (= "learn/snippet/performFn_3.clj" (:authored-source result)))
    (is (= (:source-sha256 block) (:source-sha256 result)))
    (is (= (:clojure-sha256 result) (ref/sha256 (:clojure-path result))))
    (is (= (:emitted-sha256 result) (ref/sha256 (:emitted-path result))))
    (let [markup (ref/example-panel ["<figure>original</figure>" (:file result)] result 999)]
      (is (not (str/includes? markup "learn-status")))
      (is (not (str/includes? markup "learn-repl"))))))

(deftest block-failure-cannot-become-a-zig-only-exclusion
  (let [source "pub fn invalid("
        result (ref/translate-block! {:id "test-invalid-block"
                                      :attributes "zig|invalid.zig"
                                      :source source
                                      :source-sha256 (ref/text-sha256 source)})]
    (is (= :compiler-gap (:status result)))
    (is (string? (:error result)))))

(deftest context-blocks-preserve-other-languages
  (doseq [language ["c" "javascript" "peg"]]
    (let [result (ref/translate-block! {:id "context-test"
                                      :attributes (str language "|host")
                                      :source "original host code"})]
      (is (= :zig-only (:status result)))
      (is (str/starts-with? (:reason result) "ZIG_ONLY:")))))

(deftest stale-block-source-is-not-presented-as-verified
  (let [catalog (ref/inventory)
        results (ref/current-blocks catalog)
        changed (update catalog :snippets
                        (fn [snippets]
                          (mapv #(assoc % :source-sha256 "changed") snippets)))]
    ;; This check does not require generated reports to exist in a clean checkout.
    (is (every? :source-sha256 results))
    (is (empty? (ref/current-blocks changed)))))

(deftest output-comparison-keeps-real-program-data
  (let [html (str "<pre><code>source is not output</code></pre>"
                  "<samp>$ <kbd>zig build-exe example.zig</kbd>\n"
                  "compiler trace\n$ <kbd>./example</kbd>\n"
                  "&lt;result&gt; 42 &amp;amp; &#x394;\n</samp>")]
    (is (= "<result> 42 &amp; Δ\n" (ref/execution-output html)))
    (is (= :observation-mismatch
           (:status (ref/compare-observations "exe=succeed" "value=42\n" "value=43\n")))))
  (is (= :observation-mismatch
         (:status (ref/compare-observations "test" "All 2 tests passed.\n"
                                           "All 0 tests passed.\n")))))

(deftest matching-exit-codes-do-not-hide-output-mismatches
  (doseq [[original converted expected]
          [["value=42\n" "value=42\n" :upstream-outcome-passed]
           ["value=42\n" "value=43\n" :failed]
           ["value=42\n" "" :failed]]]
    (with-redefs [ref/run-doctest! (constantly {:exit 0})
                  ref/capture-example-repl! (constantly {:value {:exit 0}})
                  ref/capture-comment-repl! (constantly {:evaluations [{:printed-value "nil"}]})
                  ref/compare-doctest-output
                  (fn [_ _] (ref/compare-observations "exe=succeed" original converted))
                  ref/write-text! (fn [& _])
                  ref/sha256 (constantly "test-hash")
                  clojure.core/slurp (constantly "(ns example) (comment (main))")]
      (let [result (ref/verify-example! {}
                                      {:file "example.zig" :manifest {:kind "exe=succeed"}}
                                      {:status :translated :clojure-path "example.clj"})]
        (is (= expected (:status result)))
        (is (= original (get-in result [:comparison :original])))
        (is (= converted (get-in result [:comparison :aguafria])))))))

(deftest outcome-command-fails-after-saving-all-comparison-reports
  (let [writes (atom [])
        failure {:file "example.zig" :status :failed
                 :comparison {:status :observation-mismatch}}]
    (with-redefs [ref/prepare-example-context! (constantly {:tool {} :translations {}})
                  ref/verify-example! (fn [& _] failure)
                  ref/write-edn! (fn [path value] (swap! writes conj [path value]))]
      (with-out-str
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Reference outcome comparison failed"
                              (ref/verify-outcomes! [{:file "example.zig"}])))))
    (is (= [["build/outcomes/example.edn" failure]
            ["build/outcomes.edn" [failure]]]
           @writes))))

(deftest diagnostic-comparison-checks-the-reason-for-failure
  (is (= :diagnostics-matched
         (:status (ref/compare-observations "exe=fail"
                                           "thread 123 panic: integer overflow\nold.zig:2:3\n"
                                           "thread 456 panic: integer overflow\nnew.zig:8:9\n"))))
  (is (= :observation-mismatch
         (:status (ref/compare-observations "test_error="
                                           "x.zig:1:2: error: division by zero\n"
                                           "x.zig:7:2: error: use of undeclared identifier 'oops'\n"))))
  (is (= :diagnostic-review-required
         (:status (ref/compare-observations "exe=fail" "" ""))))
  (is (= ["integer overflow"] (ref/failure-messages "Panic! integer overflow\n")))
  (is (= ["1 tests leaked memory."]
         (ref/failure-messages "1 tests leaked memory.\nerror: the following test command failed\n")))
  (is (= ["invalid error code"]
         (ref/failure-messages
           (str "thread 42 panic: invalid error code\n"
                "sample.zig:3:4: 0x1234 in cast_error\n"
                "    const casted_error: Set2 = @errorCast(@\"error\");\n"
                "    const text = \"panic: this is echoed source\";\n"))))
  (is (= ["integer overflow"]
         (ref/failure-messages "1/1 sample.test.overflows...thread 42 panic: integer overflow\n")))
  (is (= ["TestUnexpectedResult)"]
         (ref/failure-messages "1/1 sample.test.check...FAIL (TestUnexpectedResult)\n"))))

(deftest test-label-normalization-preserves-results-and-program-output
  (let [normalize #(ref/normalize-test-runner-labels "sample" %1 %2)
        left [{:kind :named :label "test.address of syntax"}
              {:kind :unnamed :label "test_1"}]
        right [{:kind :named :label "test.address-of-syntax-test"}
               {:kind :named :label "test.empty-block-test"}]
        original (str "1/2 sample.test.address of syntax...OK\n"
                      "2/2 sample.test_1...OK\nAll 2 tests passed.\n")
        converted (str "1/2 sample.test.address-of-syntax-test...OK\n"
                       "2/2 sample.test.empty-block-test...OK\nAll 2 tests passed.\n")]
    (is (= (normalize left original) (normalize right converted)))
    (doseq [changed [(str/replace converted "...OK" "...SKIP")
                     (str/replace converted "2/2" "2/3")
                     (str/replace converted "address-of-syntax-test" "unknown-test")
                     (str converted "printed value = 43\n")]]
      (is (not= (normalize left original) (normalize right changed))))
    (is (= "printed sample.test.address of syntax...OK\n"
           (normalize left "printed sample.test.address of syntax...OK\n")))
    (is (= "1/1 sample.<test-0>...OK\n"
           (normalize [{:kind :identifier :label "decltest.addOne"}]
                      "1/1 sample.decltest.addOne...OK\n")))))

(deftest compile-log-values-are-not-discarded-as-diagnostics
  (let [diagnostic "a.zig:1:1: error: found compile log statement\n"
        output (str diagnostic "Compile Log Output:\n@as(i32, 99)\n")]
    (is (= "@as(i32, 99)\n" (ref/compile-log-output output)))
    (is (= :diagnostics-matched
           (:status (ref/compare-observations "test_error=" output output))))
    (is (= :observation-mismatch
           (:status (ref/compare-observations "test_error=" output
                                             (str/replace output "99" "98")))))
    (is (= :observation-mismatch
           (:status (ref/compare-observations "test_error=" output diagnostic))))))

(deftest repl-output-is-generated-by-real-evaluation
  (let [context (ref/prepare-example-context!)
        hello (ref/capture-example-repl! "hello.zig" context)
        compilation (ref/capture-example-repl! "export_builtin.zig" context)
        failure (ref/capture-example-repl! "var_must_be_initialized.zig" context)]
    (is (= "(run-example! \"hello.zig\")" (:form hello)))
    (is (= "Hello, World!\n" (:stdout hello)))
    (is (= 0 (get-in hello [:value :exit])))
    (is (= :host (get-in hello [:value :scope])))
    (is (= :compile-only (get-in compilation [:value :scope])))
    (is (= 0 (get-in compilation [:value :exit])))
    (is (str/includes? (get-in failure [:exception :message])
                       "let expects an even Clojure binding vector"))))

(deftest authored-recipes-are-complete-and-call-their-own-example
  (doseq [[file {:keys [source]}] (ref/read-edn "resources/learn/overrides.edn")
          :when source
          :let [code (slurp (io/resource source))
                forms (inline/read-forms code)
                calls (ref/comment-calls code)
                tests (mapv #(list (second %)) (filter #(= 'az/deftest (first %)) forms))]]
    (testing file
      (is (or (seq calls) (not= 'comment (first (last forms)))))
      (is (not (str/includes? code "reference/run-example!")))
      (when (seq tests) (is (= tests calls)))
      (when (some #(and (#{'az/defn 'az/defn-} (first %)) (= 'main (second %))) forms)
        (is (some #{'main} (tree-seq coll? seq calls))))))
  (doseq [[id {:keys [source]}] (ref/read-edn "resources/learn/fragment-overrides.edn")]
    (is (not (str/includes? (slurp (io/resource source)) "reference/check-snippet!")) id))
  (is (= [] (ref/comment-calls "(ns example)")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a file runner"
                       (ref/comment-calls "(ns example) (comment (reference/run-example! \"x.zig\"))"))))

(deftest recipe-capture-executes-the-exact-direct-calls
  (let [hello (ref/capture-comment-repl!
               (slurp (io/resource "learn/example/hello_again.clj")) nil)
        test (ref/capture-comment-repl!
              (slurp (io/resource "learn/example/test_comptime_max_with_bool.clj")) nil)
        main-call (first (:evaluations hello))
        test-call (first (:evaluations test))]
    (is (= "(main)" (:form main-call)))
    (is (= "nil" (:printed-value main-call)))
    (is (= "Hello, World!\n" (:stderr main-call)))
    (is (= "(boolean-maximum-test)" (:form test-call)))
    (is (str/includes? (:stderr test-call) "All 1 tests passed."))
    (is (str/includes? (:printed-value test-call) ":status :passed"))
    (is (nil? (find-ns 'learn.example.hello-again)))
    (is (nil? (find-ns 'learn.example.test-comptime-max-with-bool)))))

(deftest native-diagnostics-point-at-the-authored-clojure-form
  (let [path (.getCanonicalPath (io/file "resources/learn/example/constant_identifier_cannot_change.clj"))
        result (ref/capture-comment-repl! (slurp path) nil path)
        message (get-in result [:evaluations 0 :exception :message])]
    (is (= :zig-compile (get-in result [:evaluations 0 :exception :phase])))
    (is (str/includes? message (str path ":12:5")))
    (is (str/includes? message "12 |     (ak/+= y 1)))"))
    (is (str/includes? message "^^^^^^^^^^^ this Aguafria form"))
    (is (str/includes? message "cannot assign to constant"))))

(deftest recipe-capture-does-not-invent-success-for-a-bad-call
  (let [result (ref/capture-comment-repl!
                "(ns learn.example.bad-recipe)\n(comment (missing-call))" nil)]
    (is (get-in result [:evaluations 0 :exception]))
    (is (= "(missing-call)" (get-in result [:evaluations 0 :form])))))

(deftest matching-native-code-does-not-hide-a-broken-recipe
  (with-redefs [ref/run-doctest! (constantly {:exit 0})
                ref/capture-example-repl! (constantly {:value {:exit 0}})
                ref/capture-comment-repl!
                (constantly {:evaluations [{:exception {:message "bad call"}}]})
                ref/compare-doctest-output (constantly {:status :output-matched})
                ref/write-text! (fn [& _])
                ref/sha256 (constantly "test-hash")
                clojure.core/slurp (constantly "(ns example) (comment (main))")]
    (is (= :failed
           (:status (ref/verify-example! {}
                                        {:file "example.zig" :manifest {:kind "exe=succeed"}}
                                        {:status :translated :clojure-path "example.clj"}))))))

(deftest native-diagnostics-are-not-confused-with-jvm-recipe-errors
  (doseq [kind ["syntax" "test_error=error: expected failure" "obj=error"]]
    (is (ref/verified-comment?
         {:kind kind}
         {:evaluations [{:exception {:phase :zig-compile :message "native diagnostic"}}]}))
    (doseq [phase [nil :load :zig-test-selection]]
      (is (not (ref/verified-comment?
                {:kind kind}
                {:evaluations [{:exception {:phase phase :message "broken recipe"}}]})))))
  (is (not (ref/verified-comment?
            {:kind "exe=succeed"}
            {:evaluations [{:exception {:phase :zig-compile :message "must run"}}]}))))

(deftest compiler-wrappers-retain-the-native-diagnostic
  (doseq [phase [:zig-test nil]]
    (let [source (str "(ns learn.example.wrapped-diagnostic)\n"
                      "(comment (throw (clojure.lang.Compiler$CompilerException.\n"
                      "  \"wrapped.clj\" 1 1\n"
                      "  (ex-info \"branch quota exceeded\" "
                      (pr-str {:aguafria/phase phase}) "))))")
          result (ref/capture-comment-repl! source nil)]
      (is (= phase (get-in result [:evaluations 0 :exception :phase])))
      (is (= (some? phase)
             (boolean (ref/verified-comment?
                       {:kind "test_error=error: branch quota exceeded"} result))))
      (when phase
        (is (= "branch quota exceeded"
               (get-in result [:evaluations 0 :exception :message])))))))

(deftest external-object-recipe-runs-main-in-process
  (let [result (ref/capture-comment-repl!
                (slurp (io/resource "learn/example/float_mode_exe.clj")) nil)
        output (last (:evaluations result))]
    (is (not-any? :exception (:evaluations result)))
    (is (nil? (find-ns 'learn.example.float-mode-obj)))
    (is (str/includes? (:form output) "(main)"))
    (is (= "optimized = 0.001\nstrict = 0.0009765625\n" (:stderr output)))
    (is (= "nil" (:printed-value output)))))

(deftest direct-branch-quota-test-records-the-native-error
  (let [result (ref/capture-comment-repl!
                (slurp (io/resource "learn/example/test_without_setEvalBranchQuota_builtin.clj"))
                nil)
        diagnostic (get-in result [:evaluations 0 :exception])]
    (is (= :zig-test (:phase diagnostic)))
    (is (str/includes? (:message diagnostic) "evaluation exceeded 1000 backwards branches"))
    (is (ref/verified-comment?
         {:kind "test_error=error: evaluation exceeded 1000 backwards branches"} result))))

(deftest inspection-preserves-the-open-lesson-namespace
  (let [namespace-symbol 'learn.example.open-lesson
        namespace (create-ns namespace-symbol)
        original (intern namespace 'main :unsaved-user-value)]
    (try
      (let [emitted (ref/emit-clojure
                     "(ns learn.example.open-lesson (:require [aguafria.zig :as az]))
                      (az/defn main :i32 [] 42)
                      (comment (main))"
                     namespace-symbol {})]
        (is (str/includes? emitted "return 42;"))
        (is (identical? original (ns-resolve namespace-symbol 'main)))
        (is (= :unsaved-user-value @original))
        (is (empty? (filter #(str/starts-with? (str (ns-name %))
                                             "learn.example.open-lesson.inspection-")
                            (all-ns)))))
      (finally (remove-ns namespace-symbol)))))

(deftest repl-and-shell-belong-to-their-own-language-panels
  (let [original (str "<figure><figcaption class=\"zig-cap\">"
                      "<cite class=\"file\">hello.zig</cite></figcaption>"
                      "<pre><code>original code</code></pre></figure>"
                      "<figure><figcaption class=\"shell-cap\">Shell</figcaption>"
                      "<pre><samp>original shell output</samp></pre></figure>")
        match (re-find ref/figure-pattern original)
        transcript {:namespace "learn.reference" :form "(run-example! \"hello.zig\")"
                    :stdout "<actual REPL output>\n" :stderr ""
                    :value {:exit 0}}
        panel (ref/example-panel match
                                 {:status :translated
                                  :clojure-path "resources/learn/example/hello.clj"
                                  :repl-transcript transcript} 1)
        aguafria (subs panel (str/index-of panel "id=\"learn-example-1-a\""))]
    (is (= original (first match)))
    (is (str/includes? aguafria "learn-repl-label\">REPL"))
    (is (str/includes? aguafria "&lt;actual REPL output&gt;"))
    (is (not (str/includes? aguafria "original shell output")))
    (is (not (str/includes? aguafria "class=\"shell-cap\"")))))

(deftest authored-inline-examples-use-the-real-emitter
  (let [mappings (clojure.edn/read-string (slurp (io/resource "learn/inline/operators.edn")))
        translated (mapv inline/emit-mapping (vals mappings))]
    (is (= 72 (count mappings)))
    (is (= 32 (count (filter :standalone? translated))))
    (is (every? #(= :emission-checked (:verification %)) translated))
    (is (every? :alternatives? (filter #(= :statements (:form-kind %)) translated)))
    (is (str/includes? (inline/syntax-unit translated) "<<|="))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"existing Var"
                         (inline/emit-mapping {:clojure-source "nonexistent-op"
                                               :kind :reference})))
    (is (thrown? Exception (inline/read-forms "#=(System/exit 0)")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"number of inline forms"
                         (inline/emit-mapping {:clojure-source "1 2" :kind :expr}))))
  (let [source "const x: u8 = 7;\nx == 7"
        expression (inline/expression-body source)]
    (is (str/includes? expression "const x: u8 = 7;"))
    (is (str/includes? expression "break :blk x == 7;"))))

(deftest paired-contexts-compare-actual-output-and-failures
  (let [saved (atom [])
        good {:out "result=42\n" :err "" :exit 0 :timed-out? false}]
    (doseq [[right expected] [[good :output-matched]
                              [(assoc good :out "result=43\n") :observation-mismatch]
                              [(assoc good :err "unexpected warning") :observation-mismatch]
                              [(assoc good :exit 1) :native-test-failed]
                              [(assoc good :timed-out? true) :native-test-failed]]]
      (let [runs (atom [good right])]
        (with-redefs [ref/write-text! (fn [& _])
                      ref/write-edn! (fn [path value] (swap! saved conj [path value]))
                      ref/run-command (fn [& _]
                                        (let [value (first @runs)]
                                          (swap! runs subvec 1)
                                          value))]
          (is (= expected (:status (ref/verify-native-pair! "unused" "zig" "emitted")))))))
    (is (= 5 (count @saved)))
    (is (every? #(= "unused/comparison.edn" (first %)) @saved))))

(deftest inline-evidence-cannot-outlive-source-or-compiler
  (let [snippet {:source "1" :source-sha256 "a" :clojure-source "1"
                 :zig-source "1" :standalone? true :verification :emission-checked}
        report {:fingerprint {:compiler "current"}
                :comparison {:status :output-matched}
                :snippets [snippet]}]
    (with-redefs [ref/inline-fingerprint (constantly {:compiler "current"})]
      (is (= :output-matched
             (:verification (first (ref/current-inlines [snippet] report)))))
      (is (= :emission-checked
             (:verification (first (ref/current-inlines
                                    [(assoc snippet :clojure-source "2")] report)))))
      (is (= :emission-checked
             (:verification (first (ref/current-inlines
                                    [snippet] (assoc report :fingerprint {})))))))))

(deftest syntax-notes-are-prose-not-fake-code
  (let [mapping (inline/emit-mapping {:kind :syntax-note :clojure-source ""
                                     :note "Prefix nesting, such as (+ a (* b c)), sets the order."})
        markup (inline/markup "<code>precedence</code>"
                              (assoc mapping :id "note" :source "precedence") ref/escape-html)]
    (is (= :reviewed-syntax-note (:verification mapping)))
    (is (nil? (:zig-source mapping)))
    (is (not (str/includes? markup "<code></code>")))
    (is (str/includes? markup "Prefix nesting")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"prose"
                       (inline/emit-mapping {:kind :syntax-note
                                             :clojure-source "fake program" :note "note"}))))

(deftest discovery-comparison-only-removes-known-empty-test-framing
  (let [scenario {:id :sample :mode :native-test
                  :expect {:test-counts {:original 2 :emitted 1}
                           :named-tests ["tests.test.alpha"]}}
        result {:out "" :exit 0 :timed-out? false}
        original (assoc result :err (str "1/2 subject.test_0...OK\n"
                                          "2/2 tests.test.alpha...payload=42\nOK\n"
                                          "All 2 tests passed.\n"))
        emitted (assoc result :err (str "1/1 tests.test.alpha...payload=42\nOK\n"
                                         "All 1 tests passed.\n"))
        compare! #(#'ref/discovery-compare-scenario scenario original % "subject.test_0")]
    (is (= :discovery-matched (:status (compare! emitted))))
    (doseq [changed [(str/replace (:err emitted) "42" "43")
                     (str/replace (:err emitted) "alpha" "beta")
                     (str/replace (:err emitted) "1/1" "1/2")
                     (str/replace (:err emitted) "OK\n" "FAIL\n")
                     (str "unexpected\n" (:err emitted))]]
      (is (= :discovery-mismatch (:status (compare! (assoc emitted :err changed)))))))
  (let [observe #'ref/discovery-runner-observation]
    (is (:valid? (observe "All 0 tests passed.\n" 0 [] nil)))
    (is (not (:valid? (observe "1/1 subject.test_0...extra\nOK\nAll 1 tests passed.\n"
                               1 [] "subject.test_0"))))))

(deftest complete-inline-corpus-has-explicit-context
  (let [snippets (filterv #(= "syntax" (:kind %)) (:snippets (ref/inventory)))
        translated (inline/translate snippets)]
    (is (= 1362 (count translated)))
    (is (every? #(contains? #{:translated :reference-mapped} (:status %)) translated))
    (doseq [snippet translated]
      (is (not (str/blank? (:note snippet))) (:id snippet)))
    (doseq [[source mapping] (inline/authored-mappings)]
      (is (not (re-find #"\(ak/(?:const|var)\s" (:clojure-source mapping))) source)
      (when (:standalone? mapping)
        (is (= :expr (:kind mapping)) source)))))

(deftest inline-enum-literals-are-not-bare-identifiers
  (doseq [[source mapping] (inline/authored-mappings)
          :when (re-matches #"\.[A-Za-z_][A-Za-z0-9_]*" source)]
    (is (= source (:zig-source (inline/emit-mapping mapping))) source))
  (is (= "@Int(.unsigned, 18)"
         (:zig-source (inline/emit-mapping
                       ((inline/authored-mappings) "@Int(.unsigned, 18)")))))
  (is (str/includes?
       (:zig-source (inline/emit-mapping
                     ((inline/authored-mappings) "continue :sw .next_state")))
       ".next_state")))

(deftest inline-declarations-are-stable-and-isolated
  (let [namespaces #(set (map ns-name (all-ns)))
        before-namespaces (namespaces)
        before-libraries (loaded-libs)
        mapping ((inline/authored-mappings) "pub const _start = {};")
        first-result (inline/emit-mapping mapping)
        second-result (inline/emit-mapping mapping)]
    (is (= first-result second-result))
    (is (str/includes? (:zig-source first-result) "pub const _start = {};"))
    (is (str/includes? (inline/syntax-unit [first-result]) "const fragment_0 = struct {"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Inline declarations"
                         (inline/emit-mapping {:kind :declarations
                                              :clojure-source "(ns forbidden)"})))
    (is (thrown? Exception
                 (inline/emit-mapping {:kind :declarations
                                       :clojure-source "(az/defconst unfinished)"})))
    (is (= before-namespaces (namespaces)))
    (is (= before-libraries (loaded-libs)))))

(deftest inline-alignment-assertion-preserves-the-upstream-failure
  (let [[source mapping] (first (filter #(str/starts-with? (key %) "const assert =")
                                      (inline/authored-mappings)))
        emitted (:zig-source (inline/emit-mapping mapping))
        result (ref/verify-native-pair! "build/inline-comptime-test" source emitted)]
    (is (str/includes? emitted "comptime {"))
    (is (str/includes? emitted "*align(@alignOf(u32)) u32"))
    (is (= :native-test-failed (:status result)))
    (doseq [side [:original :aguafria]
            :let [run (get result side)]]
      (is (false? (:timed-out? run)))
      (is (not= 0 (:exit run)))
      (is (= [(:expected-compile-error mapping)]
             (ref/failure-messages (:err run)))
          (:err run)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'learn.reference-test)]
    (shutdown-agents)
    (when (pos? (+ fail error))
      (System/exit 1))))
