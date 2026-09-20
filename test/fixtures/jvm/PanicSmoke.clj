(require '[aguafria.keyword :as ak]
         '[aguafria.std.debug :as debug]
         '[aguafria.zig :as az])

(defn expect-panic [invoke]
  (try
    (invoke)
    (throw (ex-info "Expected native panic was not reported" {}))
    (catch clojure.lang.ExceptionInfo failure
      (assert (= :native-panic (:aguafria/phase (ex-data failure))))
      (println "contained:" (:panic-message (ex-data failure))))))

(expect-panic #(debug/assert false))
(with-open [optional-value (ak/as nil [:optional [:slice-const :u8]])]
  (debug/assert (ak/== optional-value nil))
  (expect-panic #(debug/assert (ak/!= optional-value nil))))

(az/defn overflowing :i32 [[value :i32]] (+ value 1))
(expect-panic #(overflowing Integer/MAX_VALUE))
(az/deftest panic-test (debug/assert false))
(expect-panic panic-test)
(assert (= 42 (overflowing 41)))
(assert (= 42 (ak/+% 40 2)))
(debug/assert true)
(println "JVM survived native assertion and overflow")
(shutdown-agents)
