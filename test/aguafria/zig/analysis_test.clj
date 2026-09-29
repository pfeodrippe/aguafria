(ns aguafria.zig.analysis-test
  (:require [aguafria.zig.analysis :as analysis]
            [clojure.test :refer [deftest is]]))

(def source
  (str "(ns sample (:require [aguafria.zig :as az]))\n"
       "(az/defn identity-int :i32 [[x :i32]]\n"
       "  (let [y x] y))\n"
       "(az/defconst answer (identity-int 42))\n"
       "(az/defconst V (az/type [:vector 4 :i32]))\n"
       "(az/defconst value (az/init [1 -1 1 -1] V))\n"
       "(unknown-call answer)\n"))

(deftest no-clojure-type-inference-and-exact-spans
  (let [report (analysis/analyze-source source {:file "sample.clj"})
        forms (:forms report)
        lookup (group-by :form forms)]
    (is (every? #(= :unresolved (:status %)) forms))
    (is (every? #(nil? (:type %)) forms))
    (is (= :unresolved (:status (first (lookup "(unknown-call answer)")))))
    (is (every? #(not= :compiler (:basis %)) forms))
    (doseq [{:keys [start end form]} forms]
      (is (= form (subs source start end))))))

(deftest zig-hover-observations-use-exact-spans
  (let [source "(let [x (k/i32 1234)] x)"
        hover "```zig\nconst x: i32 = 1234;\n```"
        report (analysis/analyze-source source
                                        {:zls-observations [{:start 6 :end 7 :hover hover}]})
        xs (filter #(= "x" (:form %)) (:forms report))]
    (is (= :zls (:basis (first xs))))
    (is (= hover (:message (first xs))))
    ;; A matching name elsewhere is not evidence: never guess source mappings.
    (is (= :unresolved (:status (second xs))))))

(deftest observations-are-revision-specific-and-retain-specializations
  (let [source "(az/debug! (f x))"
        observation {:file "test.clj" :source-sha256 (analysis/source-hash source)
                     :line 1 :column 1 :phase :compile :status :ok :type "i32"}
        root (fn [observations]
               (first (filter #(zero? (:start %))
                              (:forms (analysis/analyze-source
                                       source {:file "test.clj" :observations observations})))))]
    (is (= ["i32" "f32"] (:types (root [observation (assoc observation :type "f32")]))))
    (is (= :compiler (:basis (root [observation]))))
    (is (not= :compiler (:basis (root [(assoc observation :source-sha256 "old")]))))
    (is (not= :compiler (:basis (root [(assoc observation :file "different.clj")]))))))

(deftest do-not-run-host-code-or-reader-eval
  (let [report (analysis/analyze-source
                "(az/clj! (throw (Exception. \"must not execute\")))\n#=(System/exit 1)" {})]
    (is (seq (:forms report)))
    (is (every? #(not= :compiler (:basis %)) (:forms report)))))

(deftest tolerate-anonymous-functions-and-unresolved-snippets
  (doseq [source ["(fn [x] x)" "(some-macro [a b] c)" "'x" "@value"
                  "(let [x \"é😀\"] x)" "(az/defstruct S [[:x :i32]])"]]
    (let [report (analysis/analyze-source source {})]
      (is (seq (:forms report)))
      (doseq [{:keys [start end form]} (:forms report)]
        (is (= form (subs source start end)))))))
