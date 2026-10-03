(ns aguafria.zig-api-test
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as a]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.emitter :as emitter]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest attributes-require-sets-in-every-declaration-context
  (doseq [attributes [:comptime 'ak/comptime ak/comptime [:comptime]
                     '(:comptime) nil {:comptime true}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":attrs must be a set"
                          (ak/normalize-attributes *ns* {:attrs attributes}))))
  (is (= {:attrs #{:comptime} :zig/prefix "comptime"}
         (ak/normalize-attributes *ns* {:attrs #{ak/comptime}})))
  (doseq [attributes [#{ak/export} '#{ak/export} '#{aguafria.keyword/export}]]
    (is (= {:attrs #{:export}}
           (ak/normalize-attributes *ns* {:attrs attributes}))))
  (let [namespace-symbol (gensym "aguafria.zig-api-test.attributes-")
        scratch (create-ns namespace-symbol)]
    (try
      (binding [*ns* scratch runtime/*registration-batch* (runtime/registration-batch)]
        (refer 'clojure.core)
        (require '[aguafria.zig :as a] '[aguafria.keyword :as ak])
        (doseq [form '[(a/defn invalid :void {:attrs [:public]} [])
                      (a/defvar invalid :i32 {:attrs ak/threadlocal} 0)
                      (a/defstruct Invalid {:attrs :public} [])
                      (a/defstruct Invalid
                        [(a/fn method :void {:attrs [:public]} [])])]]
          (is (thrown? Exception (eval form)) (pr-str form))))
      (finally (remove-ns namespace-symbol)))))

(deftest inferred-variable-initializers-are-not-mistaken-for-options-or-types
  (let [scratch (create-ns (gensym "aguafria.zig-api-test.inferred-"))
        declarations (runtime/registration-batch)]
    (try
      (binding [*ns* scratch runtime/*registration-batch* declarations]
        (refer 'clojure.core)
        (require '[aguafria.zig :as a])
        (doseq [[name initializer] [['flag false] ['text "hello"] ['empty nil]
                                   ['record {:x 1}] ['tuple [1 2]]]]
          (eval (list 'a/defvar name initializer))
          (is (nil? (:type (last (runtime/collected-declarations declarations)))))
          (is (= initializer (:value (last (runtime/collected-declarations declarations))))))
        (doseq [form '[(a/defvar missing)
                      (a/defvar misplaced {:public true} :bool false)
                      (a/defvar misplaced "doc" :bool false)]]
          (is (thrown? Exception (eval form)) (pr-str form))))
      (finally (remove-ns (ns-name scratch))))))

(deftest declaration-doc-attributes-and-inferred-types-test
  (let [namespace-symbol (gensym "aguafria.zig-api-test.scratch-")
        scratch (create-ns namespace-symbol)
        declarations (runtime/registration-batch)]
    (try
      (binding [*ns* scratch
                runtime/*registration-batch* declarations]
        (refer 'clojure.core)
        (require '[aguafria.zig :as a])
        (eval '(a/defconst clean-constant
                 "Inspectable constant."
                 {:export false :public false :source-comment false}
                 42))
        (eval '(a/defvar clean-variable :u32 {:public false}  1))
        (eval '(a/defn clean-function :u32
                 "Inspectable function."
                 {:export false :public true} [[x :u32]]
                 (+ x clean-variable)))
        (eval '(a/defstruct CleanPoint
                 "Inspectable struct."
                 {:public false}
                 [[:x :f32] [:y {:doc "Vertical"} :f32]])))
      (let [metadata (meta (ns-resolve scratch 'clean-function))]
        (is (= '([[x :u32]]) (:arglists metadata)))
        (is (= :u32 (:aguafria/return-type metadata)))
        (is (= "Inspectable function.\n\nReturns: :u32" (:doc metadata))))
      (let [by-name (into {} (map (juxt :name identity)) (runtime/collected-declarations declarations))]
        (is (= 4 (count (runtime/collected-declarations declarations))))
        (is (nil? (:type (get by-name 'clean-constant))))
        (is (= {:export false :public false :source-comment false}
               (:attributes (get by-name 'clean-constant))))
        (is (= :u32 (:type (get by-name 'clean-variable))))
        (is (= :normal (:layout (get by-name 'CleanPoint))))
        (is (= "Inspectable constant."
               (:doc (meta (ns-resolve scratch 'clean-constant)))))
        (is (= 42 (var-get (ns-resolve scratch 'clean-constant))))
        (is (= "Inspectable function.\n\nReturns: :u32"
               (:doc (meta (ns-resolve scratch 'clean-function)))))
        (is (str/starts-with? (:doc (meta (ns-resolve scratch 'CleanPoint)))
                             "Inspectable struct.")))
      (finally
        (remove-ns namespace-symbol)))))

(deftest canonical-functions-and-named-test-vars
  (let [namespace-symbol (gensym "aguafria.zig-api-test.canonical-")
        scratch (create-ns namespace-symbol)
        declarations (runtime/registration-batch)]
    (try
      (binding [*ns* scratch runtime/*registration-batch* declarations]
        (refer 'clojure.core)
        (require '[aguafria.zig :as a])
        (require '[aguafria.keyword :as ak])
        (eval '(a/defn answer :i32
                 "Canonical return-type-first function."
                 {:attrs #{:explicit-return}}
                 []
                 (ak/return 42)))
        (eval '(a/defn- private-answer :i32 [] 42))
        (let [first-var (eval '(a/deftest answer-test "A named test Var."))
              second-var (eval '(a/deftest answer-test "A named test Var."))]
          (is (var? first-var))
          (is (identical? first-var second-var))
          (is (= 'answer-test (:name (meta first-var))))
          (is (= "A named test Var." (:doc (meta first-var))))
          (is (:aguafria/test (meta first-var))))
        (doseq [form '[(a/defn old :- :i32 [] 1)
                      (a/defn old {:attrs #{}} :- :i32 [] 1)
                      (a/defn old {:attrs #{}} :i32 [] 1)
                      (a/deftest "string name")
                      (a/deftest nil)
                      (a/deftest {:attrs #{}} old-test)
                      (a/deftest old-test {:zig/test-name "old label"})
                      (a/deftest old-test {:zig/test-name another-name})
                      (a/deftest old-test {:zig/test-name nil})
                      (a/deftest ^{:zig/test-name "old label"} old-test)]]
          (is (thrown? Exception (eval form)) (pr-str form))))
      (let [by-name (into {} (map (juxt :name identity)) (runtime/collected-declarations declarations))]
        (is (= :i32 (:return (by-name 'answer))))
        (is (false? (:implicit-return? (by-name 'answer))))
        (is (true? (:implicit-return? (by-name 'private-answer))))
        (is (false? (:public? (by-name 'private-answer))))
        (is (= "answer-test" (:test-name (by-name 'answer-test))))
        (is (= [] (:body (by-name 'answer-test)))))
      (finally (remove-ns namespace-symbol)))))

(deftest container-member-vector-semantics
  (let [namespace-symbol (gensym "aguafria.zig-api-test.members-")
        scratch (create-ns namespace-symbol)
        declarations (runtime/registration-batch)]
    (try
      (binding [*ns* scratch runtime/*registration-batch* declarations]
        (refer 'clojure.core)
        (require '[aguafria.zig :as a])
        (eval '(a/defstruct State
                 [[:counter {:var 1234} :i32]
                  [:limit {:const 99 :doc "A limit."} :i32]
                  [:hidden {:const 8 :private true} :_]
                  [:value {:default 7} :i32]]))
        (eval '(a/defenum Tag
                 [:one [:limit {:const 2} :u8]]))
        (doseq [form '[(a/defstruct Bad [[:value {:var 1 :const 2} :i32]])
                       (a/defstruct Bad [[:value {:var 1 :default 2} :i32]])
                       (a/defstruct Bad [[:value {:const 1 :default 2} :i32]])]]
          (is (thrown? Exception (eval form)))))
      (is (= [:value] (mapv :name (:fields (first (runtime/collected-declarations declarations))))))
      (let [source (emitter/emit-module (str namespace-symbol) (runtime/collected-declarations declarations))]
        (doseq [expected ["pub var counter: i32 = 1234;"
                          "pub const limit: i32 = 99;"
                          "/// A limit."
                          "const hidden = 8;"
                          "value: i32 = 7,"
                          "pub const limit: u8 = 2;"]]
          (is (str/includes? source expected) expected))
        (is (not (str/includes? source "pub const hidden"))))
      (finally (remove-ns namespace-symbol)))))

(deftest named-unions-share-the-existing-container-semantics
  (let [scratch (create-ns (gensym "aguafria.zig-api-test.unions-"))
        declarations (runtime/registration-batch)]
    (try
      (binding [*ns* scratch runtime/*registration-batch* declarations]
        (refer 'clojure.core)
        (require '[aguafria.zig :as a] '[aguafria.keyword :as k])
        (eval '(a/defunion Payload
                 "A documented union."
                 [[:int {:doc "Integer payload."} :i32] [:float :f64]]))
        (eval '(a/defunion Tagged {:attrs #{k/enum}}
                 [[:number :i32] [:empty :void]
                  (a/fn answer :i32 [] 42)]))
        (eval '(a/defunion Packed {:layout :packed} [[:int :i32] [:uint :u32]]))
        (eval '(a/defunion External {:layout :extern} [[:int :i32] [:uint :u32]]))
        (doseq [form '[(a/defunion Bad [:x :i32])
                       (a/defunion Bad [[:x :i32 :i32]])
                       (a/defunion Bad [[:x :i32]] [[:y :i32]])
                       (a/defunion Bad {:attrs k/enum} [[:x :i32]])]]
          (is (thrown? Exception (eval form)) (pr-str form))))
      (let [source (emitter/emit-module (str (ns-name scratch)) (runtime/collected-declarations declarations))
            docs (:doc (meta (ns-resolve scratch 'Payload)))]
        (doseq [expected ["pub const Payload = union {" "/// Integer payload."
                          "pub const Tagged = union(enum) {" "pub fn answer() i32"
                          "pub const Packed = packed union {" "pub const External = extern union {"]]
          (is (str/includes? source expected) source))
        (is (str/includes? docs "A documented union."))
        (is (str/includes? docs "Integer payload.")))
      (finally (remove-ns (ns-name scratch))))))

(deftest vector-types-retain-fields-docs-names-defaults-and-methods
  (let [namespace-symbol (gensym "aguafria.zig-api-test.types-")
        scratch (create-ns namespace-symbol)
        declarations (runtime/registration-batch)]
    (try
      (binding [*ns* scratch runtime/*registration-batch* declarations]
        (refer 'clojure.core)
        (require '[aguafria.zig :as a])
        (eval '(a/defenum Color
                 "A documented enum."
                 {:type :u8}
                 [[:red 1]
                  [:really-red {:doc "Quoted tag." :zig/name "@\"really red\""} 7]]))
        (eval '(a/defstruct Timestamp
                 "Documented timestamp."
                 [[:seconds {:doc "Seconds since the epoch." :default 0} :i64]
                  [:nanos {:doc "Nanoseconds."} :u32]
                  (a/fn- epoch-seconds :i64 [] 0)
                  (a/fn unix-epoch Timestamp
                    "Returns the epoch."
                    []
                    (Timestamp {:seconds (epoch-seconds) :nanos 0}))]))
        (eval '(a/defextern sample :void "Extern docs." {:zig/prefix "extern \"c\""} []))
        (doseq [form '[(a/defextern old :- :void [])
                       (a/defextern old {:zig/prefix "extern"} :- :void [])
                       (a/defstruct Old [:x :u8])
                       (a/defstruct Old [:x :u8] [:y :u8])
                       (a/defstruct Bad [[:x]])
                       (a/defenum Old [:x] [:y])
                       (a/defenum Bad {:argument :u8} [:x])
                       (a/defstruct Bad {:layout :packed :argument :u8} [[:x :u8]])
                       (a/defunion Bad {:argument :u8} [[:x :u8]])
                       (a/defenum Bad [[:x 1 2]])]]
          (is (thrown? Exception (eval form)) (pr-str form))))
      (let [source (emitter/emit-module (str namespace-symbol) (runtime/collected-declarations declarations))]
        (doseq [expected ["enum(u8)" "red = 1" "/// Quoted tag." "@\"really red\" = 7"
                          "/// Seconds since the epoch." "seconds: i64 = 0"
                          "/// Nanoseconds." "/// Returns the epoch."
                          "pub fn unix_epoch() Timestamp" "fn epoch_seconds() i64"
                          "return Timestamp{" "extern \"c\" fn sample() void;"]]
          (is (str/includes? source expected) expected))
        (is (not (str/includes? source "pub fn epoch_seconds")))
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
      (binding [*ns* scratch]
        (refer 'clojure.core)
        (require '[aguafria.zig :as a])
        (eval '(a/defextern absolute :c_int
                 {:zig/name "abs" :zig/prefix "pub extern \"c\""}
                 [[n :c_int]]))
        (eval '(a/defextern missing :void
                 {:zig/name "aguafria_missing_extern_test_symbol"
                  :zig/prefix "pub extern \"c\""} [])))
      (let [absolute (ns-resolve scratch 'absolute)]
        (doseq [[input expected] [[-42 42] [7 7]]]
          (let [result (absolute input)]
            (is (a/zig-value? result))
            (is (= expected (a/value result)))))
        (is (= '([[n :c_int]]) (:arglists (meta absolute))))
        (is (= "Returns: :c_int" (:doc (meta absolute))))
        (is (thrown? clojure.lang.ExceptionInfo (absolute))))
      (let [error (try ((ns-resolve scratch 'missing))
                       nil
                       (catch clojure.lang.Compiler$CompilerException error error))]
        (is (some? error))
        (is (str/includes? (clojure.main/err->msg error) "aguafria_missing_extern_test_symbol")))
      (finally (remove-ns namespace-symbol)))))
