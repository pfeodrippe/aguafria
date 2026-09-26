(ns aguafria.zig.host-eval-test
  (:require [aguafria.zig :as az]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.runtime :as runtime]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- in-host-context! [f]
  (let [context (create-ns (gensym "aguafria.host-eval-"))
        configuration (az/configuration)]
    (try
      (az/configure! {:async? false})
      (binding [*ns* context]
        (refer 'clojure.core)
        (require '[aguafria.zig :as az]
                 '[aguafria.keyword :as k]
                 '[clojure.string :as string])
        (f))
      (finally
        (az/configure! configuration)
        (remove-ns (ns-name context))))))

(deftest host-computed-types-and-values-are-equivalent
  (in-host-context!
   (fn []
     (let [declarations (atom [])]
       (binding [runtime/*registration-batch* declarations]
         (eval '(defn array-n [n] [:array n :u8]))
         (eval '(defn split [s] (vec (seq s))))
         (doseq [form
                 '[(az/defconst alt-message [:array 5 :u8] [\h \e \l \l \o])
                   (az/defconst alt-message-2 (az/clj! (array-n 5)) [\h \e \l \l \o])
                   (az/defconst alt-message-3
                     (az/clj! (array-n 5)) (az/clj! (split "hello")))]]
           (eval form))
         (is (= (repeat 3 {:type [:array 5 :u8] :value [\h \e \l \l \o]})
                (map #(select-keys % [:type :value]) @declarations)))
         (eval '(az/defconst aliased (az/clj! (string/upper-case "hello")))))
       (is (= "HELLO" (:value (last @declarations))))))))

(deftest host-escapes-work-in-function-and-container-positions
  (in-host-context!
   (fn []
     (let [declarations (atom [])]
       (binding [runtime/*registration-batch* declarations]
         (eval '(def calls (atom 0)))
         (eval '(az/defn add :i32
                  [[x (az/clj! :i32)]]
                  (let [amount (az/clj! (+ 2 3))]
                    (k/+ x amount))))
         (is (= :i32 (-> @declarations last :args first :type)))
         (is (= 5 (-> @declarations last :body first second second)))
         (eval '(az/defn answer (az/clj! :i32) [] (az/clj! 42)))
         (is (= :i32 (:return (last @declarations))))
         (is (= [42] (:body (last @declarations))))
         (eval '(az/defstruct Point
                  [[:x (az/clj! (do (swap! calls inc) :i32))]
                   [:y {:default (az/clj! (+ 1 2))} :i32]]))
         (is (= 1 (eval '@calls)) "One field escape is not rerun for duplicated descriptor data")
         (is (= [:i32 :i32] (mapv :type (:fields (last @declarations)))))
         (is (str/includes? (emitter/emit-declaration (last @declarations)) "y: i32 = 3")))))))

(deftest host-values-are-not-executable-returned-code
  (in-host-context!
   (fn []
     (is (= [:array 5 :u8] (eval '(az/clj! [:array 5 :u8]))))
     (is (= 'hello (eval '(az/clj! 'hello))))
     (doseq [value [nil false 7 "hello" \h {:x [1 2]} #{:one :two}]]
       (is (= value (eval (list 'az/clj! (list 'quote value))))))
     (doseq [form '[(az/clj!)
                   (az/clj! {:as :form} '(k/+ 1 2))
                   (az/clj! '(System/exit 0))
                   (az/clj! {:nested '(k/+ 1 2)})
                   (az/clj! (Object.))]]
       (is (thrown? Exception (eval form)) (pr-str form))))))

(deftest host-failures-retain-cause-and-source
  (in-host-context!
   (fn []
     (let [form (with-meta '(az/clj! (throw (ex-info "host exploded" {:marker 42})))
                  {:line 27 :column 9})
           failure (try (eval form) (catch Throwable error error))
           data (runtime/error-data failure)]
       (is (= :clojure-escape (:aguafria/phase data)))
       (is (= 42 (:marker data)))
       (is (= 27 (:line (meta (:form data)))))
       (is (some #(str/includes? (or (ex-message %) "") "host exploded")
                 (take-while some? (iterate ex-cause failure)))))
     (binding [runtime/*registration-batch* (atom [])]
       (is (thrown? Exception
                    (eval '(az/defn unavailable :i32 [[native-x :i32]]
                             (az/clj! native-x)))))))))

(deftest host-values-are-frozen-until-declaration-reevaluation
  (in-host-context!
   (fn []
     (eval '(def calls (atom 0)))
     (eval '(defn host-answer [] (swap! calls inc) 21))
     (let [form '(az/defn answer (az/clj! :i32) [] (az/clj! (host-answer)))]
       (eval form)
       (let [answer (ns-resolve *ns* 'answer)
             first-source (az/source (ns-name *ns*))]
         (is (= 21 (answer)))
         (is (= 21 (answer)))
         (is (= 1 (eval '@calls)))
         (eval '(defn host-answer [] (swap! calls inc) 42))
         (is (= 21 (answer)))
         (is (= first-source (az/source (ns-name *ns*))))
         (is (= 1 (eval '@calls)))
         (eval form)
         (is (= 42 (answer)))
         (is (= 2 (eval '@calls)))
         (is (not= first-source (az/source (ns-name *ns*)))))))))

(deftest host-computed-array-values-execute-as-native-zig
  (in-host-context!
   (fn []
     (require '[aguafria.std.mem :as mem])
     (eval '(defn array-n [n] [:array n :u8]))
     (eval '(defn split [s] (vec s)))
     (doseq [form
             '[(az/defconst alt-message [:array 5 :u8] [\h \e \l \l \o])
               (az/defconst alt-message-2 (az/clj! (array-n 5)) [\h \e \l \l \o])
               (az/defconst alt-message-3 (az/clj! (array-n 5)) (az/clj! (split "hello")))
               (let [sss (fn [s] (vec (seq s)))]
                 (az/defconst alt-message-4
                   (az/clj! (array-n 5)) (az/clj! (sss "hello"))))
               (az/defn matching (az/clj! :bool)
                 [[expected-length (az/clj! :usize)]]
                 (k/and (k/== (az/field alt-message :len) expected-length)
                        (mem/eql :u8 (k/& alt-message) (k/& alt-message-2))
                        (mem/eql :u8 (k/& alt-message) (k/& alt-message-3))
                        (mem/eql :u8 (k/& alt-message) (k/& alt-message-4))))]]
       (eval form))
     (is (true? ((ns-resolve *ns* 'matching) 5)))
     (is (not (str/includes? (az/source (ns-name *ns*)) "clj!"))))))

(deftest host-escapes-capture-surrounding-clojure-bindings
  (in-host-context!
   (fn []
     (let [declarations (atom [])]
       (binding [runtime/*registration-batch* declarations]
         (eval '(defn array-n [n] [:array n :u8]))
         (eval '(let [sss (fn [s] (vec (seq s)))]
                  (az/defconst alt-message-4
                    (az/clj! (array-n 5))
                    (az/clj! (sss "hello")))))
         (is (= {:type [:array 5 :u8] :value [\h \e \l \l \o]}
                (select-keys (last @declarations) [:type :value])))
         (eval '(let [n 5
                      scalar :i32
                      initial 7]
                  (az/defconst inferred-size
                    (az/clj! [:array n :u8])
                    (az/clj! (vec (repeat n 42))))
                  (az/defstruct Captured [[:x (az/clj! scalar)]])
                  (az/defvar captured-state (az/clj! scalar) (az/clj! initial))))
         (is (= {:type :i32 :value 7}
                (select-keys (last @declarations) [:type :value])))
         (is (= :i32 (-> @declarations (nth 2) :fields first :type))))))))

(deftest host-escapes-run-on-execution-not-macroexpansion
  (in-host-context!
   (fn []
     (eval '(def hits (atom 0)))
     (macroexpand '(az/defconst delayed (az/clj! (swap! hits inc))))
     (is (zero? (eval '@hits)))
     (eval '(defn install! [value]
              (az/defconst captured :i32 (az/clj! (do (swap! hits inc) value)))))
     (is (zero? (eval '@hits)) "Compiling a factory must not evaluate its escapes")
     (let [declarations (atom [])]
       (binding [runtime/*registration-batch* declarations]
         (eval '(install! 21))
         (eval '(install! 42)))
       (is (= [21 42] (mapv :value @declarations)))
       (is (= 2 (eval '@hits)))))))

(deftest lexical-native-functions-recapture-on-factory-invocation
  (in-host-context!
   (fn []
     (eval '(defn install! [scalar value]
              (az/defn captured (az/clj! scalar)
                [[x (az/clj! scalar)]]
                (k/+ x (az/clj! value)))))
     (eval '(install! :i32 10))
     (let [captured (ns-resolve *ns* 'captured)]
       (is (= 13 (captured 3)))
       (eval '(install! :i32 20))
       (is (= 23 (captured 3)))
       (is (= :i32 (-> captured meta :aguafria/declaration :return)))
       (is (= :i32 (-> captured meta :aguafria/declaration :args first :type)))))))
