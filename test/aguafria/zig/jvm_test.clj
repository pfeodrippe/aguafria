(ns aguafria.zig.jvm-test
  (:require [aguafria.keyword :as ak]
            [aguafria.std :as std]
            [aguafria.std.ArrayList :as array-list]
            [aguafria.std.ArrayList.Slice :as array-list-slice]
            [aguafria.std.SemanticVersion :as semantic-version]
            [aguafria.std.testing :as zig-testing]
            [aguafria.std.debug :as debug]
            [aguafria.std.math :as math]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as az]
            [aguafria.zig.jvm :as native-call]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io StringWriter]))

(defn- fixture []
  (let [namespace (create-ns (gensym "aguafria.native-call-fixture-"))]
    (binding [*ns* namespace]
      (refer 'clojure.core)
      (require '[aguafria.zig :as az] '[aguafria.keyword :as ak]
               '[aguafria.std.debug :as debug]))
    namespace))

(deftest direct-imported-function-results-and-output
  (let [err (StringWriter.)]
    (binding [*err* err]
      (is (nil? (debug/print "Hello, {s}!\n" ["World"])))
      (is (nil? (debug/print "Hello, {s}!\n" ["Clojure"]))))
    (is (= "Hello, World!\nHello, Clojure!\n" (str err))))
  (is (= 3.0 (math/sqrt 9.0)))
  (is (= 4.0 (math/sqrt 16.0)))
  (is (Double/isNaN (math/sqrt -1.0)))
  (let [adapters-before (count @@#'native-call/prepared-adapters)]
    (is (= 5.0 (math/sqrt 25.0)))
    (is (= adapters-before (count @@#'native-call/prepared-adapters))
        "changing an ordinary argument reuses the typed adapter")))

(deftest value-expressions-use-the-shared-native-call-bridge
  (is (true? (ak/== \e (az/char-literal "'\\x65'"))))
  (is (true? (ak/== \é (az/char-literal "'\\u{e9}'"))))
  (is (= "hello" (az/string-literal "\"h\\x65llo\"")))
  (let [err (StringWriter.)]
    (binding [*err* err]
      (debug/print "{}\n" [(mem/eql :u8 "hello" (az/string-literal "\"h\\x65llo\""))]))
    (is (= "true\n" (str err))))
  (let [err (StringWriter.)]
    (binding [*err* err]
      (is (nil? (debug/print "{}\n{}\n{}\n"
                             [(and true false) (or true false) (ak/! true)]))))
    (is (= "false\ntrue\nfalse\n" (str err))))
  (with-open [absent (ak/as nil [:optional [:slice-const :u8]])
              present (ak/as "hi" [:optional [:slice-const :u8]])]
    (is (true? (ak/== absent nil)))
    (is (false? (ak/!= absent nil)))
    (is (true? (ak/!= present nil)))
    (is (false? (ak/== present nil)))
    (is (nil? (debug/assert (ak/== absent nil))))
    (is (nil? (debug/assert (ak/!= present nil)))))
  (is (= 3.0 (ak/sqrt 9.0)))
  (is (= 4 (ak/sizeOf ak/i32)))
  (let [type (ak/TypeOf true)
        err (StringWriter.)]
    (is (az/zig-type? type))
    (is (= "bool" (:zig-name (value/type-info type))))
    (is (= 1 (ak/sizeOf type)))
    (binding [*err* err]
      (debug/print "type: {}\n" [type]))
    (is (= "type: bool\n" (str err))))
  (let [type (ak/Vector 4 ak/i32)]
    (is (az/zig-type? type))
    (is (= 16 (ak/sizeOf type))))
  (is (= 30 (ak/+% 10 20)))
  (is (= 2 (ak/min 2 7)))
  (is (= 42 (az/field {:answer 42} :answer)))
  (is (= 20 (az/index [10 20 30] 1)))
  (let [before (count @@#'native-call/prepared-adapters)]
    (is (= 50 (ak/+% 20 30)))
    (is (= before (count @@#'native-call/prepared-adapters))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Wrong number"
                       (ak/! true false)))
  (let [error (try (ak/! 42) (catch clojure.lang.Compiler$CompilerException e e))]
    (is (some? error))
    (is (str/includes? (:aguafria/report (runtime/error-data error))
                       "expected type 'bool'"))))

(deftest mutable-native-values-in-ordinary-clojure-let
  (with-open [optional-value (ak/var (ak/as nil [:optional [:slice-const :u8]]))]
    (debug/assert (ak/== optional-value nil))
    (ak/= optional-value "hi")
    (debug/assert (ak/!= optional-value nil))
    (let [err (StringWriter.)]
      (binding [*err* err]
        (debug/print "{?s}\n" [optional-value]))
      (is (= "hi\n" (str err))))
    (ak/= optional-value nil)
    (is (ak/== optional-value nil)))
  (with-open [number (ak/var 1 :i32)]
    (ak/= number 42)
    (is (= 42 @number))
    (is (ak/== number 42))
    (ak/+= number 8)
    (is (= 50 @number))
    (ak/*= number 2)
    (is (= 100 @number))
    (ak/-= number 1)
    (is (= 99 @number)))
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defn optional-mutation :bool []
                 (let [value (ak/var (ak/as nil [:optional [:slice-const :u8]]))]
                   (ak/= value "hi")
                   (ak/!= value nil)))))
      (is (true? ((ns-resolve namespace 'optional-mutation))))
      (finally (remove-ns (ns-name namespace))))))

(deftest void-error-unions-return-errors-and-success-to-the-jvm
  (doseq [type [:!void [:! :void] [:error-union :anyerror :void]
               [:error-union [:error-set [:DemoError]] :void]]]
    (with-open [failed (ak/as (az/error-value :DemoError) type)
                succeeded (ak/as nil type)
                mutable (ak/var (az/error-value :DemoError) type)]
      (is (= :DemoError (get-in @failed [:error :name])))
      (is (= {:ok nil} @succeeded))
      (is (= :DemoError (get-in @mutable [:error :name])))
      (ak/= mutable {:ok nil})
      (is (= {:ok nil} @mutable)))))

(deftest inferred-member-literals-construct-native-values
  (let [namespace (fixture)
        list-type (std/ArrayList :u21)]
    (try
      (binding [*ns* namespace]
        (require '[aguafria.std :as std] '[aguafria.std.heap :as heap])
        (eval '(az/defstruct Threshold
                 [[:minimum :f32]
                  [:maximum :f32]
                  (az/const-decl default {:attrs #{:public}} Threshold
                    {:minimum 0.25 :maximum 0.75})]))
        (eval '(az/defenum Mode [:active :inactive]))
        (eval '(az/defn append-and-read :!u21 [[initial (std/ArrayList :u21)]]
                 (let [list (ak/var initial)]
                   (ak/defer ((az/field list :deinit) heap/page_allocator))
                   (try ((az/field list :append) heap/page_allocator \☔))
                   (az/index (az/field list :items) 0)))))
      (with-open [list (ak/var :.empty list-type)
                  appended ((ns-resolve namespace 'append-and-read) list)]
        (is (= :var (:kind (value/info list))))
        (is (= 0 (az/field list :capacity)))
        (is (= 0 (az/field (az/field list :items) :len)))
        (is (= {:ok (int \☔)} @appended)))
      (let [threshold-type @(ns-resolve namespace 'Threshold)
            mode-type @(ns-resolve namespace 'Mode)]
        (with-open [threshold (threshold-type :.default)
                    mutable-threshold (ak/var :.default threshold-type)
                    mode (ak/var :.active mode-type)]
          (is (= {:minimum 0.25 :maximum 0.75} @threshold))
          (is (= @threshold @mutable-threshold))
          (is (true? (ak/== mode :.active)))
          (ak/= mode :.inactive)
          (is (true? (ak/== mode :.inactive)))))
      (finally (remove-ns (ns-name namespace))))))

(deftest bound-container-methods-preserve-native-receivers
  (with-open [list (ak/var :.empty (std/ArrayList :u21))]
    (let [append (az/field list :append)
          allocator zig-testing/allocator]
      (try
        (is (fn? append))
        (is (= {:ok nil} (append allocator \☔)))
        (is (= {:ok nil} (array-list/append list allocator \☺)))
        (is (= [9748 9786] (az/field list :items)))
        (is (= [9748 9786] (:items @list)))
        (is (= (az/field list :capacity) (:capacity @list)))
        (is (str/starts-with? (pr-str list)
                             "#aguafria.zig.value.ZigValue[{:items [9748 9786]"))
        (is (= {:ok nil} (zig-testing/expectEqual 2 (az/field (az/field list :items) :len))))
        (finally (array-list/deinit list allocator)))))
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defstruct Counter
                 [[:value :i32]
                  (az/fn increment :void
                    [[self [:* Counter]] [amount :i32]]
                    (ak/+= (az/field self :value) amount))])))
      (let [Counter @(ns-resolve namespace 'Counter)]
        (with-open [counter (ak/var (Counter {:value 1}))]
          (let [increment (az/field counter :increment)]
            (is (nil? (increment 4)))
            (is (nil? (increment 6)))
            (is (= 11 (az/field counter :value))))))
      (finally (remove-ns (ns-name namespace))))))

(deftest container-functions-construct-their-own-type
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defstruct Timestamp
                 [[:seconds :i64] [:nanos :u32]
                  (az/fn- epoch-seconds :i64 [] 0)
                  (az/fn unix-epoch Timestamp "The Unix epoch." []
                    (Timestamp {:seconds (epoch-seconds) :nanos 0}))]))
        (eval '(az/defn read-epoch-seconds :i64 []
                 (az/field ((az/field Timestamp :unix-epoch)) :seconds))))
      (is (= 0 ((ns-resolve namespace 'read-epoch-seconds))))
      (let [Timestamp @(ns-resolve namespace 'Timestamp)]
        (with-open [timestamp (Timestamp {:seconds 123 :nanos 456})]
          (is (= 123 (az/field timestamp :seconds)))
          (is (= 456 (az/field timestamp :nanos)))))
      (is (str/includes? (:doc (meta (ns-resolve namespace 'Timestamp))) "The Unix epoch."))
      (finally (remove-ns (ns-name namespace))))))

(deftest container-state-is-shared-by-jvm-and-native-calls
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defstruct S
                 [[:value {:var 1234 :doc "Shared counter."} :i32]
                  [:other {:var 7} :i32]
                  [:limit {:const 9000} :i32]
                  [:instance {:default 12} :i32]]))
        (eval '(az/defn bump :i32 []
                 (ak/+= (az/field S :value) 1)
                 (az/field S :value))))
      (let [S @(ns-resolve namespace 'S)
            bump (ns-resolve namespace 'bump)]
        (with-open [counter (az/field S :value)]
          (is (= 1234 @counter))
          (is (nil? (ak/+= (az/field S :value) 1)))
          (is (= 1235 @counter))
          (is (= 1236 (bump)))
          (is (= 1236 @counter))
          (with-open [other (az/field S :other)]
            (is (= 7 @other)))
          (is (= 1237 (bump)) "another member adapter must not reset state")
          (is (= 1237 @counter))
          (ak/= counter 42)
          (is (= 43 (bump)))
          (is (= 43 @(az/field S :value)))
          (binding [*ns* namespace]
            (eval '(az/defn bump :i32 []
                     (ak/+= (az/field S :value) 2)
                     (az/field S :value))))
          (is (= 45 (bump)) "hot reload retains the existing container state")
          (is (= 45 @counter)))
        (is (= 9000 (az/field S :limit)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mutable native value"
                              (ak/+= (az/field S :limit) 1)))
        (is (= 4 (ak/sizeOf S)) "only instance fields occupy struct storage")
        (with-open [instance (S {})]
          (is (= 12 (az/field instance :instance)))))
      (finally (remove-ns (ns-name namespace))))))

(deftest thread-local-values-resolve-on-the-calling-platform-thread
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defvar counter :i32 {:attrs #{ak/threadlocal}} 1234))
        (eval '(az/defn bump-tls :i32 [] (ak/+= counter 1) counter)))
      (let [counter @(ns-resolve namespace 'counter)
            bump (ns-resolve namespace 'bump-tls)]
        (is (= 1234 @counter))
        (ak/+= counter 1)
        (is (= 1236 (bump)))
        (is (= 1236 @counter))
        (let [other (future
                      (let [before @counter]
                        (ak/+= counter 10)
                        [before (bump) @counter]))]
          (is (= [1234 1245 1245] @other)))
        (is (= 1236 @counter)))
      (finally (remove-ns (ns-name namespace))))))

(deftest anonymous-container-members-work-from-the-jvm
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (let [type (eval '(az/struct [[:value {:var 1234} :i32]]))
              field (az/field type :value)]
          (is (= :struct (:kind (value/type-info type))))
          (is (= 'value (-> type value/type-info :members first :name)))
          (is (= 1234 @field))
          (ak/+= field 1)
          (is (= 1235 @(az/field type :value))))
        (let [type (eval '(let [T :i32]
                           (az/struct [[:x T]])))
              instance (type {:x 7})]
          (is (= 7 (az/field instance :x))))
        (is (= :enum (:kind (value/type-info (eval '(az/enum [:red :blue]))))))
        (is (= :union (:kind (value/type-info (eval '(az/union [[:number :i32]])))))))
      (finally (remove-ns (ns-name namespace))))))

(deftest variable-types-can-be-inferred-without-a-placeholder
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defvar mouse-down false))
        (eval '(az/defvar documented "An inferred flag." {:public false} true))
        (eval '(az/defvar local-flag {:attrs #{ak/threadlocal}} false))
        (eval '(az/defvar count :u32 {:public false} 12))
        (eval '(az/defn pressed :bool [] mouse-down)))
      (let [mouse-down @(ns-resolve namespace 'mouse-down)
            pressed (ns-resolve namespace 'pressed)]
        (is (false? @mouse-down))
        (is (false? (pressed)))
        (ak/= mouse-down true)
        (is (true? (pressed)))
        (is (true? @@(ns-resolve namespace 'documented)))
        (is (false? @@(ns-resolve namespace 'local-flag)))
        (is (= 12 @@(ns-resolve namespace 'count)))
        (is (nil? (:type (:aguafria/declaration (meta (ns-resolve namespace 'mouse-down)))))))
      (finally (remove-ns (ns-name namespace))))))

(deftest attribute-sets-work-in-native-and-jvm-declarations
  (with-open [number (ak/var 1 :i32 {:attrs #{ak/comptime}})]
    (ak/+= number 1)
    (is (= 2 @number)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":attrs must be a set"
                        (ak/var 1 :i32 {:attrs ak/comptime})))
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (is (thrown? Exception
                      (eval '(az/defvar bad {:attrs #{ak/threadlocal}} :i32 1))))
        (is (thrown? Exception
                      (eval '(az/defvar bad :i32 {:attrs ak/threadlocal} 1))))
        (eval '(az/defn compile-counter :i32 []
                 (let [counter (ak/var 1 :i32 {:attrs #{ak/comptime}})]
                   (ak/+= counter 1)
                   counter))))
      (is (= 2 ((ns-resolve namespace 'compile-counter))))
      (finally (remove-ns (ns-name namespace))))))

(deftest generic-method-vars-have-real-completion-metadata
  (is (= '([self gpa item]) (:arglists (meta #'array-list/append))))
  (is (str/includes? (:doc (meta #'array-list/append)) "Extend the list"))
  (is (:receiver-method? (:aguafria/zig-reference (meta #'array-list/append))))
  (is (str/includes? (slurp (io/resource "aguafria/std/ArrayList.clj"))
                     "clojure.core/declare"))
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (require '[aguafria.std :as std]
                 '[aguafria.std.ArrayList :as array-list]
                 '[aguafria.std.testing :as testing])
        (eval '(az/deftest method-vars-work-in-native-code
                 (let [list (ak/var :.empty (std/ArrayList :u21))]
                   (ak/defer (array-list/deinit list testing/allocator))
                   (try (array-list/append list testing/allocator \☔))
                   (try (testing/expectEqual 1 (az/field (az/field list :items) :len)))))))
      (is (= :passed (:status ((ns-resolve namespace 'method-vars-work-in-native-code)))))
      (finally (remove-ns (ns-name namespace))))))

(deftest field-accessor-vars-work-in-jvm-and-native-code
  (is (= '([self]) (:arglists (meta #'array-list/-items))))
  (is (str/includes? (:doc (meta #'array-list/-items)) "Contents of the list"))
  (is (str/includes? (:doc (meta #'array-list/-items)) "items: aguafria.std.ArrayList/Slice"))
  (is (str/includes? (:doc (meta #'array-list/Slice)) "pub const Slice = if (alignment)"))
  (is (str/includes? (:doc (meta #'std/ArrayList)) "Fields:"))
  (is (str/includes? (:doc (meta #'std/ArrayList)) "aguafria.std.ArrayList/-items"))
  (is (= '([self]) (:arglists (meta #'array-list-slice/-len))))
  (is (= "list.items.len"
         (az/emit-expr '(aguafria.std.ArrayList.Slice/-len (aguafria.std.ArrayList/-items list)))))
  (with-open [slice (ak/as [1 2] [:slice :u21])]
    (is (= 2 (array-list-slice/-len slice)))
    (is (value/zig-pointer? (array-list-slice/-ptr slice))))
  (is (:field-accessor? (:aguafria/zig-reference (meta #'array-list/-items))))
  (is (= "list.items" (az/emit-expr '(aguafria.std.ArrayList/-items list))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one receiver"
                        (array-list/-items)))
  (let [version (:ok (semantic-version/parse "1.2.3"))]
    (is (= [1 2 3] [(semantic-version/-major version)
                    (semantic-version/-minor version)
                    (semantic-version/-patch version)])))
  (with-open [list (ak/var :.empty (std/ArrayList :u21))]
    (try
      (is (= [] (array-list/-items list)))
      (is (= 0 (array-list/-capacity list)))
      (array-list/append list zig-testing/allocator \☔)
      (is (= [9748] (array-list/-items list)))
      (is (= 1 (-> list array-list/-items array-list-slice/-len)))
      (is (= (az/field list :capacity) (array-list/-capacity list)))
      (finally (array-list/deinit list zig-testing/allocator))))
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (require '[aguafria.std :as std]
                 '[aguafria.std.ArrayList :as array-list]
                 '[aguafria.std.ArrayList.Slice :as array-list-slice]
                 '[aguafria.std.testing :as testing])
        (eval '(az/deftest field-vars-work-in-native-code
                 (let [list (ak/var :.empty (std/ArrayList :u21))]
                   (ak/defer (array-list/deinit list testing/allocator))
                   (try (array-list/append list testing/allocator \☔))
                   (try (testing/expectEqual 1 (-> list array-list/-items array-list-slice/-len)))
                   (try (testing/expect (> (array-list/-capacity list) 0)))))))
      (is (= :passed (:status ((ns-resolve namespace 'field-vars-work-in-native-code)))))
      (finally (remove-ns (ns-name namespace))))))

(deftest invalid-private-function-fails-at-its-own-definition
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (let [failure (try
                        (eval '(az/defn- change-constant :void []
                                 (let [constant 5678]
                                   (ak/+= constant 1))))
                        (catch clojure.lang.Compiler$CompilerException error error))]
          (is (some? failure))
          (is (str/includes? (or (:aguafria/report (runtime/error-data failure)) "")
                             "cannot assign to constant"))
          (is (nil? (ns-resolve namespace 'main)))))
      (finally (remove-ns (ns-name namespace))))))

(deftest undefined-storage-and-destructuring-from-the-jvm
  (with-open [x (ak/var ak/undefined :u32)
              y (ak/var ak/undefined :u32)
              z (ak/var ak/undefined :u32)
              numbers (az/array-init [4 5 6] [:array :_ :u32])
              lanes (ak/as [7 8 9] [:vector 3 :u32])]
    ;; Never read undefined storage. Initialize it before observing its contents.
    (ak/= [x y z] [1 2 3])
    (is (= [1 2 3] (mapv deref [x y z])))
    (ak/= [x y z] numbers)
    (is (= [4 5 6] (mapv deref [x y z])))
    (is (= [:array 3 :u32] (value/type numbers)))
    (ak/= [x y z] lanes)
    (is (= [7 8 9] (mapv deref [x y z])))
    (ak/= [x y] [y x])
    (is (= [8 7] (mapv deref [x y])))
    (ak/= [:_ x :_] [1 2 3])
    (is (= 2 @x))
    (is (nil? (ak/= :_ numbers)))))

(deftest explicit-initializers-use-value-first-on-the-jvm
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defstruct Point [[:x :i32] [:y :i32]])))
      (let [point-type @(ns-resolve namespace 'Point)]
        (with-open [point (az/init {:x 20 :y 22} point-type)]
          (is (= {:x 20 :y 22} @point))))
      (finally (remove-ns (ns-name namespace))))))

(deftest destructuring-then-mutable-rebinding-preserves-clojure-semantics
  (let [namespace (fixture)
        body '(let [tuple [1 2 3]
                    [x y z] tuple
                    x (ak/var x :u32)
                    y (ak/var y :u32)]
                (ak/= y 100)
                (ak/= [:_ x :_] tuple)
                (debug/print "{} {} {}\n" [x y z]))]
    (try
      (binding [*ns* namespace]
        (let [output (StringWriter.)]
          (binding [*err* output] (eval body))
          (is (= "2 100 3\n" (str output))))
        (eval (list 'az/defn 'mixed :void [] body)))
      (let [output (StringWriter.)]
        (binding [*err* output] ((ns-resolve namespace 'mixed)))
        (is (= "2 100 3\n" (str output))))
      (finally (remove-ns (ns-name namespace))))))

(deftest destructuring-retains-native-struct-error-and-slice-values
  (let [namespace (fixture)
        body '(let [tuple [(Point {:x 7})
                          (az/field Fault :Broken)
                          (ak/as "hello" [:slice-const :u8])]
                    [point fault text] tuple
                    point (ak/var point)]
                (ak/= point (Point {:x 9}))
                (debug/print "{} {} {s}\n" [(az/field point :x) fault text]))]
    (try
      (binding [*ns* namespace]
        (eval '(az/defstruct Point [[:x :i32]]))
        (eval '(az/defconst Fault (az/type [:error-set [:Broken]])))
        (let [output (StringWriter.)]
          (binding [*err* output] (eval body))
          (is (= "9 error.Broken hello\n" (str output))))
        (eval (list 'az/defn 'mixed-native-values :void [] body)))
      (let [output (StringWriter.)]
        (binding [*err* output] ((ns-resolve namespace 'mixed-native-values)))
        (is (= "9 error.Broken hello\n" (str output))))
      (finally (remove-ns (ns-name namespace))))))

(deftest nested-rebinding-keeps-outer-values
  (let [namespace (fixture)
        body '(let [x (ak/i32 7)
                    y (let [x (ak/i32 9)] x)]
                (+ x y))]
    (try
      (binding [*ns* namespace]
        (is (= 16 (eval body)))
        (eval (list 'az/defn 'nested :i32 [] body)))
      (is (= 16 ((ns-resolve namespace 'nested))))
      (finally (remove-ns (ns-name namespace))))))

(deftest zig-source-prints-declarations-types-and-values
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace runtime/*source-only-registration?* true]
        (eval '(az/defstruct Point "A point." [[:x :i32]]))
        (eval '(az/defconst limit :u32 42))
        (eval '(az/defvar counter :u32 7))
        (eval '(az/defn add :i32 [[x :i32]] (+ x 1)))
        (eval '(az/deftest addition-test (debug/assert true))))
      (doseq [[name expected] [['Point "/// A point."]
                              ['limit "const limit: u32 = 42;"]
                              ['counter "var counter: u32 = 7;"]
                              ['add "fn add(x: i32)"]
                              ['addition-test "test \"addition-test\""]]]
        (is (str/includes? (with-out-str (az/zig-source! (ns-resolve namespace name)))
                           expected)))
      (is (str/includes? (with-out-str (az/zig-source! @(ns-resolve namespace 'Point)))
                         "const Point = struct"))
      (is (= "?[]const u8\n" (with-out-str (az/zig-source! [:optional [:slice-const :u8]]))))
      (is (= "i32\n" (with-out-str (az/zig-source! ak/i32))))
      (is (= "[2]i32\n" (with-out-str (az/zig-source! [:array 2 ak/i32]))))
      (is (= "[2]Point\n" (with-out-str
                              (az/zig-source! [:array 2 @(ns-resolve namespace 'Point)]))))
      (is (= "42\n" (with-out-str (az/zig-source! 42))))
      (is (= "@import(\"std\").debug.print\n"
             (with-out-str (az/zig-source! #'debug/print))))
      (with-open [items (ak/as [4 5 6] [:array 3 :u32])]
        (is (= "@as([3]u32, .{ 4, 5, 6 })\n"
               (with-out-str (az/zig-source! items)))))
      (is (nil? (binding [*out* (StringWriter.)] (az/zig-source! :bool))))
      (finally (remove-ns (ns-name namespace))))))

(deftest native-errors-preserve-type-across-jvm-calls
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defconst ExampleErrorSet
                 (az/type [:error-set [:ExampleErrorVariant]]))))
      (let [error-type @(ns-resolve namespace 'ExampleErrorSet)
            error-value (az/field error-type :ExampleErrorVariant)
            err (StringWriter.)]
        (is (value/zig-error? error-value))
        (with-open [number-or-error (-> error-value
                                        (ak/as [:error-union error-type :i32])
                                        ak/var)]
          (binding [*err* err]
            (debug/print "{!}\n" [number-or-error])
            (ak/= number-or-error 1234)
            (debug/print "{!}\n" [number-or-error]))
          (is (= "error.ExampleErrorVariant\n1234\n" (str err)))
          (is (= {:ok 1234} @number-or-error))))
      (finally (remove-ns (ns-name namespace))))))

(deftest explicit-type-constants-are-inspectable-without-native-storage
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace runtime/*source-only-registration?* true]
        (eval '(az/defconst ExampleErrorSet "Expected failures."
                 (az/type [:error-set [:Missing :Denied]])))
        (eval '(az/defconst Buffer (az/type [:array 8 :u8]))))
      (let [error-var (ns-resolve namespace 'ExampleErrorSet)
            descriptor (value/type-info @error-var)]
        (is (az/zig-type? @error-var))
        (is (= :error-set (:kind descriptor)))
        (is (= [:Missing :Denied] (mapv :name (:members descriptor))))
        (is (str/includes? (pr-str @error-var) ":Missing"))
        (is (str/includes? (:doc (meta error-var)) "Expected failures."))
        (is (str/includes? (:doc (meta error-var)) ":Denied"))
        (is (= [:array 8 :u8]
               (:type (value/type-info @(ns-resolve namespace 'Buffer))))))
      (finally (remove-ns (ns-name namespace))))))

(deftest inferred-number-constants-are-readable-from-ordinary-clojure
  (let [namespace (fixture)]
    (binding [*ns* namespace]
      (require '[aguafria.std.math :as math])
      (doseq [form '[(az/defconst octal-int (az/number-literal "0o755"))
                    (az/defconst binary-int (az/number-literal "0b11110000"))
                    (az/defconst one-billion (az/number-literal "1_000_000_000"))
                    (az/defconst binary-mask (az/number-literal "0b1_1111_1111"))
                    (az/defconst permissions (az/number-literal "0o7_5_5"))
                    (az/defconst big-address (az/number-literal "0xFF80_0000_0000_0000"))
                    (az/defconst inf (math/inf :f32))
                    (az/defconst negative-inf (- (math/inf :f64)))
                    (az/defconst nan (math/nan :f128))
                    (az/defconst computed (+ 40 2))]]
        (eval form)))
    (let [read-constant #(value/value @(ns-resolve namespace %))]
      (is (= [493 240 1000000000 511 493 18410715276690587648N 42]
             (mapv read-constant '[octal-int binary-int one-billion binary-mask
                                  permissions big-address computed])))
      (is (= Double/POSITIVE_INFINITY (read-constant 'inf)))
      (is (= Double/NEGATIVE_INFINITY (read-constant 'negative-inf)))
      (is (Double/isNaN (read-constant 'nan)))
      (is (= 18410715276690587648N
             (ak/as @(ns-resolve namespace 'big-address) :u64)))
      (is (str/includes? (pr-str @(ns-resolve namespace 'inf)) "##Inf")))))

(deftest extern-linking-is-lazy-but-native-body-validation-is-not
  (doseq [already-running? [false true]]
   (let [namespace (fixture)]
    (binding [*ns* namespace]
      (when already-running?
        (eval '(az/defn running :i32 [] 42))
        (is (= 42 ((ns-resolve namespace 'running)))))
      (eval '(az/defextern aguafria_missing_test_symbol :i32 [[x :i32]]))
      (eval '(az/defn use-external :i32 [[x :i32]]
               (aguafria_missing_test_symbol x)))
      (is (:source-only? (runtime/module-info (ns-name namespace))))
      (let [failure (try
                      (eval '(az/defn invalid :i32 [[a :i32] [b :i32]] (/ a b)))
                      (catch Throwable failure failure))]
        (is (str/includes? (:aguafria/report (runtime/error-data failure))
                           "signed integers must use")))
      (let [failure (try
                      ((ns-resolve namespace 'use-external) 1)
                      (catch Throwable failure failure))]
        (is (str/includes? (:aguafria/report (runtime/error-data failure))
                           "aguafria_missing_test_symbol")))))))

(deftest ordinary-function-values-can-spawn-and-join-native-threads
  (let [namespace (fixture)]
    (binding [*ns* namespace]
      (require '[aguafria.std.Thread :as thread])
      (eval '(az/defn worker :void [] (debug/assert true)))
      (let [result (eval '(try (thread/spawn {} worker [])))]
        (is (value/zig-value? (:ok result)))
        (with-open [thread (:ok result)]
          (is (str/includes? (pr-str thread) "ZigValue"))
          (is (nil? ((az/field thread :join)))))))))

(deftest generic-private-functions-are-ordinary-callable-vars
  (let [namespace (fixture)]
    (binding [*ns* namespace]
      (eval '(az/defn- maximum T
               [[T {:zig/prefix "comptime"} :type] [left T] [right T]]
               (if (== T :bool)
                 (or left right)
                 (if (> left right) left right)))))
    (let [maximum (ns-resolve namespace 'maximum)]
      (is (true? (maximum :bool false true)))
      (is (false? (maximum :bool false false)))
      (is (= 8 (maximum :i32 3 8)))
      (is (= 12 (maximum :i32 12 4)))
      (is (true? (.invoke ^clojure.lang.IFn maximum :bool false true)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Wrong number"
                           (maximum :bool true)))
      (binding [*ns* namespace]
        (eval '(az/defn- maximum T
                 [[T {:zig/prefix "comptime"} :type] [left T] [right T]]
                 (if (== T :bool)
                   (and left right)
                   (if (< left right) left right)))))
      (is (false? (maximum :bool false true)))
      (is (= 3 (maximum :i32 3 8))))))

(deftest generic-calls-use-live-native-state
  (let [namespace (fixture)]
    (binding [*ns* namespace]
      (eval '(az/defvar calls :i64 0))
      (eval '(az/defn count-call :i64 [[T {:zig/prefix "comptime"} :type] [x T]]
               (ak/= :_ x)
               (ak/= calls (+ calls 1))
               calls))
      (eval '(az/defn count-now :i64 [] calls)))
    (let [count-call (ns-resolve namespace 'count-call)
          count-now (ns-resolve namespace 'count-now)]
      (is (= 1 (count-call :bool true)))
      (is (= 2 (count-call :bool false)))
      (is (= 2 (count-now))))))

(deftest native-output-is-restored-after-failure
  (let [err (StringWriter.)]
    (binding [*err* err]
      (is (thrown-with-msg? Exception #"deliberate"
                           (native-call/call-with-output
                            #(do (debug/print "before\n" [])
                                 (throw (Exception. "deliberate"))))))
      (debug/print "after\n" []))
    (is (= "before\nafter\n" (str err)))))

(deftest normal-zero-arg-function-forwards-native-output
  (let [namespace (fixture)
        err (StringWriter.)]
    (binding [*ns* namespace]
      (eval '(az/defn main :void [] (debug/print "native main\n" []))))
    (binding [*err* err]
      (is (nil? ((ns-resolve namespace 'main)))))
    (is (= "native main\n" (str err)))))

(deftest forced-inline-runtime-result-still-folds-at-the-callsite
  (let [namespace (fixture)
        err (StringWriter.)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defn- inline-add :i32 {:zig/prefix "inline"}
                 [[left :i32] [right :i32]]
                 (debug/print "inside inline call\n" [])
                 (+ left right)))
        (eval '(az/defn main :void []
                 (when (!= (inline-add 1200 34) 1234)
                   (ak/compileError "inline result no longer folds")))))
      (binding [*err* err]
        (is (nil? ((ns-resolve namespace 'main)))))
      (is (= "inside inline call\n" (str err)))
      (finally (remove-ns (ns-name namespace))))))

(deftest process-init-main-is-directly-callable
  (let [namespace (fixture)
        out (StringWriter.)]
    (try
      (binding [*ns* namespace]
        (require '[aguafria.std.process :as process]
                 '[aguafria.std.Io.File :as std-file])
        (eval '(az/defn main :!void [[init process/Init]]
                 (try (std-file/writeStreamingAll (std-file/stdout)
                                                  (az/field init :io)
                                                  "main in this JVM\n"))))
        (eval '(az/defn ordinary :i32 [[value :i32]] value)))
      (binding [*out* out]
        (is (nil? ((ns-resolve namespace 'main)))))
      (is (= "main in this JVM\n" (str out)))
      (is (= 42 ((ns-resolve namespace 'ordinary) 42)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)(arity|arguments)"
                           ((ns-resolve namespace 'ordinary))))
      (finally (remove-ns (ns-name namespace))))))

(deftest minimal-process-main-receives-argv
  (let [namespace (fixture)
        err (StringWriter.)]
    (try
      (binding [*ns* namespace]
        (require '[aguafria.std.process.Init :as process-init])
        (eval '(az/defn main :!void [[init process-init/Minimal]]
                 (let [arguments (az/field (az/field init :args) :vector)]
                   (debug/print "argc={d}; argv0={s}\n"
                                [(az/field arguments :len) (az/index arguments 0)])))))
      (binding [*err* err]
        (is (nil? ((ns-resolve namespace 'main))))
        (is (nil? ((ns-resolve namespace 'main) ["hello" "world"]))))
      (is (= "argc=1; argv0=main\nargc=3; argv0=main\n" (str err)))
      (finally (remove-ns (ns-name namespace))))))

(deftest module-struct-results-retain-field-names
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/deffield first-value :u32))
        (eval '(az/deffield second-value :u64))
        (eval '(az/defconst Record (ak/This)))
        (eval '(az/defn make-record Record [[value :u32]]
                 (az/object [[:first-value value] [:second-value (* value 10)]]))))
      (let [result ((ns-resolve namespace 'make-record) 42)]
        (try
          (is (= {:first-value 42 :second-value 420} (az/value result)))
          (finally (az/close! result))))
      (finally (remove-ns (ns-name namespace))))))

(deftest keyword-enum-members-are-callable-values
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defconst Mode
                 (az/container {:kind :enum :argument :c_int}
                   [(az/enum-field-decl :idle)
                    (az/enum-field-decl :running)])))
        (eval '(az/defn running? :bool [[mode Mode]] (== mode :.running))))
      (let [running? (ns-resolve namespace 'running?)]
        (is (false? (running? :idle)))
        (is (true? (running? :running)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown Zig enum member"
                              (running? :missing))))
      (finally (remove-ns (ns-name namespace))))))

(deftest quoted-native-export-is-looked-up-without-zig-syntax
  (let [namespace (fixture)]
    (try
      (binding [*ns* namespace]
        (eval '(az/defn sentence :i32
                 {:attrs #{:export} :zig/name "@\"A complete sentence.\""}
                 [] 42)))
      (is (= 42 ((ns-resolve namespace 'sentence))))
      (finally (remove-ns (ns-name namespace))))))

(deftest ordinary-java-program-calls-the-same-native-vars
  (let [source (io/file (io/resource "fixtures/jvm/NativeCallSmoke.java"))
        process (.start
                 (doto (ProcessBuilder.
                        ^java.util.List
                        [(str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         (.getAbsolutePath source)])
                   (.redirectErrorStream true)))
        output (slurp (.getInputStream process))]
    (is (zero? (.waitFor process)) output)
    (is (re-find #"Hello, Java!" output))
    (is (re-find #"maximum=true, sqrt=4.0, print=nil" output))))

(deftest native-panics-do-not-terminate-the-jvm
  (let [source (io/file (io/resource "fixtures/jvm/PanicSmoke.clj"))
        process (.start
                 (doto (ProcessBuilder.
                        ^java.util.List
                        [(str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" (.getAbsolutePath source)])
                   (.redirectErrorStream true)))
        output (future (slurp (.getInputStream process)))
        finished? (.waitFor process 120 java.util.concurrent.TimeUnit/SECONDS)]
    (when-not finished? (.destroyForcibly process))
    (is finished? "Native panic boundary must not hang")
    (is (and finished? (zero? (.exitValue process))) @output)
    (is (str/includes? @output "JVM survived native assertion and overflow") @output)))
