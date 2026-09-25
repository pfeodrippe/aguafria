(require '[aguafria.keyword :as ak]
         '[aguafria.std.debug :as debug]
         '[aguafria.zig :as az])

(defn expect-panic [invoke]
  (try
    (invoke)
    (throw (ex-info "Expected native panic was not reported" {}))
    (catch clojure.lang.ExceptionInfo failure
      (assert (= :native-panic (:aguafria/phase (ex-data failure))))
      (println "contained:" (:panic-message (ex-data failure)))
      failure)))

(defn expect-source-panic [invoke source-form]
  (let [failure (expect-panic invoke)
        details (ex-data failure)]
    (assert (= :execution (:clojure.error/phase details)) details)
    (assert (.endsWith (:clojure.error/source details) "PanicSmoke.clj") details)
    (assert (.contains (ex-message failure) source-form) (ex-message failure))
    (assert (= (:clojure.error/line details)
               (.getLineNumber (first (.getStackTrace failure)))) details)
    (assert (.contains (:hint details) "defers were not unwound"))
    (assert (seq (:native-frames details)))
    (println "source mapped:" source-form)))

(az/defn assert-second :void []
  (debug/assert true)
  (debug/assert false))

(az/defn nested-assertion :void []
  (assert-second))

(expect-source-panic nested-assertion "(debug/assert false)")

(az/defn explicit-panic :void []
  (ak/panic "specific failure"))
(expect-source-panic explicit-panic "(ak/panic \"specific failure\")")

(expect-panic #(debug/assert false))
(with-open [optional-value (ak/as nil [:optional [:slice-const :u8]])]
  (debug/assert (ak/== optional-value nil))
  (expect-panic #(debug/assert (ak/!= optional-value nil))))

(az/defn overflowing :i32 [[value :i32]] (+ value 1))
(expect-source-panic #(overflowing Integer/MAX_VALUE) "(+ value 1)")
(az/deftest panic-test (debug/assert false))
(expect-source-panic panic-test "(debug/assert false)")
(assert (= 42 (overflowing 41)))
(assert (= 42 (ak/+% 40 2)))
(debug/assert true)
(println "JVM survived native assertion and overflow")
(shutdown-agents)
