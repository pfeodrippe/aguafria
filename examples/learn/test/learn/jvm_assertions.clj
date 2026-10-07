(ns learn.jvm-assertions
  "Observe native assertion results while replaying authored bodies on the JVM."
  (:require [aguafria.zig :as a]
            [aguafria.zig.jvm :as jvm]
            [clojure.string :as str]))

(defn call-with-checks
  "Run normal JVM code and surface failed std.testing expectations, including
  results that an ordinary Clojure let/do/try would otherwise discard. Native
  calls and successful results are unchanged; application error values remain
  data. Intended for sequential, isolated test workers."
  [invoke]
  (let [native-call jvm/invoke-reference!]
    (with-redefs [jvm/invoke-reference!
                  (fn [reference arguments]
                    (let [result (native-call reference arguments)
                          function (:symbol reference)]
                      (when (and (symbol? function)
                                 (= "aguafria.std.testing" (namespace function))
                                 (str/starts-with? (name function) "expect"))
                        (let [decoded (a/value result)]
                          (when (and (map? decoded) (= #{:error} (set (keys decoded))))
                            (throw (ex-info "Native testing expectation failed during JVM replay"
                                            {:function function :native-result decoded})))))
                      result))]
      (invoke))))
