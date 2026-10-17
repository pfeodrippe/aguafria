(ns aguafria.zig.typeof-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.discovery :as discovery]
            [clojure.test :refer [deftest is]]))

(a/defn bump :i32 [[counter [:* :i32]]]
  (k/+= (a/deref counter) 1)
  (a/deref counter))

(a/defn aligned-zero :i32 {:align 16} [] 7)
(a/defn c-zero :i32 {:callconv :c} [] 7)

(deftest function-types-retain-their-owner-alignment-and-calling-convention
  (is (k/== (a/type [:fn {} [] :i32]) (k/TypeOf aligned-zero)))
  (is (k/== (a/type [:* {:const? true :align 16} [:fn {} [] :i32]])
            (k/TypeOf (k/& aligned-zero))))
  ;; Parameterized functions still use their original source identity. Sending
  ;; the resulting type to another adapter must qualify the owning declaration.
  (is (k/== (a/type [:fn {} [{:type [:* :i32]}] :i32]) (k/TypeOf bump)))
  (is (k/== (a/type [:fn {:callconv :c} [] :i32]) (k/TypeOf c-zero)))
  (is (k/!= (k/TypeOf aligned-zero) (k/TypeOf c-zero))))

(deftest typeof-is-unevaluated-but-still-checked-by-zig
  (is (:macro (meta #'k/TypeOf)))
  (with-open [counter (k/var 0 :i32)]
    (is (k/== (a/type :i32) (k/TypeOf (bump (k/& counter)))))
    (is (= 0 @counter))
    ;; An ordinary call remains an ordinary call; only TypeOf suppresses it.
    (bump (k/& counter))
    (is (= 1 @counter)))
  (is (thrown? Exception (k/TypeOf (bump 1)))))

(deftest typeof-does-not-write-back-comptime-mutable-captures
  (with-open [counter (k/var 0 :i32 {:attrs #{:comptime}})]
    (is (k/== (a/type :i32) (k/TypeOf counter)))
    (is (k/== (a/type :i32) (k/TypeOf (bump (k/& counter)))))
    (is (= 0 @counter))))

(deftest typeof-keeps-peer-types-and-real-string-representations
  (is (k/== (a/type :i32) (k/TypeOf (k/as 1 :i16) (k/as 2 :i32))))
  (let [text "hello"]
    (is (k/== (a/type [:*const [:array 5 {:sentinel 0} :u8]])
              (k/TypeOf text)))
    (with-open [slice (k/as text [:slice-const :u8])]
      (is (k/== (a/type [:slice-const :u8]) (k/TypeOf slice))))))

(deftest literal-provenance-is-syntax-only-and-excludes-mutable-bindings
  (let [binding-source (fn [name initializer variable?]
                         (with-meta name {:aguafria/jvm-binding-source
                                          {:initializer initializer :variable? variable?}}))
        literal (binding-source 'text "hello" false)
        alias (binding-source 'alias literal false)]
    (is (= "hello" (#'discovery/immutable-string-literal alias)))
    (is (nil? (#'discovery/immutable-string-literal
               (binding-source 'mutable "hello" true))))
    (is (nil? (#'discovery/immutable-string-literal
               (binding-source 'typed '(k/as "hello" [:slice-const :u8]) false))))
    (is (nil? (#'discovery/immutable-string-literal
               (binding-source 'application '(throw (Exception. "Must not execute")) false))))
    (is (nil? (#'discovery/immutable-string-literal
               (binding-source 'cycle 'cycle false))))))

(deftest qualification-retains-the-original-scoped-template
  (let [context (the-ns 'aguafria.zig.typeof-test)
        form '(k/TypeOf (bump (k/& counter)))
        qualified (binding [emitter/*lexical-bindings* #{'counter}]
                    (emitter/qualify-form context form))]
    (is (= form (:aguafria/scoped-template (meta qualified))))
    (is (= ['counter] (mapv first (:aguafria/scoped-captures (meta qualified)))))))
