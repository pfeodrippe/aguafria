(ns aguafria.zig.jvm-alignment-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]
            [aguafria.zig.convert :as convert]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.source-map :as source-map]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest structured-declaration-alignment
  (doseq [kind [:const :var :extern-var :fn :fn-proto]]
    (let [source (emitter/emit-declaration
                  {:kind kind :name 'aligned :type :u8 :value 100
                   :args [] :return :void :body [] :align 4})]
      (is (str/includes? source "align(4)"))))
  (let [context (the-ns 'aguafria.zig.jvm-alignment-test)
        prepared (emitter/prepare-declaration
                  context {:kind :fn :name 'aligned :args [] :return :void :body []
                           :align '(k/* (k/sizeOf :usize) 2)})]
    (is (= '(aguafria.keyword/* (aguafria.keyword/sizeOf :usize) 2) (:align prepared)))
    (is (str/includes? (emitter/emit-declaration prepared) "align((@sizeOf(usize) * 2))"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"[Uu]nresolved|[Uu]nknown"
                         (emitter/prepare-declaration context
                           {:kind :var :name 'aligned :type :u8 :value 100
                            :align 'unknown-alignment}))))
  (doseq [kind [:const :var :fn]]
    (let [descriptor {:kind kind :name 'aligned :module "alignment.fingerprint"
                      :qualified-name 'alignment.fingerprint/aligned
                      :type :u8 :value 100 :args [] :return :void :body []}
          key (if (= :var kind) :schema-fingerprint :implementation-fingerprint)]
      (is (not= (key (runtime/declaration-info (assoc descriptor :align 4)))
                (key (runtime/declaration-info (assoc descriptor :align 8))))))))

(deftest converter-preserves-alignment-as-an-expression
  (let [result (convert/verify-file "test/fixtures/declaration_alignment.zig"
                                    {:namespace 'fixture.declaration-alignment
                                     :mode :test :throw? false})
        source (:clojure-source result)]
    (is (:success? result) (pr-str result))
    (is (str/includes? source ":align 4"))
    (is (str/includes? source ":align 8"))
    (is (not (re-find #":zig/qualifiers \"align" source)))
    (is (str/includes? source "callconv(.c)"))))

(deftest pointer-schemas-use-star
  (is (= "*volatile u8" (emitter/emit-type [:* {:volatile? true} :u8])))
  (is (= "[*]align(4) const u8"
         (emitter/emit-type [:* {:size :many :const? true :align 4} :u8])))
  (is (thrown? clojure.lang.ExceptionInfo
               (emitter/emit-type [(keyword "pointer") {} :u8]))))

(deftest actual-alignment-lesson-bodies-run-on-the-jvm
  (doseq [lesson ["test_volatile" "test_variable_alignment" "test_variable_func_alignment"]]
    (let [file (str "examples/learn/resources/learn/example/" lesson ".clj")
          forms (source-map/read-forms (slurp file))]
      (binding [*ns* *ns* *file* file]
        (eval (first forms))
        (binding [runtime/*source-only-registration?* true]
          (doseq [form (rest forms)] (eval form)))
        (doseq [declaration (filter #(and (seq? %) (= 'az/deftest (first %))) forms)]
          (testing (str lesson "/" (second declaration))
            (doseq [body (drop 2 declaration)]
              (is (some? (do (eval body) :completed)) (pr-str body)))
            (is (= :passed (:status ((ns-resolve *ns* (second declaration))))))))))))

(deftest computed-type-options-use-native-results
  (is (= "*align(@alignOf(i32)) i32"
         (emitter/emit-type
          ((requiring-resolve 'aguafria.zig.jvm/constructor-type)
           [:* {:align (k/alignOf :i32)} :i32]))))
  (doseq [type [:i32 [:array 3 :u32] [:* :i32]]]
    (is (= (az/value (k/alignOf (az/type type)))
           (az/value (k/alignOf type))))))

(deftest mutable-storage-supports-structured-alignment
  (doseq [alignment [4 (k/alignOf :u32)]]
    (let [v (k/var 100 :u8 {:align alignment})]
      (is (= 4 (:align (az/value-info v))))
      (is (zero? (mod (.address (az/native-segment v)) 4)))
      (k/= v 101)
      (is (= 101 (az/value v)))))
  (doseq [alignment [0 -1 3 nil]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive power of two"
                         (k/var 100 :u8 {:align alignment})))))
