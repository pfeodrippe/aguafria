(ns aguafria.zig.constructor-test
  (:require [aguafria.keyword]
            [aguafria.zig :as a]
            [aguafria.zig.emitter :as emit]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest local-type-constructors-respect-lexical-scope
  (let [body (fn [form]
               (-> (emit/prepare-declaration *ns* {:args [{:name 'callback :type :anytype}]
                                                  :body [form]})
                   :body first emit/emit-expr))]
    (testing "locally declared types and their aliases are constructors"
      (let [source (body '(let [Local (aguafria.zig/struct [[:x :u8]])
                               Alias Local
                               value (Alias {:x 7})]
                           value))]
        (is (str/includes? source "const value = Alias{.x = 7};"))))
    (testing "a nested ordinary function binding shadows the type"
      (let [source (body '(let [Local (aguafria.zig/struct [[:x :u8]])]
                           (let [Local callback]
                             (Local {:x 7}))
                           (Local {:x 9})))]
        (let [[_ local-name] (re-find #"const ([A-Za-z0-9_]+) = callback;" source)]
          (is (some? local-name))
          (is (str/includes? source (str local-name "(.{.x = 7})"))))
        (is (str/includes? source "Local{.x = 9}"))))
    (testing "ordinary functions receiving maps are not constructors"
      (is (str/includes? (body '(let [f callback] (f {:x 7})))
                         "f(.{.x = 7})")))))

(deftest constructors-preserve-zig-defaults
  (let [old-config (a/configuration)
        test-symbol (symbol (str "aguafria.constructor-defaults-"
                                 (or (System/getProperty "aguafria.test.fixture-suffix")
                                     (random-uuid))))
        test-ns (create-ns test-symbol)]
    (try
      (a/configure! {:async? false :modules {}})
      (binding [*ns* test-ns]
        (refer 'clojure.core)
        (alias 'a 'aguafria.zig)
        (eval '(a/defn- default-number :i32 [] (+ 1200 34)))
        (eval '(a/defstruct Foo
                 [[:a {:default (default-number)} :i32]
                  [:b :i32]]))
        (eval '(a/defstruct Packed {:layout :packed}
                 [[:low {:default 7} :u4]
                  [:high :u4]]))
        (eval '(a/defstruct Options
                 [[:enabled {:default true} :bool]
                  [:number {:default 9} [:optional :i32]]
                  [:packed {:default (Packed {:high 2})} Packed]
                  [:inner {:default (Foo {:b 5})} Foo]]))
        (eval '(a/defn sum-fields :i32 [[value Foo]]
                 (+ (a/field value :a) (a/field value :b))))
        (eval '(a/defn native-constructor-sum :i32 []
                 (let [value (Foo {:b 5})]
                   (sum-fields value))))
        (eval '(a/defn local-constructor-sum :i32 []
                 (let [Local (a/struct [[:a {:default 1234} :i32] [:b :i32]])
                       Alias Local
                       ^:var value (Alias {:b 5})]
                   (set! (a/field value :b) 6)
                   (+ (a/field value :a) (a/field value :b))))))
      (let [Foo (var-get (ns-resolve test-ns 'Foo))
            Packed (var-get (ns-resolve test-ns 'Packed))
            Options (var-get (ns-resolve test-ns 'Options))]
        (with-open [value (Foo {:b 5})
                    overridden (Foo {:a 0 :b 5})
                    packed (Packed {:high 2})
                    options (Options {})
                    explicit (Options {:enabled false :number nil
                                       :packed {:high 3} :inner {:b 6}})]
          (is (= {:a 1234 :b 5} (a/value value)))
          (is (= {:a 0 :b 5} (a/value overridden)))
          (is (= {:low 7 :high 2} (a/value packed)))
          (is (= {:enabled true :number 9 :packed {:low 7 :high 2}
                  :inner {:a 1234 :b 5}}
                 (a/value options)))
          (is (= {:enabled false :number nil :packed {:low 7 :high 3}
                  :inner {:a 1234 :b 6}}
                 (a/value explicit))))
        (is (= 1239 (a/value ((ns-resolve test-ns 'sum-fields) {:b 5}))))
        (is (= 1239 (a/value ((ns-resolve test-ns 'native-constructor-sum)))))
        (is (= 1240 (a/value ((ns-resolve test-ns 'local-constructor-sum))))))
      (finally
        (a/configure! old-config)
        (remove-ns test-symbol)))))
