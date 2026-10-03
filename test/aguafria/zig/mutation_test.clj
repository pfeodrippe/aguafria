(ns aguafria.zig.mutation-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is]]))

(defn- fixture! []
  (let [context (or (find-ns 'aguafria.mutation-fixture)
                    (create-ns 'aguafria.mutation-fixture))]
    (binding [*ns* context runtime/*source-only-registration?* true]
      (refer 'clojure.core)
      (alias 'a 'aguafria.zig)
      (alias 'k 'aguafria.keyword)
      (eval '(a/defstruct Pair [[:x :i32] [:y :i32] [:enabled :bool]]))
      (eval '(a/defstruct Config [[:pair Pair] [:values [:array 2 :i32]]
                                  [:pointer [:optional [:*const :i32]]]]))
      (eval '(a/defstruct Text [[:label [:slice-const :u8]]]))
      (eval '(a/defstruct Envelope [[:text Text]]))
      (eval '(a/defstruct FormatConfig [[:format :u32]]))
      (eval '(a/defconst format-value :c_int 5))
      (eval '(a/defn native-format :u32 []
               (let [config (k/var (FormatConfig {:format 0}))]
                 (a/merge! config {:format format-value})
                 (:format config))))
      (eval '(a/defstruct CallbackConfig
               [[:callback [:optional [:*const [:fn {:callconv :.c} [{:type :i32}] :i32]]]]]))
      (eval '(a/defn callback :i32 {:zig/qualifiers "callconv(.c)"} [[x :i32]]
               (k/+ x 1)))
      (eval '(a/defn call-config :i32 [[config CallbackConfig]]
               ((a/unwrap (:callback config)) 41)))
      (eval '(a/defn native-update :i32 []
               (let [p (k/var (Pair {:x 1 :y 2 :enabled false}))]
                 (a/merge! p {:x (:y p) :y (:x p) :enabled true})
                 (a/assoc! (a/assoc! p :x 7) :y 8)
                 (k/+ (:x p) (:y p)))))
      (eval '(a/defn native-index :i32 []
               (let [a (k/var (a/array [1 2] :i32))]
                 (a/assoc! a 0 (a/get a 1) 1 (a/get a 0))
                 (k/+ (k/* (a/get a 0) 10) (a/get a 1))))))
    context))

(deftest native-and-jvm-shallow-mutation
  (let [context (fixture!)
        Pair (var-get (ns-resolve context 'Pair))
        Config (var-get (ns-resolve context 'Config))
        immutable (Pair {:x 1 :y 2 :enabled false})
        p (k/var immutable)]
    (is (not (:macro (meta #'a/assoc!))))
    (is (not (:macro (meta #'a/merge!))))
    (is (identical? p (a/assoc! p :x (:y p) :y (:x p))))
    (is (= {:x 2 :y 1 :enabled false} (a/value p)))
    (is (identical? p (a/merge! p {:enabled true})))
    (is (true? (:enabled p)))
    (is (identical? p (a/merge! p {})))
    (is (= 15 (a/value ((ns-resolve context 'native-update)))))
    (is (= 21 (a/value ((ns-resolve context 'native-index)))))
    (let [FormatConfig (var-get (ns-resolve context 'FormatConfig))
          config (k/var (FormatConfig {:format 0}))]
      (a/merge! config {:format (var-get (ns-resolve context 'format-value))})
      (is (= 5 (a/value (:format config))))
      (is (= 5 (a/value ((ns-resolve context 'native-format))))))
    (let [config (k/var (Config {:pair {:x 3 :y 4 :enabled false}
                                 :values [10 20]
                                 :pointer nil}))
          pair (:pair config)
          array (:values config)
          scalar (k/i32 42)]
      (is (identical? pair (a/merge! pair {:x 9 :enabled true})))
      (is (= {:x 9 :y 4 :enabled true} (a/value (:pair config))))
      (is (identical? array (a/assoc! array 0 (a/get array 1) 1 (a/get array 0))))
      (is (= [20 10] (a/value (:values config))))
      (a/assoc! config :pointer (k/& scalar))
      (is (= 42 (a/value @(:pointer config)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mutable native storage"
                         (a/assoc! immutable :x 3)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"key/value pairs"
                         (a/assoc! p :x)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"map of updates"
                         (a/merge! p [:x 3])))
    (is (thrown? Exception (a/assoc! p :x 99 :missing 2)))
    (is (= 2 (a/value (:x p))))
    (is (thrown? Exception (a/assoc! p :x 99 :enabled "not a boolean")))
    (is (= 2 (a/value (:x p))))
    (let [Envelope (var-get (ns-resolve context 'Envelope))
          envelope (k/var (Envelope {:text {:label "before"}}))]
      (a/merge! (:text envelope) {:label (str "after " (System/nanoTime))})
      (System/gc)
      (is (clojure.string/starts-with? (a/value (:label (:text envelope))) "after ")))
    (let [CallbackConfig (var-get (ns-resolve context 'CallbackConfig))
          config (k/var (CallbackConfig {:callback nil}))
          callback (var-get (ns-resolve context 'callback))]
      (is (identical? config (a/merge! config {:callback (k/& callback)})))
      (is (= 42 (a/value ((ns-resolve context 'call-config) config)))))))

(deftest mutation-emission-validates-shape
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"key/value pairs"
                       (emitter/emit-expr '(assoc! target :x))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"literal map"
                       (emitter/emit-expr '(merge! target updates)))))

(deftest native-storage-crosses-optimization-profiles-through-c-abi
  (let [configuration (runtime/configuration)]
    (try
      (doseq [optimize ["debug" "safe" "fast" "small"]]
        (runtime/configure! {:optimize optimize :jvm-optimize "safe"})
        (let [context (create-ns (symbol (str "aguafria.storage-abi-" (random-uuid))))]
          (try
            (binding [*ns* context runtime/*source-only-registration?* true]
              (refer 'clojure.core)
              (alias 'a 'aguafria.zig)
              (eval '(a/defstruct Holder [[:pointer [:optional [:*const :i32]]]])))
            (let [Holder (var-get (ns-resolve context 'Holder))
                  holder (k/var (Holder {:pointer nil}))
                  number (k/i32 42)]
              (a/assoc! holder :pointer (k/& number))
              (is (= 42 (a/value @(:pointer holder))) optimize))
            (finally (remove-ns (ns-name context))))))
      (finally (runtime/configure! configuration)))))
