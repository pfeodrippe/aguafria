(ns aguafria.zig.editor-test
  (:require [aguafria.zig.convert :as convert]
            [aguafria.zig.editor :as editor]
            [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io File]))

(defn- reset-runtime
  [test-function]
  (editor/clear!)
  (runtime/configure! {:async? true})
  (try
    (test-function)
    (finally
      (editor/clear!))))

(use-fixtures :each reset-runtime)

(def ^:private fixture-suffix (str (random-uuid)))

(deftest parses-unsaved-source-with-editor-ranges
  (let [source "/// answer docs\npub fn answer() u32 { return 42; }\n\nconst x = 1;"
        parsed (convert/parse-source source {:path "file:///virtual/demo.zig"})
        spans (convert/declaration-spans parsed)]
    (is (= "file:///virtual/demo.zig" (:path parsed)))
    (is (= ["answer" "x"] (mapv :zig-name spans)))
    (is (= {:line 1 :character 0} (get-in spans [0 :range :start])))
    (is (= "answer"
           (:zig-name
            (convert/declaration-at source {:line 0 :character 3}
                                    {:path "file:///virtual/demo.zig"}))))))

(deftest renders-unsaved-source-without-a-generated-clojure-file
  (let [rendered
        (convert/render-source "pub fn answer() u32 { return 42; }"
                               {:namespace 'editor.fixture
                                :path "file:///virtual/demo.zig"})
        declaration (first (:declarations rendered))]
    (is (= "editor.fixture" (:module declaration)))
    (is (= 'editor.fixture/answer (:qualified-name declaration)))
    (is (= ["editor.fixture" :fn "answer"] (:logical-id declaration)))
    (is (= "file:///virtual/demo.zig" (get-in declaration [:source :file])))
    (is (re-find #"pub fn answer" (:zig-source rendered)))
    (is (not (contains? rendered :output-path)))))

(deftest diffs-in-memory-zig-declarations-by-live-identity
  (let [options {:namespace 'editor.diff :path "file:///virtual/diff.zig"}
        before (convert/declarations-from-source
                "pub fn answer() u32 { return 42; }\nconst stable = 7;"
                options)
        after (convert/declarations-from-source
               "pub fn answer() u32 { return 43; }\nconst stable = 7;\nconst added = 9;"
               options)
        diff (convert/diff-declarations before after)]
    (is (= ["added"] (mapv (comp str :name) (:added diff))))
    (is (= ["answer"] (mapv (comp str :name) (:changed diff))))
    (is (= ["stable"] (mapv (comp str :name) (:unchanged diff))))
    (is (empty? (:removed diff)))))

(deftest rehomed-source-preserves-same-module-reference-identity
  (let [source
        (str "pub const Message = union(enum) { ping };\n"
             "pub const Mailbox = struct {\n"
             "    value: Message,\n"
             "    pub fn read(self: Mailbox) Message { return self.value; }\n"
             "};\n")
        rendered (convert/render-source source
                                        {:namespace 'editor.same-module
                                         :path "file:///virtual/same.zig"})
        mailbox (some #(when (= 'Mailbox (:name %)) %)
                      (:declarations rendered))
        message-references
        (->> (tree-seq coll? seq mailbox)
             (filter symbol?)
             (keep #(some-> % meta :aguafria/zig-reference))
             (filter #(= "Message" (:zig-name %))))]
    (is (seq message-references))
    (is (every? #(= "editor.same-module" (:module %)) message-references))
    (is (not (re-find #"editor\\.same_module__Message" (:zig-source rendered))))
    (is (re-find #"value: Message" (:zig-source rendered)))
    (is (re-find #"read\(self: Mailbox\) Message" (:zig-source rendered)))))

(deftest rehomed-source-preserves-mutable-state-reference-identity
  (let [source
        (str "pub var counter: i32 = 1;\n"
             "pub fn read_counter() i32 { return counter; }\n")
        rendered (convert/render-source
                  source
                  {:namespace 'editor.same-state-module
                   :path "file:///virtual/same-state.zig"})
        counter (some #(when (= 'counter (:name %)) %)
                      (:declarations rendered))
        reader (some #(when (= 'read_counter (:name %)) %)
                     (:declarations rendered))
        counter-reference
        (->> (tree-seq coll? seq reader)
             (filter symbol?)
             (keep #(some-> % meta :aguafria/zig-reference))
             (some #(when (= "counter" (:zig-name %)) %)))
        state-reference (runtime/state-reference counter)]
    (is (= ["editor.same-state-module" :var "counter"]
           (:logical-id counter-reference)))
    (is (= (:accessor state-reference)
           (:state-accessor counter-reference)))
    (is (not (re-find #"aguafria\.zig\.convert\.scratch"
                      (:zig-source rendered))))))

(deftest evaluates-and-hot-reloads-pure-zig-in-one-runtime
  (let [root (.getCanonicalPath (File. "."))
        project-id (str "editor-live-test-" fixture-suffix)
        uri (str (.toURI (File. root "test/fixtures/editor_live.zig")))
        initial
        (str "pub const exact: u128 = 1208925819614629174706177;\n"
             "pub fn answer() u32 { return 42; }\n"
             "pub fn caller() u32 { return answer(); }\n")
        changed
        (str "pub const exact: u128 = 1208925819614629174706177;\n"
             "pub fn answer() u32 { return 43; }\n"
             "pub fn caller() u32 { return answer(); }\n")
        initial-hash (atom nil)
        accepted-hash (atom nil)]
    (editor/start-project! root {:project-id project-id})
    (let [first-ticket
          (editor/evaluate! {:project-id project-id
                             :uri uri
                             :source initial
                             :document-version 1
                             :mode :file})
          first-publication (editor/await! (:ticket-id first-ticket))]
      (reset! initial-hash (:source-hash first-ticket))
      (reset! accepted-hash @initial-hash)
      (is (= :published (:status first-publication)))
      (is (= 42 (editor/invoke! project-id uri "caller" [])))
      (is (= 1208925819614629174706177N
             (:value (editor/inspect! project-id uri initial
                                      {:line 0 :character 12})))))

    (let [second-ticket
          (editor/evaluate! {:project-id project-id
                             :uri uri
                             :source changed
                             :base-source-hash @accepted-hash
                             :document-version 2
                             :mode :declaration
                             :position {:line 1 :character 8}})
          second-publication (editor/await! (:ticket-id second-ticket))]
      (reset! accepted-hash (:source-hash second-ticket))
      (is (= :published (:status second-publication)))
      (is (= 43 (editor/invoke! project-id uri "caller" []))
          "the already-compiled native caller observes the new callee"))

    (testing "two editor clients cannot silently overwrite the same accepted source"
      (let [competing (.replace initial "return 42" "return 44")
            failed (editor/evaluate! {:project-id project-id
                                      :uri uri
                                      :source competing
                                      :base-source-hash @initial-hash
                                      :document-version 3
                                      :mode :declaration
                                      :position {:line 1 :character 8}})]
        (is (= :failed (:status failed)))
        (is (= :zig-editor-stale-source
               (get-in failed [:diagnostics 0 :details :aguafria/phase])))
        (is (= @accepted-hash
               (get-in failed [:diagnostics 0 :details :accepted-source-hash])))
        (is (= 43 (editor/invoke! project-id uri "caller" [])))))

    (testing "a malformed edit retains the published behavior"
      (let [failed (editor/evaluate! {:project-id project-id
                                      :uri uri
                                      :source "pub fn answer( {"
                                      :document-version 3
                                      :mode :declaration
                                      :position {:line 0 :character 7}})]
        (is (= :failed (:status failed)))
        (is (true? (get-in failed [:diagnostics 0 :old-behavior-retained?])))
        (is (= 43 (editor/invoke! project-id uri "caller" [])))))

    (testing "an async compiler error maps the exact token in unsaved Zig"
      (let [invalid (.replace changed "return 43" "return DoesNotExist")
            ticket (editor/evaluate! {:project-id project-id
                                      :uri uri
                                      :source invalid
                                      :base-source-hash @accepted-hash
                                      :document-version 4
                                      :mode :declaration
                                      :position {:line 1 :character 8}})
            failed (editor/await! (:ticket-id ticket))]
        (is (= :failed (:status failed)))
        (is (= {:start {:line 1 :character 29}
                :end {:line 1 :character 41}}
               (get-in failed [:diagnostics 0 :range])))
        (is (nil? (meta failed)) "unsaved source is released after completion")
        (is (= 43 (editor/invoke! project-id uri "caller" [])))))))

(deftest bootstraps-and-hot-reloads-across-pure-zig-files
  (let [workspace (.getCanonicalFile (File. "test/fixtures/import_tree"))
        project-id (str "editor-import-tree-test-" (random-uuid))
        main-uri (str (.toURI (File. workspace "main.zig")))
        math-file (File. workspace "math.zig")
        math-uri (str (.toURI math-file))
        original (slurp math-file)
        changed (.replace original "value * 2" "value * 3")]
    (editor/start-project!
     (.getPath workspace)
     {:project-id project-id
      :project-root "."
      :namespace-prefix (symbol (str "editor.import-tree." project-id))
      :bootstrap? true
      :capture-build-modules? false})
    (let [summary (editor/bootstrap-project! project-id)]
      (is (= :ready (:status summary)))
      (is (= 3 (:file-count summary))))

    (is (= 40 (editor/invoke! project-id main-uri "quadruple" [10])))

    (let [ticket (editor/evaluate! {:project-id project-id
                                    :uri math-uri
                                    :source changed
                                    :document-version 1
                                    :mode :declaration
                                    :position {:line 0 :character 8}})
          publication (editor/await! (:ticket-id ticket))]
      (is (= :published (:status publication)))
      (is (= 90 (editor/invoke! project-id main-uri "quadruple" [10]))
          "an already-compiled cross-file caller observes the new callee"))))

(deftest adds-a-function-and-reevaluates-its-new-caller-without-restart
  (let [root (.getCanonicalPath (File. "."))
        project-id (str "editor-add-callee-" (random-uuid))
        namespace-prefix (symbol (str "editor.add-callee." project-id))
        uri (str (.toURI (File. root "test/fixtures/editor_add_callee.zig")))
        initial "pub fn b() u32 { return 1; }\n"
        changed (str "pub fn a() u32 { return 41; }\n"
                     "pub fn b() u32 { return a() + 1; }\n")]
    (editor/start-project! root {:project-id project-id
                                 :namespace-prefix namespace-prefix})
    (let [ticket (editor/evaluate! {:project-id project-id
                                    :uri uri
                                    :source initial
                                    :document-version 1
                                    :mode :file})]
      (is (= :published (:status (editor/await! (:ticket-id ticket)))))
      (is (= 1 (editor/invoke! project-id uri "b" []))))

    (let [ticket (editor/evaluate! {:project-id project-id
                                    :uri uri
                                    :source changed
                                    :document-version 2
                                    :mode :file})]
      (is (= :published (:status (editor/await! (:ticket-id ticket)))))
      (is (= 41 (editor/invoke! project-id uri "a" [])))
      (is (= 42 (editor/invoke! project-id uri "b" []))))))

(deftest breaking-pure-zig-signature-keeps-its-old-caller-live
  (let [root (.getCanonicalPath (File. "."))
        project-id (str "editor-breaking-signature-" (random-uuid))
        namespace-prefix (symbol (str "editor.breaking-signature." project-id))
        uri (str (.toURI (File. root "test/fixtures/editor_signature.zig")))
        initial (str "pub fn calculate(x: i32) i32 { return x + 1; }\n"
                     "pub fn old_caller(x: i32) i32 { return calculate(x) + 100; }\n")
        changed (str "pub fn calculate(x: i32, y: i32) i32 { return x + y; }\n"
                     "pub fn old_caller(x: i32) i32 { return calculate(x) + 100; }\n")]
    (editor/start-project! root {:project-id project-id
                                 :namespace-prefix namespace-prefix})
    (let [calculate-name (symbol (editor/module-for-uri project-id uri)
                                 "calculate")
          ticket (editor/evaluate! {:project-id project-id
                                    :uri uri
                                    :source initial
                                    :document-version 1
                                    :mode :file})]
      (is (= :published (:status (editor/await! (:ticket-id ticket)))))
      (is (= 8 (editor/invoke! project-id uri "calculate" [7])))
      (is (= 108 (editor/invoke! project-id uri "old_caller" [7])))

      (let [v1 (first (runtime/function-versions calculate-name))
            ticket (editor/evaluate! {:project-id project-id
                                      :uri uri
                                      :source changed
                                      :document-version 2
                                      :mode :declaration
                                      :position {:line 0 :character 8}})]
        (is (= :published (:status (editor/await! (:ticket-id ticket)))))
        (is (= 12 (editor/invoke! project-id uri "calculate" [5 7])))
        (is (= 108 (editor/invoke! project-id uri "old_caller" [7])))
        (is (= 8 (runtime/invoke-version! calculate-name
                                          (:abi-fingerprint v1)
                                          [7])))
        (is (= 2 (count (runtime/function-versions calculate-name))))))))

(deftest compatible-pure-zig-reload-preserves-native-state-and-address
  (let [root (.getCanonicalPath (File. "."))
        project-id (str "editor-state-" (random-uuid))
        namespace-prefix (symbol (str "editor.state." project-id))
        uri (str (.toURI (File. root "test/fixtures/editor_state.zig")))
        initial (str "pub var counter: i32 = 1;\n"
                     "pub fn read_counter() i32 { return counter; }\n"
                     "pub fn write_counter(value: i32) void { counter = value; }\n"
                     "pub fn counter_address() usize { return @intFromPtr(&counter); }\n")
        changed (.replace initial
                          "return counter;"
                          "return counter + 1;")]
    (editor/start-project! root {:project-id project-id
                                 :namespace-prefix namespace-prefix})
    (let [ticket (editor/evaluate! {:project-id project-id
                                    :uri uri
                                    :source initial
                                    :document-version 1
                                    :mode :file})]
      (is (= :published (:status (editor/await! (:ticket-id ticket))))))
    (editor/invoke! project-id uri "write_counter" [37])
    (let [address (editor/invoke! project-id uri "counter_address" [])]
      (is (= 37 (editor/invoke! project-id uri "read_counter" [])))
      (let [ticket (editor/evaluate! {:project-id project-id
                                      :uri uri
                                      :source changed
                                      :document-version 2
                                      :mode :declaration
                                      :position {:line 1 :character 8}})]
        (is (= :published (:status (editor/await! (:ticket-id ticket)))))
        (is (= 38 (editor/invoke! project-id uri "read_counter" [])))
        (is (= address (editor/invoke! project-id uri "counter_address" [])))))))
