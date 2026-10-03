(ns aguafria.zig.host-eval-test
  (:require [aguafria.zig :as a]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.runtime :as runtime]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- in-host-context! [f]
  (let [context (create-ns (gensym "aguafria.host-eval-"))
        configuration (a/configuration)]
    (try
      (a/configure! {:async? false})
      (binding [*ns* context]
        (refer 'clojure.core)
        (require '[aguafria.zig :as a]
                 '[aguafria.keyword :as k]
                 '[clojure.string :as string])
        (f))
      (finally
        (a/configure! configuration)
        (remove-ns (ns-name context))))))

(deftest host-computed-types-and-values-are-equivalent
  (in-host-context!
   (fn []
     (let [declarations (runtime/registration-batch)]
       (binding [runtime/*registration-batch* declarations]
         (eval '(defn array-n [n] [:array n :u8]))
         (eval '(defn split [s] (vec (seq s))))
         (doseq [form
                 '[(a/defconst alt-message [:array 5 :u8] [\h \e \l \l \o])
                   (a/defconst alt-message-2 (a/clj! (array-n 5)) [\h \e \l \l \o])
                   (a/defconst alt-message-3
                     (a/clj! (array-n 5)) (a/clj! (split "hello")))]]
           (eval form))
         (is (= (repeat 3 {:type [:array 5 :u8] :value [\h \e \l \l \o]})
                (map #(select-keys % [:type :value]) (runtime/collected-declarations declarations))))
         (eval '(a/defconst aliased (a/clj! (string/upper-case "hello")))))
       (is (= "HELLO" (:value (last (runtime/collected-declarations declarations)))))))))

(deftest host-escapes-work-in-function-and-container-positions
  (in-host-context!
   (fn []
     (let [declarations (runtime/registration-batch)]
       (binding [runtime/*registration-batch* declarations]
         (eval '(def calls (atom 0)))
         (eval '(a/defn add :i32
                  [[x (a/clj! :i32)]]
                  (let [amount (a/clj! (+ 2 3))]
                    (k/+ x amount))))
         (is (= :i32 (-> (runtime/collected-declarations declarations) last :args first :type)))
         (is (= 5 (-> (runtime/collected-declarations declarations) last :body first second second)))
         (eval '(a/defn answer (a/clj! :i32) [] (a/clj! 42)))
         (is (= :i32 (:return (last (runtime/collected-declarations declarations)))))
         (is (= [42] (:body (last (runtime/collected-declarations declarations)))))
         (eval '(a/defstruct Point
                  [[:x (a/clj! (do (swap! calls inc) :i32))]
                   [:y {:default (a/clj! (+ 1 2))} :i32]]))
         (is (= 1 (eval '@calls)) "One field escape is not rerun for duplicated descriptor data")
         (is (= [:i32 :i32] (mapv :type (:fields (last (runtime/collected-declarations declarations))))))
         (is (str/includes? (emitter/emit-declaration (last (runtime/collected-declarations declarations))) "y: i32 = 3")))))))

(deftest host-values-are-not-executable-returned-code
  (in-host-context!
   (fn []
     (is (= [:array 5 :u8] (eval '(a/clj! [:array 5 :u8]))))
     (is (= 'hello (eval '(a/clj! 'hello))))
     (doseq [value [nil false 7 "hello" \h {:x [1 2]} #{:one :two}]]
       (is (= value (eval (list 'a/clj! (list 'quote value))))))
     (doseq [form '[(a/clj!)
                   (a/clj! {:as :form} '(k/+ 1 2))
                   (a/clj! '(System/exit 0))
                   (a/clj! {:nested '(k/+ 1 2)})
                   (a/clj! (Object.))]]
       (is (thrown? Exception (eval form)) (pr-str form))))))

(deftest host-failures-retain-cause-and-source
  (in-host-context!
   (fn []
     (let [form (with-meta '(a/clj! (throw (ex-info "host exploded" {:marker 42})))
                  {:line 27 :column 9})
           failure (try (eval form) (catch Throwable error error))
           data (runtime/error-data failure)]
       (is (= :clojure-escape (:aguafria/phase data)))
       (is (= 42 (:marker data)))
       (is (= 27 (:line (meta (:form data)))))
       (is (some #(str/includes? (or (ex-message %) "") "host exploded")
                 (take-while some? (iterate ex-cause failure)))))
     (binding [runtime/*registration-batch* (runtime/registration-batch)]
       (is (thrown? Exception
                    (eval '(a/defn unavailable :i32 [[native-x :i32]]
                             (a/clj! native-x)))))))))

(deftest host-values-are-frozen-until-declaration-reevaluation
  (in-host-context!
   (fn []
     (eval '(def calls (atom 0)))
     (eval '(defn host-answer [] (swap! calls inc) 21))
     (let [form '(a/defn answer (a/clj! :i32) [] (a/clj! (host-answer)))]
       (eval form)
       (let [answer (ns-resolve *ns* 'answer)
             first-source (a/source (ns-name *ns*))]
         (is (= 21 (a/value (answer))))
         (is (= 21 (a/value (answer))))
         (is (= 1 (eval '@calls)))
         (eval '(defn host-answer [] (swap! calls inc) 42))
         (is (= 21 (a/value (answer))))
         (is (= first-source (a/source (ns-name *ns*))))
         (is (= 1 (eval '@calls)))
         (eval form)
         (is (= 42 (a/value (answer))))
         (is (= 2 (eval '@calls)))
         (is (not= first-source (a/source (ns-name *ns*)))))))))

(deftest host-computed-array-values-execute-as-native-zig
  (in-host-context!
   (fn []
     (require '[aguafria.std.mem :as mem])
     (eval '(defn array-n [n] [:array n :u8]))
     (eval '(defn split [s] (vec s)))
     (doseq [form
             '[(a/defconst alt-message [:array 5 :u8] [\h \e \l \l \o])
               (a/defconst alt-message-2 (a/clj! (array-n 5)) [\h \e \l \l \o])
               (a/defconst alt-message-3 (a/clj! (array-n 5)) (a/clj! (split "hello")))
               (let [sss (fn [s] (vec (seq s)))]
                 (a/defconst alt-message-4
                   (a/clj! (array-n 5)) (a/clj! (sss "hello"))))
               (a/defn matching (a/clj! :bool)
                 [[expected-length (a/clj! :usize)]]
                 (k/and (k/== (a/field alt-message :len) expected-length)
                        (mem/eql :u8 (k/& alt-message) (k/& alt-message-2))
                        (mem/eql :u8 (k/& alt-message) (k/& alt-message-3))
                        (mem/eql :u8 (k/& alt-message) (k/& alt-message-4))))]]
       (eval form))
     (is (true? ((ns-resolve *ns* 'matching) 5)))
     (is (not (str/includes? (a/source (ns-name *ns*)) "clj!"))))))

(deftest host-escapes-capture-surrounding-clojure-bindings
  (in-host-context!
   (fn []
     (let [declarations (runtime/registration-batch)]
       (binding [runtime/*registration-batch* declarations]
         (eval '(defn array-n [n] [:array n :u8]))
         (eval '(let [sss (fn [s] (vec (seq s)))]
                  (a/defconst alt-message-4
                    (a/clj! (array-n 5))
                    (a/clj! (sss "hello")))))
         (is (= {:type [:array 5 :u8] :value [\h \e \l \l \o]}
                (select-keys (last (runtime/collected-declarations declarations)) [:type :value])))
         (eval '(let [n 5
                      scalar :i32
                      initial 7]
                  (a/defconst inferred-size
                    (a/clj! [:array n :u8])
                    (a/clj! (vec (repeat n 42))))
                  (a/defstruct Captured [[:x (a/clj! scalar)]])
                  (a/defvar captured-state (a/clj! scalar) (a/clj! initial))))
         (is (= {:type :i32 :value 7}
                (select-keys (last (runtime/collected-declarations declarations)) [:type :value])))
         (is (= :i32 (-> (runtime/collected-declarations declarations) (nth 2) :fields first :type))))))))

(deftest host-escapes-run-on-execution-not-macroexpansion
  (in-host-context!
   (fn []
     (eval '(def hits (atom 0)))
     (macroexpand '(a/defconst delayed (a/clj! (swap! hits inc))))
     (is (zero? (eval '@hits)))
     (eval '(defn install! [value]
              (a/defconst captured :i32 (a/clj! (do (swap! hits inc) value)))))
     (is (zero? (eval '@hits)) "Compiling a factory must not evaluate its escapes")
     (let [declarations (runtime/registration-batch)]
       (binding [runtime/*registration-batch* declarations]
         (eval '(install! 21))
         (eval '(install! 42)))
       (is (= [21 42] (mapv :value (runtime/collected-declarations declarations))))
       (is (= 2 (eval '@hits)))))))

(deftest lexical-native-functions-recapture-on-factory-invocation
  (in-host-context!
   (fn []
     (eval '(defn install! [scalar value]
              (a/defn captured (a/clj! scalar)
                [[x (a/clj! scalar)]]
                (k/+ x (a/clj! value)))))
     (eval '(install! :i32 10))
     (let [captured (ns-resolve *ns* 'captured)]
       (is (= 13 (a/value (captured 3))))
       (eval '(install! :i32 20))
       (is (= 23 (a/value (captured 3))))
       (is (= :i32 (-> captured meta :aguafria/declaration :return)))
       (is (= :i32 (-> captured meta :aguafria/declaration :args first :type)))))))
