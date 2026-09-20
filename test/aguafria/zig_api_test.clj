(ns aguafria.zig-api-test
  (:require [aguafria.zig.runtime :as runtime]
            [aguafria.zig.emitter :as emitter]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest declaration-doc-attributes-and-inferred-types-test
  (let [namespace-symbol (gensym "aguafria.zig-api-test.scratch-")
        scratch (create-ns namespace-symbol)
        declarations (atom [])]
    (try
      (binding [*ns* scratch
                runtime/*registration-batch* declarations]
        (refer 'clojure.core)
        (require '[aguafria.zig :as az])
        (eval '(az/defconst clean-constant
                 "Inspectable constant."
                 {:export false :public false :source-comment false}
                 42))
        (eval '(az/defvar clean-variable {:public false} :u32 1))
        (eval '(az/defn clean-function :u32
                 "Inspectable function."
                 {:export false :public true} [[x :u32]]
                 (+ x clean-variable)))
        (eval '(az/defstruct CleanPoint
                 "Inspectable struct."
                 {:public false}
                 [[:x :f32] [:y {:doc "Vertical"} :f32]])))
      (let [by-name (into {} (map (juxt :name identity)) @declarations)]
        (is (= 4 (count @declarations)))
        (is (nil? (:type (get by-name 'clean-constant))))
        (is (= {:export false :public false :source-comment false}
               (:attributes (get by-name 'clean-constant))))
        (is (= :u32 (:type (get by-name 'clean-variable))))
        (is (= :normal (:layout (get by-name 'CleanPoint))))
        (is (= "Inspectable constant."
               (:doc (meta (ns-resolve scratch 'clean-constant)))))
        (is (= 42 (var-get (ns-resolve scratch 'clean-constant))))
        (is (= "Inspectable function."
               (:doc (meta (ns-resolve scratch 'clean-function)))))
        (is (str/starts-with? (:doc (meta (ns-resolve scratch 'CleanPoint)))
                             "Inspectable struct.")))
      (finally
        (remove-ns namespace-symbol)))))

(deftest canonical-functions-and-named-test-vars
  (let [namespace-symbol (gensym "aguafria.zig-api-test.canonical-")
        scratch (create-ns namespace-symbol)
        declarations (atom [])]
    (try
      (binding [*ns* scratch runtime/*registration-batch* declarations]
        (refer 'clojure.core)
        (require '[aguafria.zig :as az])
        (require '[aguafria.keyword :as ak])
        (eval '(az/defn answer :i32
                 "Canonical return-type-first function."
                 {:attrs #{:explicit-return}}
                 []
                 (ak/return 42)))
        (eval '(az/defn- private-answer :i32 [] 42))
        (let [first-var (eval '(az/deftest answer-test "A named test Var."))
              second-var (eval '(az/deftest answer-test "A named test Var."))]
          (is (var? first-var))
          (is (identical? first-var second-var))
          (is (= 'answer-test (:name (meta first-var))))
          (is (= "A named test Var." (:doc (meta first-var))))
          (is (:aguafria/test (meta first-var))))
        (doseq [form '[(az/defn old :- :i32 [] 1)
                      (az/defn old {:attrs #{}} :- :i32 [] 1)
                      (az/defn old {:attrs #{}} :i32 [] 1)
                      (az/deftest "string name")
                      (az/deftest nil)
                      (az/deftest {:attrs #{}} old-test)
                      (az/deftest old-test {:zig/test-name "old label"})
                      (az/deftest old-test {:zig/test-name another-name})
                      (az/deftest old-test {:zig/test-name nil})
                      (az/deftest ^{:zig/test-name "old label"} old-test)]]
          (is (thrown? Exception (eval form)) (pr-str form))))
      (let [by-name (into {} (map (juxt :name identity)) @declarations)]
        (is (= :i32 (:return (by-name 'answer))))
        (is (false? (:implicit-return? (by-name 'answer))))
        (is (true? (:implicit-return? (by-name 'private-answer))))
        (is (false? (:public? (by-name 'private-answer))))
        (is (= "answer-test" (:test-name (by-name 'answer-test))))
        (is (= [] (:body (by-name 'answer-test)))))
      (finally (remove-ns namespace-symbol)))))

(deftest vector-types-retain-fields-docs-names-defaults-and-methods
  (let [namespace-symbol (gensym "aguafria.zig-api-test.types-")
        scratch (create-ns namespace-symbol)
        declarations (atom [])]
    (try
      (binding [*ns* scratch runtime/*registration-batch* declarations]
        (refer 'clojure.core)
        (require '[aguafria.zig :as az])
        (eval '(az/defenum Color
                 "A documented enum."
                 {:argument :u8}
                 [[:red 1]
                  [:really-red {:doc "Quoted tag." :zig/name "@\"really red\""} 7]]))
        (eval '(az/defstruct Timestamp
                 "Documented timestamp."
                 [[:seconds {:doc "Seconds since the epoch." :default 0} :i64]
                  [:nanos {:doc "Nanoseconds."} :u32]
                  (az/fn-decl unix-epoch Timestamp
                    "Returns the epoch."
                    {:attrs #{:public}}
                    []
                    (az/init {:seconds 0 :nanos 0} Timestamp))]))
        (eval '(az/defextern sample :void "Extern docs." {:zig/prefix "extern \"c\""} []))
        (doseq [form '[(az/defextern old :- :void [])
                       (az/defextern old {:zig/prefix "extern"} :- :void [])
                       (az/defstruct Old [:x :u8])
                       (az/defstruct Old [:x :u8] [:y :u8])
                       (az/defstruct Bad [[:x]])
                       (az/defenum Old [:x] [:y])
                       (az/defenum Bad [[:x 1 2]])]]
          (is (thrown? Exception (eval form)) (pr-str form))))
      (let [source (emitter/emit-module (str namespace-symbol) @declarations)]
        (doseq [expected ["enum(u8)" "red = 1" "/// Quoted tag." "@\"really red\" = 7"
                          "/// Seconds since the epoch." "seconds: i64 = 0"
                          "/// Nanoseconds." "/// Returns the epoch."
                          "pub fn unix_epoch() Timestamp" "extern \"c\" fn sample() void;"]]
          (is (str/includes? source expected) expected))
        (is (str/starts-with? (:doc (meta (ns-resolve scratch 'Color))) "A documented enum."))
        (let [timestamp (ns-resolve scratch 'Timestamp)
              color (ns-resolve scratch 'Color)
              docs (:doc (meta timestamp))]
          (doseq [text [":seconds :i64 = 0" "Seconds since the epoch."
                        ":nanos :u32" "(unix-epoch)" "Returns the epoch."]]
            (is (str/includes? docs text) text))
          (is (str/includes? (pr-str @timestamp) ":members"))
          (is (str/includes? (pr-str @timestamp) "unix-epoch"))
          (is (not (str/includes? (pr-str @timestamp) ":schema-fingerprint")))
          (is (str/includes? (pr-str @color) ":kind :enum"))
          (is (str/includes? (:doc (meta color)) "Quoted tag."))
          (runtime/refresh-declaration-var! (:aguafria/declaration (meta timestamp)))
          (is (= docs (:doc (meta timestamp))) "Refreshing metadata must not duplicate member docs.")))
      (finally (remove-ns namespace-symbol)))))

(deftest extern-vars-call-the-native-library-from-the-jvm
  (let [namespace-symbol (gensym "aguafria.zig-api-test.extern-")
        scratch (create-ns namespace-symbol)]
    (try
      (binding [*ns* scratch runtime/*source-only-registration?* true]
        (refer 'clojure.core)
        (require '[aguafria.zig :as az])
        (eval '(az/defextern absolute :c_int
                 {:zig/name "abs" :zig/prefix "pub extern \"c\""}
                 [[n :c_int]]))
        (eval '(az/defextern missing :void
                 {:zig/name "aguafria_missing_extern_test_symbol"
                  :zig/prefix "pub extern \"c\""} [])))
      (let [absolute (ns-resolve scratch 'absolute)]
        (is (= 42 (absolute -42)))
        (is (= 7 (absolute 7)))
        (is (= '([n]) (:arglists (meta absolute))))
        (is (thrown? clojure.lang.ExceptionInfo (absolute))))
      (let [error (try ((ns-resolve scratch 'missing))
                       nil
                       (catch clojure.lang.Compiler$CompilerException error error))]
        (is (some? error))
        (is (str/includes? (clojure.main/err->msg error) "aguafria_missing_extern_test_symbol")))
      (finally (remove-ns namespace-symbol)))))
