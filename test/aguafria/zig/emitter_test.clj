(ns aguafria.zig.emitter-test
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as a]
            [aguafria.zig.emitter :as emit]
            [aguafria.zig.project :as project]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest source-string-escaping-is-independent-of-repl-print-settings
  (let [text "hello \"☔\"\n"
        expected (emit/emit-expr text)
        identifier (emit/identifier "while")]
    (binding [*print-length* 1 *print-level* 1 *print-meta* true
              *print-dup* true *print-readably* false *print-namespace-maps* false]
      (is (= expected (emit/emit-expr text)))
      (is (= identifier (emit/identifier "while"))))))

(deftest inspection-hooks-preserve-ordinary-emission
  (let [context (the-ns 'aguafria.zig.emitter-test)
        observations (atom [])
        observer (fn [observation]
                   (swap! observations conj observation)
                   (:source observation))]
    (doseq [[form expected]
            [['(a/field receiver :items) "receiver.items"]
             ['(deref pointer) "pointer.*"]
             ['(a/index array index) "array[index]"]
             ['(a/slice array start end) "array[start..end]"]]]
      (is (= expected (emit/emit-expr context form)))
      (is (= expected
             (binding [emit/*expression-observer* observer]
               (emit/emit-expr context form)))))
    (let [form '(ak/+= (a/index array index) 1)
          ordinary (emit/emit-stmt-in context form)]
      (is (= ordinary
             (binding [emit/*expression-observer* observer]
               (emit/emit-stmt-in context form))))
      (is (= ordinary (emit/emit-stmt-in context form))))
    (is (= #{:field :deref :index :slice :statement}
           (set (keep :placement @observations))))
    (is (nil? emit/*expression-observer*))))

(deftest inspection-records-the-operator-selected-by-emission
  (let [context (the-ns 'aguafria.zig.emitter-test)
        observations (atom [])
        observer (fn [observation]
                   (swap! observations conj observation)
                   (:source observation))]
    (doseq [[form native-form]
            [['(+ left right) '(aguafria.keyword/+ left right)]
             ['(- number) '(aguafria.keyword/- number)]
             ['(mod left right) '(aguafria.keyword/mod left right)]
             ['(a/op "+" left right) '(aguafria.keyword/+ left right)]
             ['(a/op "-" number) '(aguafria.keyword/- number)]]]
      (reset! observations [])
      (is (= (emit/emit-expr context form)
             (binding [emit/*expression-observer* observer]
               (emit/emit-expr context form))))
      (is (= native-form (:form (last @observations))))
      (is (= form (:source-form (last @observations)))))
    (reset! observations [])
    (binding [emit/*expression-observer* observer]
      (emit/emit-expr context '(callee (+ left right))))
    (is (= '(callee (+ left right)) (:form (last @observations)))
        "An inner operator must not change its enclosing call's identity")))

(deftest inspection-resolves-native-structural-constructors
  (let [context (the-ns 'aguafria.zig.emitter-test)
        observations (atom [])
        observer (fn [observation]
                   (swap! observations conj observation)
                   (:source observation))]
    (doseq [[form function expected]
            [['(vector [1 2] :u8) 'aguafria.zig/vector "@Vector(2, u8){1, 2}"]
             ['(array [1 2] :u8) 'aguafria.zig/array "[_]u8{1, 2}"]]]
      (is (= expected (emit/emit-expr context form)))
      (reset! observations [])
      (is (= expected
             (binding [emit/*expression-observer* observer]
               (emit/emit-expr context form))))
      (let [metadata (:var-meta (last @observations))]
        (is (= function (symbol (str (ns-name (:ns metadata))) (str (:name metadata)))))))))

(deftest nested-access-expands-to-existing-native-operations
  (let [context (the-ns 'aguafria.zig.emitter-test)]
    (doseq [[compact expanded]
            [['(:x (make-point 3)) '(a/field (make-point 3) :x)]
             ['(-> point :x) '(a/field point :x)]
             ['(:len points) '(a/field points :len)]
             ['(a/get point :x) '(a/field point :x)]
             ['(a/get points i) '(a/index points i)]
             ['(a/get-in points [4 :x]) '(a/field (a/index points 4) :x)]
             ['(a/get-in model [:points i :x])
              '(a/field (a/index (a/field model :points) i) :x)]
             ['(a/get-in grid [1 2]) '(a/index (a/index grid 1) 2)]
             ['(a/get-in points []) 'points]]]
      (is (= (emit/emit-expr context expanded)
             (emit/emit-expr context compact))))))

(deftest keyword-field-access-rejects-invalid-arity
  (doseq [form '[(:x) (:x point 0)]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one value"
                          (emit/emit-expr form)))))

(deftest inferred-member-calls-are-not-keyword-field-access
  (let [context (the-ns 'aguafria.zig.emitter-test)]
    (doseq [[form expected] [['(:.init) ".init()"]
                             ['(:.init 7) ".init(7)"]
                             ['(:.fromPair 1 2) ".fromPair(1, 2)"]]]
      (is (= expected (emit/emit-expr form)))
      (is (= expected (emit/emit-expr context form))))))

(deftest keyword-labeled-native-blocks
  (let [context (the-ns 'aguafria.zig.emitter-test)
        form '(a/with-block :result (ak/break :result 42))]
    (is (= "result: {\n    break :result 42;\n}"
           (emit/emit-expr context form)))
    (is (= "result: {\n    break :result 42;\n}"
           (emit/emit-stmt-in context form)))
    (doseq [form '[(a/with-block result (ak/break result 42))
                   (a/with-block "result" (ak/break "result" 42))
                   (a/with-block)]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"keyword label"
                            (emit/emit-expr context form))))))

(deftest scoped-captures-respect-inner-bindings-and-field-names
  (let [context (the-ns 'aguafria.zig.emitter-test)
        enclosing '[input item point]]
    (is (= '[input]
           (emit/scoped-captures context
                                 '(a/with-block :result
                                    (let [input (ak/+ input 1)] (ak/break :result input)))
                                 enclosing)))
    (is (= '[point]
           (emit/scoped-captures context '(a/field point input) enclosing)))
    (is (= '[input]
           (emit/scoped-captures context
                                 '(a/with-block :result
                                    (ak/const item input)
                                    (ak/for [point item] (ak/= :_ point))
                                    (ak/break :result item))
                                 enclosing)))
    (is (empty? (emit/scoped-captures context
                                      '(a/with-block :result
                                         (let [input 7] (ak/break :result input)))
                                      enclosing)))
    (is (= '[input]
           (emit/scoped-captures context
                                 '(a/with-block :result
                                    (let [item (ak/var input :i32)]
                                      (ak/break :result item)))
                                 enclosing)))
    (is (= '[point]
           (emit/scoped-captures context
                                 '(a/with-block :result
                                    (ak/for [{:keys [input]} point]
                                      (ak/= :_ input)))
                                 enclosing)))
    (is (= '[point]
           (emit/scoped-captures context
                                 '(a/if-capture {:payload [input] :error [item]}
                                                point (ak/+ input 1) (ak/= :_ item))
                                 enclosing)))
    (is (= '[point]
           (emit/scoped-captures context
                                 '(a/while-loop {:payload [input] :error [item]
                                                 :continue [(ak/= :_ input)]
                                                 :else [(ak/= :_ item)]}
                                                point (ak/= :_ input))
                                 enclosing)))
    (is (= '[input]
           (emit/scoped-captures context
                                 '(a/with-block :result
                                    (a/fn helper :i32 [[point :i32]] (ak/+ point input)))
                                 enclosing)))))

(deftest array-elements-and-native-operator-vars
  (let [context (the-ns 'aguafria.zig.emitter-test)]
    (is (= "[_]i32{1, 2}" (emit/emit-expr context '(a/array [1 2] :i32))))
    (is (= "[2]i32{1, 2}" (emit/emit-expr context '(a/init [1 2] [:array 2 :i32]))))
    (doseq [[form source] [['(ak/++ left right) "(left ++ right)"]

                           ['(ak/|| A B) "(A || B)"]
                           ['(ak/<<| a b) "(a <<| b)"]
                           ['(ak/... 2 8) "2 ... 8"]]]
      (is (= source (emit/emit-expr context form))))
    (is (thrown? clojure.lang.ExceptionInfo (emit/emit-expr context '(a/array [1]))))))

(deftest vector-constructor-infers-lane-count
  (let [context (the-ns 'aguafria.zig.emitter-test)]
    (doseq [[elements type] [[[1 2 3 4] :i32] [[true false] :bool] [[] :f32]]]
      (is (= (emit/emit-expr context (list 'a/init elements [:vector (count elements) type]))
             (emit/emit-expr context (list 'a/vector elements type)))))
    (doseq [form '[(a/vector [1]) (a/vector 1 :i32) (a/vector [1] {} :i32)]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"vector expects"
                            (emit/emit-expr context form))))))

(deftest sentinel-array-options
  (doseq [[form expected]
          [['(a/array [1 25 3 4] {:sentinel 0} :u8) "[_:0]u8{1, 25, 3, 4}"]
           ['(a/array [] {:sentinel 255} :u8) "[_:255]u8{}"]
           ['(a/array [true] {:sentinel false} :bool) "[_:false]bool{true}"]
           ['(a/array [1] {} :u8) "[_]u8{1}"]]]
    (is (= expected (emit/emit-expr (the-ns 'aguafria.zig.emitter-test) form))))
  (doseq [form '[(a/array [1] {:sentinal 0} :u8)
                 (a/array [1] :sentinel :u8)
                 (a/array [1] {:sentinel 0 :length 1} :u8)]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"array options"
                          (emit/emit-expr (the-ns 'aguafria.zig.emitter-test) form)))))

(deftest array-type-options
  (doseq [[schema expected]
          [[[:array 4 {:sentinel 0} :u8] "[4:0]u8"]
           [[:array 0 {:sentinel false} :bool] "[0:false]bool"]
           [[:array 4 {} :u8] "[4]u8"]
           [[:array 2 [:array 3 {:sentinel 0} :u8]] "[2][3:0]u8"]]]
    (is (= expected (emit/emit-type schema))))
  (doseq [schema [[:array-sentinel 4 0 :u8]
                  [:array 4 {:sentinal 0} :u8]
                  [:array 4 0 :u8]
                  [:array 4 {:sentinel 0} :u8 :extra]]]
    (is (thrown? clojure.lang.ExceptionInfo (emit/emit-type schema)))))

(deftest flat-native-for-bindings
  (let [context (the-ns 'aguafria.zig.emitter-test)]
    (is (= "for ((&items), 0..) |*item, index| {\n    item.* = @intCast(index);\n}"
           (emit/emit-stmt-in context
                              '(ak/for [(ak/* item) (ak/& items) index (a/range 0)]
                                 (ak/= @item (ak/intCast index))))))
    (is (= "2 .. 8" (emit/emit-expr context '(a/range 2 8))))
    (doseq [form '[(ak/for [[item items]] (use item))
                   (ak/for [item items index] (use item))
                   (ak/for [(ak/* a b) items] (use a))
                   (a/range)
                   (a/range 1 2 3)
                   (ak/* item)]]
      (is (thrown? clojure.lang.ExceptionInfo (emit/emit-expr context form))))))

(deftest stored-references-are-not-qualified-twice
  (let [source (create-ns (gensym "reference-source-"))
        consumer (create-ns (gensym "reference-consumer-"))
        target (intern source 'worker (fn []))]
    (try
      (alter-meta! target assoc :aguafria/zig-reference
                   {:kind :declaration :module (str (ns-name source))
                    :symbol (symbol (str (ns-name source)) "worker")
                    :zig-name "worker"})
      (binding [*ns* consumer]
        (alias 'source (ns-name source)))
      (let [once (emit/qualify-form consumer 'source/worker)
            twice (emit/qualify-form consumer once)
            reference (:aguafria/zig-reference (meta twice))]
        (is (= reference (:aguafria/zig-reference
                          (meta (emit/qualify-form consumer twice)))))
        (is (= (str (:import-alias reference) ".worker") (:zig-name reference)))
        (is (= "worker" (:zig-name (:aguafria/zig-reference
                                    (meta (emit/qualify-form source twice)))))))
      (let [qualified (emit/qualify-form consumer 'source/worker)
            typed (vary-meta qualified assoc-in [:aguafria/zig-reference :kind] :struct)
            repeated (emit/qualify-form consumer typed)
            reference (:aguafria/zig-reference (meta repeated))]
        (is (= (str (:import-alias reference) ".worker") (:zig-name reference))
            "typed return descriptors may retain their container kind"))
      (finally
        (remove-ns (ns-name consumer))
        (remove-ns (ns-name source))))))

(deftest import-discovery-walks-symbol-metadata-and-retains-conflicts
  (let [reference (fn [module order]
                    (with-meta 'dependency/value
                      {:aguafria/zig-reference
                       {:import-alias "dependency" :import-name module
                        :import-namespace (symbol module) :source-order order}}))
        nested (reference "library" 7)
        receiver (with-meta 'receiver
                   {:zig/type [:optional [:* nested]]})
        declaration {:body [(list 'consume {:items [receiver]})]}
        imports (emit/declaration-imports [declaration])]
    (is (= {"dependency" {:alias "dependency" :import-name "library"
                          :namespace 'library :source-order 7}}
           imports))
    (is (= imports (emit/declaration-imports [declaration declaration])))
    (is (= 7 (get-in (emit/declaration-imports
                      [declaration {:body [(reference "library" 9)]}])
                     ["dependency" :source-order])))
    (let [conflicting (reference "other.library" 9)
          error (try
                  (emit/declaration-imports [declaration {:body [conflicting]}])
                  (catch clojure.lang.ExceptionInfo error error))]
      (is (instance? clojure.lang.ExceptionInfo error))
      (is (= "Two required namespaces resolve to the same Zig import alias"
             (ex-message error)))
      (is (identical? conflicting (:form (ex-data error)))))
    (let [explicit {:kind :const :name 'dependency
                    :attributes {:zig/import-name "library"
                                 :zig/import-namespace 'library}}]
      (is (= imports (emit/declaration-imports [explicit declaration]))))
    (doseq [key [:var :tag]]
      (is (= imports
             (emit/declaration-imports
              [{:body [(with-meta 'receiver {key nested})]}]))))
    (let [changed (assoc declaration :body [(reference "updated.library" 8)])]
      (is (= "updated.library"
             (get-in (emit/declaration-imports [changed])
                     ["dependency" :import-name]))))))

(deftest lexical-bindings-shadow-namespace-aliases
  (let [context (the-ns 'aguafria.zig.emitter-test)]
    (doseq [body ['(ak/var a :u32)
                  '(let [a 42] a)]]
      (let [declaration (emit/prepare-declaration
                         context
                         {:kind :fn :name 'read-value :return :u32
                          :args [{:name 'a :type :u32}]
                          :body [body]})]
        (is (empty? (emit/declaration-imports [declaration])))))
    (let [declaration (emit/prepare-declaration
                       context
                       {:kind :fn :name 'read-value :return :u32
                        :args [{:name 'a :type :u32}]
                        :body ['(a/field a :value)]})]
      (is (= '[(field a :value)] (:body declaration)))
      (is (empty? (emit/declaration-imports [declaration]))))))

(deftest captured-names-shadow-aliases-only-in-their-body
  (let [context (the-ns 'aguafria.zig.emitter-test)]
    (doseq [form '[(a/while-loop {:payload [ak]} optional (ak/= :_ ak))
                   (a/if-capture {:payload [ak]} optional ak 0)
                   (case [:some] [ak] ak)
                   (a/catch-capture [ak] fallible ak)
                   (ak/errdefer [ak] (ak/= :_ ak))]]
      (let [qualified (emit/qualify-form context form)]
        (is (empty? (emit/declaration-imports [{:body [qualified]}])) (pr-str qualified))))
    (let [qualified (emit/qualify-form
                     context '(a/while-loop {:payload [a]} a (ak/= :_ a)))
          condition (nth qualified 2)
          use (last (last qualified))]
      (is (= :namespace-root (:kind (:aguafria/zig-reference (meta condition)))))
      (is (nil? (:aguafria/zig-reference (meta use)))))))

(deftest nested-functions-shadow-namespace-aliases
  (let [context (the-ns 'aguafria.zig.emitter-test)
        declaration (emit/prepare-declaration
                     context
                     {:kind :const :name 'Example
                      :value '(a/struct
                               [(a/fn from-local :u32 []
                                  (ak/const ak 7)
                                  (ak/return ak))
                                (a/fn from-parameter :u32 [[a :u32]]
                                  a)])})
        source (emit/emit-static-dependency-module "nested-shadow" [declaration])]
    (is (empty? (emit/declaration-imports [declaration])))
    (is (str/includes? source "const ak = 7;"))
    (is (str/includes? source "return ak;"))
    (is (str/includes? source "return a;"))))

(deftest container-functions-shadow-namespace-aliases
  (let [context (the-ns 'aguafria.zig.emitter-test)
        declaration (emit/prepare-declaration
                     context
                     {:kind :const :name 'Example
                      :value '(a/struct
                               [(a/fn a :u32 [] 7)
                                (a/fn run :u32 [] (a))])})
        source (emit/emit-static-dependency-module "container-shadow" [declaration])]
    (is (empty? (emit/declaration-imports [declaration])))
    (is (str/includes? source "return a();"))
    (is (not (str/includes? source "@import(\"aguafria.zig\")")))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Unresolved Zig reference"
         (emit/prepare-declaration
          context {:kind :const :name 'Unknown
                   :value '(a/struct [(a/fn run :u32 [] (missing))])}))))
  (let [context (the-ns 'aguafria.zig.emitter-test)
        value '(a/struct [(a/fn run :u32 [] (a))
                          (a/fn a :u32 [] 7)])]
    (with-redefs [project/converted-module? (constantly true)]
      (let [declaration (emit/prepare-declaration
                         context {:kind :const :name 'Converted :value value})]
        (is (empty? (emit/declaration-imports [declaration])))))))

(deftest parameter-names-shadow-aliases-in-dependent-signatures
  (let [context (the-ns 'aguafria.zig.emitter-test)
        declaration (emit/prepare-declaration
                     context
                     {:kind :fn :name 'identity-value
                      :return '(ak/TypeOf ak)
                      :args [{:name 'ak :type :anytype}
                             {:name 'other :type '(ak/TypeOf ak)}]
                      :body ['other]})]
    (is (empty? (emit/declaration-imports [declaration])))
    (is (= '(aguafria.keyword/TypeOf ak) (:return declaration)))
    (is (= '(aguafria.keyword/TypeOf ak) (get-in declaration [:args 1 :type])))
    (is (not (str/includes?
              (emit/emit-static-dependency-module "signature-test" [declaration])
              "@import(\"aguafria.keyword\")")))))

(deftest anonymous-containers-use-one-member-vector
  (let [context (the-ns 'aguafria.zig.emitter-test)]
    (doseq [[form expected]
            [['(a/struct [[:x {:doc "Coordinate" :default 3} :u8]
                          (a/fn-decl answer :u8 [] 42)])
              ["struct {" "/// Coordinate" "x: u8 = 3" "fn answer() u8" "return 42;"]]
             ['(a/enum {:type :u8}
                       [:red [:blue {:doc "Blue" :zig/name "@\"deep blue\""} 4]])
              ["enum(u8)" "red," "/// Blue" "@\"deep blue\" = 4"]]
             ['(a/union {:enum? true} [[:value :u32] [:empty :void]])
              ["union(enum)" "value: u32" "empty: void"]]
             ['(a/struct {:layout :packed :type :u16}
                         [[:low :u8] [:high :u8]])
              ["packed struct(u16)" "low: u8" "high: u8"]]
             ['(a/opaque [(a/fn-decl size :usize [] 0)])
              ["opaque {" "fn size() usize"]]
             ['(a/struct [(a/struct-decl Nested [[:value :u8]])])
              ["const Nested = struct" "value: u8"]]]]
      (let [source (emit/emit-expr context form)]
        (doseq [fragment expected]
          (is (str/includes? source fragment) (str form " => " source)))))
    (doseq [form '[(a/struct [:x :u8])
                   (a/struct [:x :u8] [:y :u8])
                   (a/struct [[:x :u8 (a/fn-decl method :void [])]])
                   (a/enum [:a] [:b])
                   (a/enum [[:a 1 2]])
                   (a/union [:x :u8])
                   (a/opaque)
                   (a/container {:kind :struct} (a/field-decl :x :u8))]]
      (is (thrown? Exception (emit/emit-expr context form)) (pr-str form)))))

(deftest compiler-modules-preserve-inner-source-locations
  (doseq [kind [:fn :test :comptime]]
    (let [statement (with-meta '(consume value) {:line 17 :column 5})
          declaration {:kind kind :name 'exercise :return :void :args []
                       :body [statement]
                       :source {:file "fixture.clj" :line 9 :column 1}
                       :emit-source-comment? false}
          source (emit/emit-module "fixture" [declaration])]
      (is (str/includes? source "// Aguafria form: 17:5") (str kind)))))

(deftest keyword-field-names-are-quoted-native-identifiers
  (doseq [field [:enum :fn :struct :union :error :type :test]]
    (let [expected (if (= field :type) "type" (str "@\"" (name field) "\""))]
      (is (= (str "information." expected)
             (emit/emit-expr (list 'field 'information field))))))
  (is (= "information.fields" (emit/emit-expr '(field information :fields))))
  (is (= "tuple.@\"3\"" (emit/emit-expr '(field tuple :3))))
  (doseq [member ["@\"align\"" "@\"two words\"" "false"]]
    (is (= (str "information." member)
           (emit/emit-expr (list 'field 'information
                                 (list 'identifier-literal member)))))))

(deftest clojure-local-names-can-use-zig-reserved-words
  (is (= "anytype" (emit/emit-type :anytype)))
  (is (= "anyframe" (emit/emit-type :anyframe)))
  (is (= "@\"error\"" (emit/identifier 'error)))
  (is (= "const @\"error\" = failure;" (emit/emit-stmt '(const error failure))))
  (is (= "consume(@\"error\");" (emit/emit-stmt '(consume error))))
  (is (= "error" (emit/identifier "error")) "Exact Zig strings are not rewritten."))

(deftest unreachable-forms-are-valid-expressions
  (is (= "unreachable" (emit/emit-expr '(unreachable))))
  (is (thrown? clojure.lang.ExceptionInfo (emit/emit-expr '(unreachable 1))))
  (is (str/includes? (emit/emit-expr '(switch value (case-else (comptime (unreachable)))))
                     "else => comptime unreachable")))

(deftest reader-dereference-is-native-pointer-access
  (let [form (read-string "@pointer")]
    (is (= '(clojure.core/deref pointer) form))
    (is (= '(deref pointer) (emit/qualify-form *ns* form)))
    (is (= "pointer.*" (emit/emit-expr form)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unresolved Zig reference"
                          (emit/qualify-form *ns* '(nonexistent/deref pointer))))))

(deftest direct-comptime-let-is-a-block-not-an-expression-statement
  (let [source (emit/emit-stmt '(comptime
                                 (let [^{:var :i32} value 1]
                                   (set! value 2))) 0)]
    (is (str/includes? source "comptime {"))
    (is (str/includes? source "var value: i32 = 1;"))
    (is (not (str/includes? source "};")))))

(deftest array-constructor-alignment-belongs-to-storage-not-the-element-type
  (is (= [:array :_ :u8] (emit/array-initializer-type [[1 2] {:align 4} :u8])))
  (is (= [:array :_ {:sentinel 0} :u8]
         (emit/array-initializer-type [[1 2] {:align 4 :sentinel 0} :u8])))
  (is (str/includes? (emit/emit-stmt '(let [bytes (array [1 2] {:align 4} :u8)]))
                     "const bytes align(4) = [_]u8{1, 2};")))

(deftest comptime-uses-the-enclosing-expression-or-statement-context
  (let [context (the-ns 'aguafria.zig.emitter-test)
        expression (emit/qualify-form context '(ak/comptime (ak/+ 20 22)))
        statement (emit/qualify-form context '(ak/comptime (ak/unreachable)))]
    (is (= "(comptime (20 + 22))" (emit/emit-expr expression)))
    (is (= "return (comptime (20 + 22));"
           (emit/emit-function-body [expression] :i32)))
    (is (= "comptime unreachable;" (emit/emit-stmt statement)))
    (doseq [block ['(do (validate)) '(block (validate))]]
      (is (= "comptime {\n    validate();\n}"
             (emit/emit-stmt (list 'comptime block)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"comptime expects one"
                          (emit/emit-stmt '(comptime))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"comptime expects one"
                          (emit/emit-stmt '(comptime 1 2))))
    (is (not (contains? (emit/syntax-operators) 'comptime-stmt)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unresolved Zig reference"
                          (emit/qualify-form context '(aguafria.zig/comptime-stmt 1))))))

(deftest type-emission-test
  (is (= "i32" (emit/emit-type :i32)))
  (is (= "!void" (emit/emit-type :!void)))
  (is (= "!u32" (emit/emit-type :!u32)))
  (is (= "!bool" (emit/emit-type :!bool)))
  (is (= "!Result" (emit/emit-type :!Result)))
  (is (= "![]const u8" (emit/emit-type [:! [:slice-const :u8]])))
  (is (= (emit/emit-type [:error-union [:optional :u32]])
         (emit/emit-type [:! [:optional :u32]])))
  (is (thrown? clojure.lang.ExceptionInfo (emit/emit-type [:!])))
  (is (thrown? clojure.lang.ExceptionInfo (emit/emit-type [:! :u32 :u8])))
  (is (thrown? clojure.lang.ExceptionInfo (emit/emit-type '[! :u32])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires a payload"
                        (emit/emit-type :!)))
  (is (= "Point" (emit/emit-type 'Point)))
  (is (= "*const i32" (emit/emit-type [:*const :i32])))
  (is (= "[*:0]const u8" (emit/emit-type [:sentinel-const :u8 0])))
  (is (= "[]const u8" (emit/emit-type [:slice-const :u8])))
  (doseq [[schema expected]
          [[[:* {:size :slice :sentinel nil} [:optional :u8]] "[:null]?u8"]
           [[:* {:size :many :sentinel nil :const? true} [:optional :u8]]
            "[*:null]const ?u8"]
           [[:* {:size :slice :sentinel false} :bool] "[:false]bool"]
           [[:* {:size :slice} [:optional :u8]] "[]?u8"]]]
    (is (= expected (emit/emit-type schema))))
  (is (= "[4]f32" (emit/emit-type [:array 4 :f32])))
  (is (= "@Vector(4, f32)" (emit/emit-type [:vector 4 :f32])))
  (is (= "[*c]u8" (emit/emit-type [:c-pointer :u8])))
  (is (= "?!i32"
         (emit/emit-type [:optional [:error-union :i32]])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unknown composite Zig type"
                        (emit/emit-type [:mystery :i32]))))

(deftest clojure-identifier-emission-test
  (is (= "circle_contains_q" (emit/identifier 'circle-contains?)))
  (is (= "reset_bang" (emit/identifier 'reset!))))

(deftest callback-parameter-names-are-data
  (let [callback (fn [names]
                   [:*const
                    [:fn {:callconv :.c}
                     (mapv (fn [parameter type]
                             {:name parameter :type type})
                           names [[:c-pointer :u8] :i32 :i32])
                     :u32]])
        keywords (callback [:output :frame-width :frame-height])
        symbols (callback '[output frame_width frame_height])]
    (is (= "*const fn (output: [*c]u8, frame_width: i32, frame_height: i32) callconv(.c) u32"
           (emit/emit-type keywords)))
    (is (= (emit/emit-type symbols) (emit/emit-type keywords))
        "Keyword name data preserves the existing callback ABI and spelling")
    (let [qualified (emit/qualify-type (the-ns 'aguafria.zig.emitter-test)
                                       (callback '[a ak b]))]
      (is (empty? (emit/declaration-imports [{:type qualified}])))
      (is (= '[a ak b] (mapv :name (get-in qualified [1 2]))))))
  (let [signature '[:fn {} [{:name a :type :type :prefix "comptime"}
                            {:name value :type a}] a]
        qualified (emit/qualify-type (the-ns 'aguafria.zig.emitter-test) signature)]
    (is (= signature qualified))
    (is (empty? (emit/declaration-imports [{:type qualified}])))))

(deftest static-dependency-demotes-default-exports-without-development-containers-test
  (let [source
        (emit/emit-static-dependency-module
         "demo.dependency"
         [{:kind :fn
           :module "demo.dependency"
           :name 'initialize!
           :declaration-key [:fn 'initialize!]
           :args []
           :return :bool
           :body [true]
           :public? true
           :export? true
           :source {:file "demo/dependency.clj" :line 7 :column 1}}])]
    (is (str/includes? source
                       "pub fn initialize_bang() callconv(.c) bool"))
    (is (not (str/includes? source "export fn initialize_bang")))
    (is (not (str/includes? source "__aguafria_type__")))
    (is (str/includes? source
                       "Aguafria declaration: demo.dependency/initialize!"))))

(deftest unevaluated-cross-namespace-name-is-rejected-test
  (let [provider-symbol 'aguafria.emitter-forward-provider
        caller-symbol 'aguafria.emitter-forward-caller
        provider-ns (create-ns provider-symbol)
        caller-ns (create-ns caller-symbol)]
    (try
      (project/register-catalog!
       {:schema-version 1
        :modules {(str provider-symbol) {}}})
      (binding [*ns* caller-ns]
        (alias 'provider provider-symbol)
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Unresolved Zig reference.*tick-auto"
             (emit/prepare-declaration
              caller-ns
              {:kind :fn
               :name 'run
               :args []
               :return :u32
               :body '((provider/tick-auto))
               :public? true
               :implicit-return? true}))))
      (finally
        (remove-ns caller-symbol)
        (remove-ns provider-symbol)))))

(deftest fully-qualified-macro-import-is-one-zig-identifier
  (let [provider-symbol 'aguafria.emitter-macro-provider
        caller-symbol 'aguafria.emitter-macro-caller
        provider-ns (create-ns provider-symbol)
        caller-ns (create-ns caller-symbol)]
    (try
      (project/register-catalog!
       {:schema-version 1 :modules {(str provider-symbol) {}}})
      (intern provider-ns
              (with-meta 'tick-auto
                {:aguafria/zig-reference
                 {:kind :declaration :module (str provider-symbol)
                  :zig-name "tick_auto" :symbol (symbol (str provider-symbol) "tick-auto")}})
              nil)
      (intern provider-ns (with-meta 'invoke-native {:macro true})
              (fn [_form _env] (list (symbol (str provider-symbol) "tick-auto"))))
      (binding [*ns* caller-ns]
        (alias 'provider provider-symbol)
        (let [declaration
              (emit/prepare-declaration caller-ns
                                        {:kind :fn :name 'run :args [] :return :u32
                                         :body '((provider/invoke-native)) :public? true :implicit-return? true})
              alias "@\"aguafria.emitter_macro_provider\""
              source (emit/emit-module (str caller-symbol) [declaration])]
          (is (str/includes? source (str "const " alias " = @import(")))
          (is (str/includes? source (str "return " alias ".tick_auto();")))
          (is (= [alias] (vec (keys (emit/declaration-imports [declaration])))))))
      (finally
        (remove-ns caller-symbol)
        (remove-ns provider-symbol)))))

(deftest converted-references-require-a-parsed-declaration
  (let [context (the-ns 'aguafria.zig.emitter-test)
        module "aguafria.emitter-catalog-provider"
        declaration {:kind :fn :name 'run :return :u32 :args []}]
    (project/register-catalog!
     {:schema-version 1
      :modules {module {:source-kind :zig :source-orders {"answer" 0}}}})
    (is (map? (emit/validate-declaration-references!
               context (assoc declaration :body [(list (symbol module "answer"))]) #{})))
    (doseq [reference [(symbol module "missing") 'answer]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Unresolved Zig reference"
           (emit/validate-declaration-references!
            context (assoc declaration :body [(list reference)]) #{}))))
    (project/register-catalog!
     {:schema-version 1
      :modules {module {:source-kind :aguafria :source-orders {"answer" 0}}}})
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Unresolved Zig reference"
         (emit/validate-declaration-references!
          context (assoc declaration :body [(list (symbol module "answer"))]) #{})))))

(deftest rehomed-references-use-the-registered-module-scope
  (let [context (the-ns 'aguafria.zig.emitter-test)
        declaration {:kind :fn :module "editor.rehomed" :name 'read-counter
                     :return :i32 :args [] :body '[editor.rehomed/counter]}]
    (is (map? (emit/validate-declaration-references! context declaration '#{counter})))
    (doseq [reference '[editor.rehomed/missing other.module/counter]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Unresolved Zig reference"
           (emit/validate-declaration-references!
            context (assoc declaration :body [reference]) '#{counter}))))))

(deftest qualified-validation-does-not-enumerate-the-namespace-scope
  (let [context (the-ns 'aguafria.zig.emitter-test)
        declaration {:kind :fn :module "fixture.large-scope" :name 'answer
                     :return :u32 :args [] :body '[fixture.large-scope/item0]}
        names (into #{} (map #(symbol (str "item" %))) (range 10000))
        qualified-symbols (atom 0)
        make-symbol symbol]
    (with-redefs [clojure.core/symbol
                  (fn
                    ([name] (make-symbol name))
                    ([module name]
                     (when (= "fixture.large-scope" module)
                       (swap! qualified-symbols inc))
                     (make-symbol module name)))]
      (is (= declaration
             (emit/validate-declaration-references! context declaration names))))
    (is (zero? @qualified-symbols))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Unresolved Zig reference"
         (emit/validate-declaration-references!
          context (assoc declaration :body '[(let [local 1] fixture.large-scope/local)])
          names)))))

(deftest clojure-macros-expand-in-declaration-test
  (let [context-ns (the-ns 'aguafria.zig.emitter-test)]
    (is (= '[(transform value 1)]
           (:body
            (emit/prepare-declaration
             context-ns
             {:kind :fn
              :name 'threaded
              :args [{:name 'value :type :i32}
                     {:name 'transform :type [:*const [:fn [:i32 :i32] :i32]]}]
              :return :i32
              :body '((-> value (transform 1)))})))))
  (let [context-ns (the-ns 'aguafria.zig.emitter-test)
        declaration
        (emit/prepare-declaration
         context-ns
         {:kind :fn
          :name 'cast-pointer
          :args [{:name 'pointer :type [:optional [:* :anyopaque]]}
                 {:name 'Widget :type :type}]
          :return :void
          :body '((-> pointer (a/cast [:* Widget])))})]
    (is (= "@as(*Widget, @ptrCast(@alignCast(pointer.?)))"
           (emit/emit-expr context-ns (first (:body declaration))))))
  (let [context-ns (the-ns 'aguafria.zig.emitter-test)
        declaration
        (emit/prepare-declaration
         context-ns
         {:kind :fn
          :name 'choose
          :args [{:name 'value :type :i32}]
          :return :i32
          :body '((cond (ak/== value 0) 10
                        (ak/== value 1) 20
                        :else 30))})]
    (is (= '(if (aguafria.keyword/== value 0)
              10
              (if (aguafria.keyword/== value 1) 20 30))
           (first (:body declaration))))))

(deftest local-callable-shadows-clojure-core-macro-test
  (let [context-ns (the-ns 'aguafria.zig.emitter-test)
        declaration
        (emit/prepare-declaration
         context-ns
         {:kind :fn
          :name 'check
          :args [{:name 'checker :type [:*const [:fn [:bool [:slice-const :u8]] :void]]}]
          :return :void
          :body '((ak/const assert checker)
                  (assert true "from Zig"))})]
    (is (= '[(const assert checker)
             (assert true "from Zig")]
           (:body declaration))))
  (let [context-ns (the-ns 'aguafria.zig.emitter-test)
        declaration
        (emit/prepare-declaration
         context-ns
         {:kind :fn
          :name 'factory
          :args []
          :return :type
          :body '((a/struct
                   [(a/fn-decl sync :void [])
                    (a/fn-decl call-sync :void [] (sync))]))})]
    (is (some #{'(sync)} (tree-seq coll? seq (:body declaration))))))

(deftest expression-emission-test
  (is (= "(a + (b * 2))" (emit/emit-expr '(+ a (* b 2)))))
  (is (= "@mod(counter, 5)" (emit/emit-expr '(mod counter 5))))
  (is (= "@max(a, b)"
         (emit/emit-expr (the-ns 'aguafria.zig.emitter-test)
                         '(ak/max a b))))
  (is (= "(~bits)" (emit/emit-expr '(op "~" bits))))
  (is (= "(if ((x < 0)) (-x) else x)"
         (emit/emit-expr '(if (< x 0) (- x) x))))
  (is (= "point.x" (emit/emit-expr '(field point x))))
  (is (= "tuple.@\"3\"" (emit/emit-expr '(field tuple :3))))
  (is (= "tuple.@\"3\"[0]" (emit/emit-expr '(index (field tuple :3) 0))))
  (is (= "items[start..end]" (emit/emit-expr '(slice items start end))))
  (is (= "0 .. 10" (emit/emit-expr '(op ".." 0 10))))
  (is (= "?*u8" (emit/emit-expr '(type [:optional [:* :u8]]))))
  (is (= "(Foo{.x = 1}).stat()"
         (emit/emit-expr '((field (init {:x 1} Foo) stat)))))
  (is (= ".{.z = 3, .a = 1, .m = 2}"
         (emit/emit-expr '(object [[:z 3] [:a 1] [:m 2]]))))
  (is (= "Foo{.z = 3, .a = 1}"
         (emit/emit-expr '(init (object [[:z 3] [:a 1]]) Foo))))
  (let [foo (with-meta 'Foo
              {:aguafria/zig-reference
               {:kind :declaration
                :declaration-kind :struct
                :zig-name "Foo"
                :type-reference? true}})]
    (is (= "Foo{.a = 1, .z = 3}"
           (emit/emit-expr (list foo {:z 3 :a 1}))))
    (is (= "Foo(.{.a = 1, .z = 3})"
           (emit/emit-expr '(Foo {:z 3 :a 1})))
        "An unmarked function call receiving a map must stay a function call"))
  (testing "postfix expressions preserve Zig precedence"
    (is (= "b.step(\"check\", \"Check\")"
           (emit/emit-expr '((field b step) "check" "Check"))))
    (is (= "(try file.stat(io)).size"
           (emit/emit-expr '(field (try ((field file stat) io)) size)))))
  (is (str/starts-with?
       (emit/emit-expr
        '(container {:kind :struct :layout :packed :type :u16}
                    [(field-decl bits :u16)]))
       "packed struct(u16)"))
  (is (= (str "enum {\n"
              "    /// Waiting for work.\n"
              "    waiting,\n"
              "}")
         (emit/emit-expr
          '(container {:kind :enum :layout :normal}
                      [(enum-field-decl waiting "Waiting for work.")]))))
  (is (= ".{.x = 1, .y = 2}" (emit/emit-expr {:y 2 :x 1})))
  (is (= ".{1, 2, 3}" (emit/emit-expr [1 2 3])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"expects at least two operands"
                        (emit/emit-expr '(== 1)))))

(deftest mutable-binding-type-shorthand-test
  (doseq [type [:i32 [:array 4096 :u8] 'Point [:* 'Point]]
          emit-form [emit/emit-stmt emit/emit-expr]]
    (let [form (fn [metadata]
                 (list 'let [(with-meta 'buffer metadata) 'ak/undefined]
                       'buffer))]
      (is (= (emit-form (form {:var true :zig/type type}))
             (emit-form (form {:var type})))
          (str "Equivalent mutable type syntax for " type))))
  (is (str/includes?
       (emit/emit-stmt '(let [^{:var [:array 4096 :u8]} buffer ak/undefined]
                          (set! (a/index buffer 0) 42)))
       "var buffer: [4096]u8 = undefined;"))
  (testing "boolean mutability retains inference and explicit types take precedence"
    (is (str/includes? (emit/emit-stmt '(let [^:var value 1] value))
                       "var value = 1;"))
    (is (str/includes? (emit/emit-stmt '(let [^{:var false} value 1] value))
                       "const value = 1;"))
    (is (str/includes?
         (emit/emit-stmt '(let [^{:var :u8 :zig/type :u16} value 1] value))
         "var value: u16 = 1;"))))

(deftest local-binding-metadata-qualifies-imported-types-test
  (let [provider-symbol 'aguafria.emitter-local-type-provider
        caller-symbol 'aguafria.emitter-local-type-caller
        provider-ns (create-ns provider-symbol)
        caller-ns (create-ns caller-symbol)]
    (try
      (intern provider-ns
              (with-meta 'Point
                {:aguafria/zig-reference
                 {:kind :declaration :module (str provider-symbol)
                  :zig-name "Point" :symbol (symbol (str provider-symbol) "Point")
                  :type-reference? true}}) nil)
      (binding [*ns* caller-ns] (alias 'provider provider-symbol))
      (doseq [metadata [{:var [:array 8 'provider/Point]}
                        {:var true :zig/type [:array 8 'provider/Point]}
                        {:var true :tag [:array 8 'provider/Point]}]]
        (let [declaration
              (emit/prepare-declaration caller-ns
                                        {:kind :fn :name 'use-points :args [] :return :void
                                         :body [(list 'let [(with-meta 'points metadata)
                                                            'aguafria.keyword/undefined]
                                                      '(aguafria.keyword/= :_ points))]})
              ;; Emission occurs outside the original caller namespace.
              source (emit/emit-declaration declaration)]
          (is (str/includes? source "var points: [8]provider.Point = undefined;")
              source)))
      (finally
        (remove-ns caller-symbol)
        (remove-ns provider-symbol)))))

(deftest statement-emission-test
  (is (= "const answer: i32 = 42;"
         (emit/emit-stmt '(const answer :i32 42))))
  (is (= "total += value;" (emit/emit-stmt '(+= total value))))
  (is (= "total /= divisor;"
         (emit/emit-stmt '(assign "/=" total divisor))))
  (is (= "defer cleanup();" (emit/emit-stmt '(defer (cleanup)))))
  (is (= "errdefer cleanup();" (emit/emit-stmt '(errdefer (cleanup)))))
  (is (= "comptime validate();"
         (emit/emit-stmt '(comptime (validate)))))
  (testing "Clojure-shaped locals are immutable unless explicitly marked mutable"
    (let [source (emit/emit-stmt
                  '(let [a 4
                         ^:var b 10
                         ^{:zig/type :u8} c 2]
                     (set! b (+ a c))))]
      (is (str/includes? source "const a = 4;"))
      (is (str/includes? source "var b = 10;"))
      (is (str/includes? source "const c: u8 = 2;"))))
  (is (= (str "for (0..@as(usize, @intCast(count))) |row| {\n"
              "    use(row);\n"
              "}")
         (emit/emit-stmt '(dotimes [row count] (use row)))))
  (is (= "continue :dispatch self.producer;"
         (emit/emit-stmt '(continue dispatch (field self producer)))))
  (is (= (str "while ((i < n)) {\n"
              "    total += i;\n"
              "    i += 1;\n"
              "}")
         (emit/emit-stmt '(while (< i n) (+= total i) (+= i 1)))))
  (is (= (str "if ((x < 0)) {\n"
              "    return (-x);\n"
              "} else {\n"
              "    return x;\n"
              "}")
         (emit/emit-stmt '(if (< x 0) (return (- x)) (return x)))))
  (testing "for-else expressions terminate the complete Zig statement"
    (is (= (str "for (items) |item| {\n"
                "    use(item);\n"
                "} else return .different_member_set;")
           (emit/emit-stmt
            '(for [item items] (use item)
                  (else-expression (return :.different_member_set))))))
    (is (= (str "inline for (items) |item| {\n"
                "    use(item);\n"
                "} else unreachable;")
           (emit/emit-stmt
            '(inline-for [item items] (use item)
                         (else-expression (unreachable)))))))
  (testing "while-else expressions terminate only when Zig requires it"
    (is (= (str "while ((head < max)) {\n"
                "    advance();\n"
                "} else max;")
           (emit/emit-stmt
            '(while-loop {:else-expression max} (< head max) (advance)))))
    (let [source
          (emit/emit-stmt
           '(while-loop
             {:else-expression
              (switch value
                      (case [0] zero)
                      (case-else other))}
             ready
             (advance)))]
      (is (str/ends-with? source "\n}"))
      (is (not (str/ends-with? source "\n};"))))))

(deftest struct-schema-test
  (testing "Malli-style field entries preserve per-field properties"
    (is (= [{:name :x
             :type :f32
             :properties {:doc "Horizontal component" :align 4}}
            {:name :y :type :f32 :properties {}}]
           (emit/parse-struct-fields
            [[:x {:doc "Horizontal component" :align 4} :f32]
             [:y :f32]]))))
  (testing "metadata on an entry is retained as field properties"
    (is (= {:unit :meters}
           (:properties
            (first (emit/parse-struct-fields
                    [(with-meta [:distance :f32] {:unit :meters})]))))))
  (testing "the old flattened struct syntax is rejected clearly"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Each a/defstruct field must be a vector"
         (emit/parse-struct-fields '[:x :- :f32 :y :- :f32])))))

(deftest declaration-and-module-test
  (let [source (emit/emit-module
                "demo"
                [{:kind :fn :name 'add :return :i32 :export? true
                  :args [{:name 'a :type :i32} {:name 'b :type :i32}]
                  :body ['(+ a b)]}
                 {:kind :const :name 'factor :type :i32 :value 3}])]
    (testing "top-level declarations are deterministic and constants precede functions"
      (is (< (.indexOf source "pub const factor")
             (.indexOf source "export fn add"))))
    (is (re-find #"export fn add\(a: i32, b: i32\) callconv\(\.c\) i32" source))
    (is (str/includes? source "return (a + b);"))))

(deftest dependency-module-preserves-default-function-abi-test
  (let [source
        (emit/emit-dependency-module
         "demo.callback"
         [{:kind :fn
           :name 'callback
           :return :void
           :export? true
           :public? true
           :attributes {}
           :args [{:name 'value :type :i32}]
           :body []}])]
    (is (str/includes?
         source
         "pub fn callback(value: i32) callconv(.c) void"))
    (is (not (str/includes? source "export fn callback")))))

(deftest dependency-module-keeps-generic-functions-on-the-zig-abi-test
  (let [source
        (emit/emit-dependency-module
         "demo.generic"
         [{:kind :fn
           :name 'scale
           :return :u32
           :export? true
           :public? true
           :attributes {}
           :args [{:name 'value
                   :type :u32
                   :properties {:zig/prefix "comptime"}}]
           :body ['(* value 2)]}])]
    (is (str/includes? source "pub fn scale(comptime value: u32) u32"))
    (is (not (str/includes? source "callconv(.c)")))))

(deftest declaration-only-hot-slice-retains-external-import-test
  (let [external-call
        (with-meta 'aguafria.zig.import.demo.uuid/new-v4
          {:aguafria/zig-reference
           {:kind :import-member
            :import "uuid"
            :module "uuid"
            :member "v4.new"
            :zig-name "uuid.v4.new"}})
        source
        (emit/emit-reloadable-module
         "demo.external"
         [{:kind :fn
           :name 'make-id
           :declaration-key [:fn 'make-id]
           :return :u128
           :args [{:name 'io :type 'std.Io}]
           :body [(list external-call 'io)]}]
         {[:fn 'make-id]
          {:implementation "make_id_implementation"
           :dispatch "make_id_dispatch"
           :dispatch-type "make_id_function_type"
           :setter "make_id_set_dispatch"
           :getter "make_id_implementation_address"
           :active-counter "active_calls"
           :active-depth "active_depth"
           :active-tracking "track_active"
           :active-tracking-setter "set_active_tracking"
           :active-getter "active_call_count"
           :publication-epoch "publication_epoch"
           :publication-epoch-setter "set_publication_epoch"}}
         {}
         {:dependency? true})]
    (is (str/includes? source "const uuid = @import(\"uuid\");"))
    (is (str/includes? source "uuid.v4.new(io)"))))

(deftest named-dependencies-preserve-per-module-type-identity-test
  (let [alpha-container (emit/named-module-container "demo.alpha")
        beta-container (emit/named-module-container "demo.beta")
        imported-alpha
        (with-meta 'alpha
          {:aguafria/zig-reference
           {:kind :namespace-root
            :module "demo.alpha"
            :import-name "demo.alpha"
            :import-namespace 'demo.alpha
            :zig-name "alpha"
            :symbol 'alpha}})
        dependency-source
        (emit/emit-dependency-module
         "demo.alpha"
         [{:kind :const
           :name 'Thing
           :public? true
           :value '(container {:kind :struct} [])}])
        root-source
        (emit/emit-named-module
         "demo.root"
         [{:kind :const :name 'alpha :value imported-alpha}
          {:kind :const :name 'type-name
           :value '(ak/typeName :u32)}])]
    (testing "containers are deterministic and differ across source modules"
      (is (not= alpha-container beta-container))
      (is (str/includes? dependency-source
                         (str "pub const " alpha-container " = struct")))
      (is (str/includes? dependency-source
                         "inline fn __aguafria_type_name(comptime T: type) [:0]const u8"))
      (is (str/includes? dependency-source
                         "if (comptime @import(\"std\").mem.startsWith")))
    (testing "compiler roots select the dependency container"
      (is (str/includes?
           root-source
           (str "@import(\"demo.alpha\")." alpha-container)))
      (is (str/includes? root-source
                         "__aguafria_type_name(u32)")))))

(deftest reloadable-module-publication-epoch-test
  (let [declaration {:kind :fn :name 'increment :return :i32 :export? true
                     :declaration-key [:fn 'increment]
                     :args [{:name 'value :type :i32}]
                     :body ['(+ value 1)]}
        source
        (emit/emit-reloadable-module
         "demo.live" [declaration]
         {[:fn 'increment]
          {:implementation "__impl"
           :dispatch-type "__fn_type"
           :dispatch "__dispatch"
           :getter "__implementation_address"
           :setter "__set_dispatch"
           :emit-getter? true
           :active-counter "__active_calls"
           :active-getter "__active_call_count"
           :publication-epoch "__publication_epoch"
           :publication-epoch-setter "__set_publication_epoch"}})]
    (is (str/includes? source
                       "var __publication_epoch: ?*const usize = null;"))
    (is (str/includes?
         source
         (str "export fn __set_publication_epoch("
              "__set_publication_epoch_address: usize)")))
    (is (str/includes? source
                       "if ((__dispatch_publication_before & 1) != 0) continue;"))
    (is (str/includes? source
                       (str "if (__dispatch_publication_before == "
                            "__dispatch_publication_after) break :publication "
                            "__dispatch_publication_candidate;")))
    (is (< (.indexOf source "if (@inComptime())")
           (.indexOf source "const __dispatch_target")))))

(deftest exported-native-callback-counts-active-calls-without-tls-test
  (let [source
        (emit/emit-reloadable-module
         "demo.callback"
         [{:kind :fn
           :name 'tick
           :return :void
           :export? true
           :attributes {:attrs #{:export}}
           :declaration-key [:fn 'tick]
           :args [{:name 'context :type [:* :u8]}]
           :body []}]
         {[:fn 'tick]
          {:implementation "__impl"
           :dispatch-type "__fn_type"
           :dispatch "__dispatch"
           :getter "__implementation_address"
           :setter "__set_dispatch"
           :active-counter "__active_calls"
           :active-depth "__active_depth"
           :active-tracking "__track_active_calls"
           :active-getter "__active_call_count"
           :publication-epoch "__publication_epoch"
           :publication-epoch-setter "__set_publication_epoch"}})
        implementation
        (subs source (.indexOf source "fn __impl")
              (.indexOf source "const __fn_type"))]
    (is (str/includes? implementation
                       "@atomicRmw(usize, &__active_calls, .Add"))
    (is (str/includes? implementation
                       "@atomicRmw(usize, &__active_calls, .Sub"))
    (is (not (str/includes? implementation "__active_depth")))
    (is (not (str/includes? implementation "_outermost")))))

(deftest jvm-adapters-count-invocations-without-per-image-tls
  (let [declaration {:kind :fn :name 'identity :return :u32
                     :declaration-key [:fn 'identity]
                     :args [{:name 'value :type :u32}] :body ['value]}
        specs {[:fn 'identity]
               {:implementation "__impl" :dispatch-type "__fn_type"
                :dispatch "__dispatch" :getter "__implementation_address"
                :setter "__set_dispatch" :active-counter "__active_calls"
                :active-depth "__active_depth" :active-tracking "__track_active_calls"
                :active-tracking-setter "__set_active_tracking"
                :active-getter "__active_call_count"
                :publication-epoch "__publication_epoch"
                :publication-epoch-setter "__set_publication_epoch"}}
        adapter (assoc declaration :jvm-adapter? true)
        adapter-source (emit/emit-reloadable-module "demo.adapter" [adapter] specs)
        authored-source (emit/emit-reloadable-module "demo.adapter" [declaration] specs)]
    (is (not (str/includes? adapter-source "threadlocal var")))
    (is (not (str/includes? adapter-source "__active_depth")))
    (is (str/includes? adapter-source "@atomicRmw(usize, &__active_calls, .Add"))
    (is (str/includes? adapter-source "@atomicRmw(usize, &__active_calls, .Sub"))
    (is (str/includes? authored-source "threadlocal var __active_depth"))
    (is (str/includes? authored-source "__active_depth += 1"))
    (is (= (emit/emit-module "demo.adapter" [declaration])
           (emit/emit-module "demo.adapter" [adapter]))
        "adapter tracking does not alter ordinary static Zig emission")))

(deftest reloadable-module-reserves-publication-locals-test
  (let [declaration {:kind :fn :name 'choose :return :usize
                     :declaration-key [:fn 'choose]
                     :args [{:name 'candidate :type :usize}]
                     :body ['candidate]}
        source
        (emit/emit-reloadable-module
         "demo.publication-locals" [declaration]
         {[:fn 'choose]
          {:implementation "__impl"
           :dispatch-type "__fn_type"
           :dispatch "__dispatch"
           :getter "__implementation_address"
           :setter "__set_dispatch"
           :active-counter "__active_calls"
           :active-depth "__active_depth"
           :active-tracking "__track_active_calls"
           :active-getter "__active_call_count"
           :publication-epoch "__publication_epoch"
           :publication-epoch-setter "__set_publication_epoch"}})]
    (is (str/includes? source "candidate: usize"))
    (is (str/includes? source
                       "const __dispatch_publication_candidate = @atomicLoad"))
    (is (not (str/includes? source "const candidate = @atomicLoad")))))

(deftest reloadable-branch-hint-remains-first-statement-test
  (let [declaration {:kind :fn :name 'crash :return :noreturn
                     :declaration-key [:fn 'crash]
                     :args []
                     :body ['(ak/branchHint :.cold) 'unreachable]}
        source
        (emit/emit-reloadable-module
         "demo.cold" [declaration]
         {[:fn 'crash]
          {:implementation "__impl"
           :dispatch-type "__fn_type"
           :dispatch "__dispatch"
           :getter "__implementation_address"
           :setter "__set_dispatch"
           :active-counter "__active_calls"
           :active-depth "__active_depth"
           :active-tracking "__track_active_calls"
           :active-getter "__active_call_count"
           :publication-epoch "__publication_epoch"
           :publication-epoch-setter "__set_publication_epoch"}})]
    (is (re-find #"fn __impl\(\) noreturn \{\s*(?://[^\n]*\n\s*)?@branchHint\(\.cold\);\s+const"
                 source))
    (is (re-find #"fn crash\(\) noreturn \{\s*(?://[^\n]*\n\s*)?@branchHint\(\.cold\);\s+if"
                 source))))

(deftest reloadable-discard-arguments-get-callable-internal-names-test
  (let [declaration {:kind :fn :name 'visit :return :i32 :export? true
                     :declaration-key [:fn 'visit]
                     :args [{:name '_ :type :i32}
                            {:name 'value :type :i32}]
                     :body ['value]}
        source
        (emit/emit-reloadable-module
         "demo.discard" [declaration]
         {[:fn 'visit]
          {:implementation "__impl"
           :dispatch-type "__fn_type"
           :dispatch "__dispatch"
           :getter "__implementation_address"
           :setter "__set_dispatch"
           :emit-getter? true
           :active-counter "__active_calls"
           :active-depth "__active_depth"
           :active-tracking "__track_active_calls"
           :active-getter "__active_call_count"
           :publication-epoch "__publication_epoch"
           :publication-epoch-setter "__set_publication_epoch"}})]
    (is (str/includes? source "__aguafria_discard_0: i32"))
    (is (str/includes? source "_ = __aguafria_discard_0;"))
    (is (str/includes? source
                       "const __fn_type = @TypeOf(&__impl);"))
    (is (str/includes? source
                       "return __dispatch_target(__aguafria_discard_0, value);"))
    (is (str/includes? source
                       "return @intFromPtr(&__impl);"))
    (is (not (str/includes? source "__impl(_, value)")))))

(deftest reloadable-noreturn-fallback-is-a-complete-statement-test
  (let [declaration {:kind :fn :name 'fatal :return :noreturn
                     :declaration-key [:fn 'fatal]
                     :args [{:name '_ :type :i32}]
                     :body ['unreachable]}
        source
        (emit/emit-reloadable-module
         "demo.noreturn" [declaration]
         {[:fn 'fatal]
          {:implementation "__impl"
           :dispatch-type "__fn_type"
           :dispatch "__dispatch"
           :getter "__implementation_address"
           :setter "__set_dispatch"
           :active-counter "__active_calls"
           :active-depth "__active_depth"
           :active-tracking "__track_active_calls"
           :active-getter "__active_call_count"
           :publication-epoch "__publication_epoch"
           :publication-epoch-setter "__set_publication_epoch"}})]
    (is (re-find
         #"if \(__dispatch_target_address == 0\) \{\s+__impl\(__aguafria_discard_0\);\s+\}"
         source))
    (is (not (str/includes? source
                            "__impl(__aguafria_discard_0)\n    }")))))

(deftest reloadable-inferred-error-dispatch-preserves-exact-zig-abi-test
  (doseq [result [{:return :void :zig-qualifiers "!"}
                  {:return :!void}
                  {:return [:! :void]}
                  {:return [:error-union :void]}]]
    (let [declaration (merge result {:kind :fn :name 'run
                                     :declaration-key [:fn 'run]
                                     :args []
                                     :body ['(return)]})
          source
          (emit/emit-reloadable-module
           "demo.inferred-error" [declaration]
           {[:fn 'run]
            {:implementation "__impl"
             :dispatch-type "__fn_type"
             :dispatch "__dispatch"
             :getter "__implementation_address"
             :setter "__set_dispatch"
             :active-counter "__active_calls"
             :active-depth "__active_depth"
             :active-tracking "__track_active_calls"
             :active-getter "__active_call_count"
             :publication-epoch "__publication_epoch"
             :publication-epoch-setter "__set_publication_epoch"}})]
      (is (str/includes? source "const __fn_type = @TypeOf(&__impl);"))
      (is (str/includes? source "return __dispatch_target();"))
      (is (not (str/includes? source "dispatch_frame")))
      (is (not (str/includes? source "anyerror!void"))))))

(deftest reloadable-const-keeps-comptime-state-reference-direct-test
  (let [state-symbol
        (with-meta 'io-threaded
          {:aguafria/zig-reference
           {:symbol 'io-threaded
            :zig-name "io_threaded"
            :declaration-kind :var
            :state-accessor "__state_io_threaded_reference"}})
        state {:kind :var
               :name 'io-threaded
               :zig-name "io_threaded"
               :type :u32
               :value 41
               :declaration-key [:var 'io-threaded]}
        derived {:kind :const
                 :name 'answer
                 :type :u32
                 :value (list 'aguafria.keyword/+ state-symbol 1)
                 :declaration-key [:const 'answer]}
        runtime-reader {:kind :fn
                        :name 'read-answer
                        :return :u32
                        :args []
                        :body [state-symbol]
                        :declaration-key [:fn 'read-answer]}
        source
        (emit/emit-reloadable-module
         "demo.comptime-state"
         [state derived runtime-reader]
         {}
         {[:var 'io-threaded]
          {:accessor "__state_io_threaded_reference"
           :getter "__state_io_threaded_address"
           :setter "__state_io_threaded_set_address"
           :size-getter "__state_io_threaded_size"
           :align-getter "__state_io_threaded_alignment"
           :pointer-align-getter "__state_io_threaded_pointer_alignment"}})]
    (is (str/includes? source "const answer: u32 = (io_threaded + 1);"))
    (is (str/includes? source
                       "return __state_io_threaded_reference().*;"))))

(deftest reloadable-state-supports-comptime-selected-void-storage-test
  (let [declaration {:kind :var
                     :name 'state
                     :type 'StateType
                     :value '(raw ".{}")
                     :declaration-key [:var 'state]}
        source
        (emit/emit-reloadable-module
         "demo.conditional-state"
         [declaration]
         {}
         {[:var 'state]
          {:accessor "__state_reference"
           :getter "__state_address"
           :setter "__state_set_address"
           :size-getter "__state_size"
           :align-getter "__state_alignment"
           :pointer-align-getter "__state_pointer_alignment"}})]
    (is (str/includes?
         source
         "var state: StateType = if (@typeInfo(StateType) == .void) {} else .{};"))))

(deftest source-metadata-safety-test
  (let [source (emit/emit-module
                "cider-buffer"
                [{:kind :const :name 'answer :type :i32 :value 42
                  :source {:file "(ns broken\n  (:require [evil]))"
                           :line 9 :column 3}}])]
    (testing "multiline editor buffer contents can never be injected as Zig"
      (is (not (str/includes? source "(:require")))
      (is (str/includes? source "// Aguafria source: <repl>:9:3")))))

(deftest unresolved-reference-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Unresolved dotted Zig reference"
       (emit/emit-expr '(out_of_nowhere.member 1)))))

(deftest qualified-local-declarations-and-discard-targets-validate
  (let [context (the-ns 'aguafria.zig.emitter-test)
        declaration {:kind :fn :name 'local-discard :return :u32 :args []
                     :body '[(ak/var value 7 :u32)
                             (ak/= :_ (& value))
                             (ak/= _ value)
                             value]}]
    (is (map? (emit/prepare-declaration context declaration)))
    (binding [emit/*keyword-context* context]
      (is (= "_ = value;" (emit/emit-stmt '(ak/= :_ value))))
      (is (= "value = 9;" (emit/emit-stmt '(ak/= value 9)))))
    (testing "discard syntax does not permit unknown values or locals before declaration"
      (doseq [body ['[(ak/= :_ missing)]
                    '[(ak/= missing 7)]
                    '[(ak/= :_ value) (ak/var value 7 :u32)]]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unresolved Zig reference"
                              (emit/prepare-declaration context (assoc declaration :body body))))))))

(deftest destructuring-declarations-introduce-only-their-declared-locals
  (let [context (the-ns 'aguafria.zig.emitter-test)
        declaration {:kind :fn :name 'sum :return :u32 :args []}
        good '[(const values [1 2])
               (destructure {} [{:kind :const :name left}
                                {:kind :var :name right :type :u32}] values)
               (+ left right)]]
    (is (map? (emit/prepare-declaration context (assoc declaration :body good))))
    (doseq [body ['[(destructure {} [{:kind :const :name left}] left)]
                  '[(destructure {} [{:kind :target :target left}] [1])]
                  '[(destructure {} [{:kind :const :name left :type Missing}] [1])]
                  '[(+ left 1) (destructure {} [{:kind :const :name left}] [1])]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unresolved Zig reference"
                            (emit/prepare-declaration context (assoc declaration :body body)))))))

(deftest modulo-operands-are-validated
  (let [context (the-ns 'aguafria.zig.emitter-test)
        declaration {:kind :fn :name 'wrap :return :u32
                     :args [{:name 'value :type :u32}]}]
    (doseq [op '[mod]]
      (is (map? (emit/prepare-declaration
                 context (assoc declaration :body [(list op 'value 3)]))))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Unresolved Zig reference"
           (emit/prepare-declaration
            context (assoc declaration :body [(list op 'missing 3)])))))))

(deftest callback-type-parameter-names-are-not-value-references
  (let [context (the-ns 'aguafria.zig.emitter-test)
        declaration {:kind :const :name 'Callback
                     :value '(a/type [:*const [:fn {:callconv :.c}
                                               [{:name it :type :i32}] :bool]])}]
    (is (map? (emit/prepare-declaration context declaration)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Unresolved Zig reference"
         (emit/prepare-declaration
          context (assoc declaration :value '(a/type [:fn [{:name it :type Missing}] :void])))))))

(deftest loop-option-blocks-have-sequential-local-scope
  (let [context (the-ns 'aguafria.zig.emitter-test)
        declaration {:kind :fn :name 'example :return :void :args []}
        loop-form '(while-loop {:else [(const fallback 7) (set! _ fallback)]} false)]
    (is (map? (emit/validate-declaration-references!
               context (assoc declaration :body [loop-form]))))
    (doseq [body [[loop-form '(set! _ fallback)]
                  '[(while-loop {:else [(set! _ fallback) (const fallback 7)]} false)]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unresolved Zig reference `fallback`"
                            (emit/validate-declaration-references!
                             context (assoc declaration :body body)))))))

(deftest converted-containers-know-only-their-declared-members
  (let [context (the-ns 'aguafria.zig.emitter-test)
        declaration {:kind :const :name 'Container
                     :value '(container {:kind :struct}
                                        [(fn-decl caller :u32 [] (helper))
                                         (fn-decl helper :u32 [] 7)])}
        catalog-name 'fixture.converted-container-members]
    (project/register-catalog! {:schema-version 1
                                :modules {(str catalog-name) {:source-kind :zig}}})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unresolved Zig reference"
                          (emit/validate-declaration-references! context declaration)))
    (binding [project/*catalog-namespace* catalog-name]
      (is (= declaration (emit/validate-declaration-references! context declaration)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Unresolved Zig reference `unknown`"
           (emit/validate-declaration-references!
            context (assoc declaration :value
                           '(container {:kind :struct}
                                       [(fn-decl caller :u32 [] (unknown))
                                        (fn-decl helper :u32 [] 7)]))))))))

(deftest implicit-return-test
  (is (= "return result: {\n    break :result 42;\n};"
         (emit/emit-function-body '((with-block :result (break :result 42))) :i32)))
  (is (= "return (a + b);"
         (emit/emit-function-body '((+ a b)) :i32)))
  (is (= (str "if ((x < 0)) {\n"
              "    return (-x);\n"
              "} else {\n"
              "    return x;\n"
              "}")
         (emit/emit-function-body '((if (< x 0) (- x) x)) :i32)))
  (is (= "value;" (emit/emit-function-body '(value) :void)))
  (is (= (str "if ((x < 0)) {\n"
              "    return 0;\n"
              "}\n"
              "return (x + 1);")
         (emit/emit-function-body
          '((when (< x 0) (return 0))
            (+ x 1))
          :i32)))
  (is (= (str "{\n"
              "    const x = (a + 1);\n"
              "    const y = (x * 2);\n"
              "    return (x + y);\n"
              "}")
         (emit/emit-function-body
          '((let [x (+ a 1) y (* x 2)] (+ x y)))
          :i32)))
  (is (= (str "while (ready) {\n"
              "    continue;\n"
              "}")
         (emit/emit-function-body '((while ready (continue))) :i32))))

(deftest generated-adapter-source-does-not-depend-on-call-site-lines
  (let [declaration (fn [line adapter?]
                      {:kind :fn :name 'adapter :return :i32 :args []
                       :declaration-key [:fn 'adapter]
                       :jvm-adapter? adapter? :implicit-return? true
                       :body [(list 'with-block :result
                                    (with-meta '(break :result 42)
                                      {:line line :column 5}))]})
        first-source (emit/emit-declaration (declaration 10 true))
        moved-source (emit/emit-declaration (declaration 99 true))
        authored-source (emit/emit-declaration (declaration 10 false))
        specs {[:fn 'adapter] {:dispatch "adapter_dispatch"
                               :dispatch-type "adapter_type"
                               :implementation "adapter_impl"
                               :getter "adapter_getter"
                               :setter "adapter_setter"
                               :emit-getter? true}}
        reloadable (fn [line adapter?]
                     (emit/emit-reloadable-module
                      "demo.adapter" [(declaration line adapter?)] specs))]
    (is (= first-source moved-source))
    (is (not (str/includes? first-source "// Aguafria form:")))
    (is (str/includes? authored-source "// Aguafria form: 10:5"))
    (is (str/includes? first-source "return result:"))
    (is (= (reloadable 10 true) (reloadable 99 true)))
    (is (str/includes? (reloadable 10 true) "if (@inComptime())"))
    (is (not (str/includes? (reloadable 10 true) "// Aguafria form:")))
    (is (str/includes? (reloadable 10 false) "// Aguafria form: 10:5"))))

(deftest saturating-left-shift-assignment-test
  (is (= "value <<|= shift;" (emit/emit-stmt '(<<|= value shift))))
  (is (= "value <<|= shift;"
         (emit/emit-function-body '((<<|= value shift)) :u32)))
  (is (= "value <<|= shift;\nreturn value;"
         (emit/emit-function-body '((<<|= value shift) value) :u32))))

(deftest incomplete-non-void-functions-remain-native-diagnostics
  (testing "empty and statement-only bodies never synthesize a return value"
    (is (= "" (emit/emit-function-body [] :u32)))
    (is (= "" (emit/emit-function-body '((do)) :u32)))
    (is (= "value = 1;"
           (emit/emit-function-body '((set! value 1)) :u32)))
    (is (re-matches #"\{\s+const memory = allocate\(\);\s+_ = memory;\s+\}"
                    (emit/emit-function-body
                     '((let [memory (allocate)] (set! _ memory)))
                     [:optional [:* :u8]])))
    (is (re-matches #"\{\s+const value = 1;\s+\}"
                    (emit/emit-function-body '((let [value 1])) :u32))))
  (testing "incomplete branches stay statements, while value branches return"
    (is (= "if (ready) {\n    value = 1;\n}"
           (emit/emit-function-body '((if ready (set! value 1))) :u32)))
    (is (= (str "if (ready) {\n    return 42;\n} else {\n"
                "    value = 1;\n}")
           (emit/emit-function-body '((if ready 42 (set! value 1))) :u32))))
  (testing "a let used as an expression still needs a value"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"A let used as a value requires a result expression"
                          (emit/emit-expr '(let [value 1]))))))

(deftest unreachable-tails-need-no-return-attribute
  (is (= "unreachable;"
         (emit/emit-function-body '((unreachable)) :usize)))
  (is (= (str "if (available) {\n"
              "    return 42;\n"
              "} else {\n"
              "    unreachable;\n"
              "}")
         (emit/emit-function-body '((if available 42 (unreachable))) :usize)))
  (is (re-matches
       #"\{\s+const result = compute\(\);\s+if \(result\) \{\s+return 42;\s+\}\s+unreachable;\s+\}"
       (emit/emit-function-body
        '((let [result (compute)]
            (when result (return 42))
            (unreachable)))
        :usize)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"unreachable takes no arguments"
                        (emit/emit-function-body '((unreachable 1)) :usize))))

(deftest void-error-unions-and-noreturn-need-no-return-attribute
  (doseq [return-type [:void :!void [:! :void] [:error-union :void]
                       [:error-union :anyerror :void]]]
    (is (= "_ = result;"
           (emit/emit-function-body '((set! _ result)) return-type))))
  (is (= "return 42;" (emit/emit-function-body '(42) :!u32)))
  (is (= "while (true) {}"
         (emit/emit-function-body '((while-loop {} true)) :noreturn)))
  (is (= "abort();"
         (emit/emit-function-body '((abort)) :noreturn))))

(deftest implicit-error-and-loop-expression-returns
  (doseq [return-type [:!void [:! :void] [:error-union :void]
                       [:error-union :anyerror :void]]]
    (is (= "return error.Failed;"
           (emit/emit-function-body '((error-value :Failed)) return-type)))
    (is (= "return try work();"
           (emit/emit-function-body '((try (work))) return-type))))
  (is (= "return while (ready) {\n    advance();\n} else 42;"
         (emit/emit-function-body
          '((while-loop {:else-expression 42} ready (advance))) :usize))))
