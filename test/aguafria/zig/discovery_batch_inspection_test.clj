(ns aguafria.zig.discovery-batch-inspection-test
  (:require [aguafria.keyword]
            [aguafria.zig]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.runtime :as runtime]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- analyze [forms]
  (let [context (create-ns (symbol (str "aguafria.batch-inspection-" (random-uuid))))
        commands (atom [])
        inspect runtime/inspect-module!
        blocked (fn [& _] (throw (ex-info "Inspection invoked native code" {})))]
    (try
      (binding [*ns* context runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (alias 'k 'aguafria.keyword)
        (doseq [form forms] (eval form)))
      (with-redefs [runtime/invoke! blocked runtime/invoke-with-result! blocked
                    runtime/inspect-module!
                    (fn [& arguments]
                      (let [result (apply inspect arguments)]
                        (swap! commands conj (select-keys result [:exit :err :source-path :command]))
                        result))]
        (let [report (discovery/analyze! (ns-name context))]
          {:report report :commands @commands}))
      (finally (remove-ns (ns-name context))))))

(deftest numeric-constant-query-and-original-roots-use-one-pass
  (let [{:keys [report commands]}
        (analyze '[(a/defconst number :i32 42)
                   (a/defn add :i32 [[x :i32]] (k/+ x number))])]
    (is (zero? (get-in report [:baseline :exit])))
    (is (= 2 (count commands)))
    (is (= (get-in report [:baseline :command]) (get-in report [:local-type-query :command])))
    (is (= (get-in report [:baseline :source-path])
           (get-in report [:local-type-query :source-path])))
    (is (empty? (:local-type-identities report)))
    (is (some #(and (= 'aguafria.keyword/+ (:function %)) (= :observed (:status %)))
              (:operations report)))))

(deftest combined-query-checks-test-bodies-without-running-them
  (let [{:keys [report commands]}
        (analyze '[(a/defconst number :i32 42)
                   (a/deftest never-run (k/unreachable))])]
    (is (zero? (get-in report [:baseline :exit])))
    (is (= 2 (count commands)))
    (is (str/includes? (slurp (get-in report [:baseline :source-path])) "unreachable"))
    (is (empty? (:root-failures report)))))

(deftest no-constant-query-keeps-the-original-root-check
  (let [{:keys [report commands]}
        (analyze '[(a/defn add :i32 [[x :i32]] (k/+ x 1))])]
    (is (zero? (get-in report [:baseline :exit])))
    (is (nil? (:local-type-query report)))
    (is (= 2 (count commands)))))

(deftest rejected-exported-roots-cannot-be-accepted-from-type-queries
  (let [{:keys [report commands]}
        (analyze '[(a/defconst number :i32 42)
                   (a/defn broken :i32 [[x :i32]]
                     (k/= :_ x)
                     (k/compileError "rejected function root"))
                   (a/defn add :i32 [[x :i32]] (k/+ x number))])]
    (is (pos? (get-in report [:baseline :exit])))
    (is (pos? (get-in report [:analysis-baseline :exit])))
    (is (str/includes? (get-in report [:baseline :diagnostics]) "rejected function root"))
    (is (not (str/includes? (slurp (get-in report [:baseline :source-path])) "aguafria.local-type")))
    (is (> (count commands) 2))
    (is (:compiler-errors? report))))

(deftest type-valued-constants-retain-compiler-identified-aliases
  (let [{:keys [report commands]}
        (analyze '[(a/defconst Alias (k/TypeOf (k/i32 42)))
                   (a/defconst number :i32 42)
                   (a/defn add :i32 [[x :i32]] (k/+ x number))])]
    (is (zero? (get-in report [:baseline :exit])))
    (is (not (str/includes? (slurp (get-in report [:baseline :source-path])) "aguafria.local-type")))
    (is (= #{'Alias} (:local-type-identities report)))
    (is (= 1 (get-in report [:local-type-query :exit])))
    (is (= 3 (count commands)))))
