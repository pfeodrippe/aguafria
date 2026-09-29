(ns aguafria.zig.debug-test
  (:require [aguafria.zig.debug :as debug]
            [clojure.test :refer [deftest is]]))

(defn- parse-reports [stderr]
  (let [reports (atom [])]
    (with-redefs [debug/report! (fn [report _] (swap! reports conj report))]
      (#'debug/report-compiler-output!
       stderr {"ab12" {:file "example.clj" :line 10 :column 3 :form "x"}} {}))
    @reports))

(deftest read-only-compiler-log-results-not-diagnostic-source-lines
  (let [reports (parse-reports
                 (str "error: found compile log statement\n"
                      "@compileLog(\"aguafria.debug:ab12:\" ++ @typeName(@TypeOf(x)));\n"
                      "Compile Log Output:\n"
                      "@as(*const [24:0]u8, \"aguafria.debug:ab12:i32\")\n"
                      "@as(*const [24:0]u8, \"aguafria.debug:ab12:f32\")\n"))]
    (is (= #{"i32" "f32"} (set (map :type reports))))
    (is (every? #(= :ok (:status %)) reports))
    (is (every? #(= "example.clj" (:file %)) reports))
    (is (every? #(= 10 (:line %)) reports))))

(deftest uninstantiated-expressions-have-no-invented-types
  (is (= [] (parse-reports "")))
  (let [[report] (parse-reports "error: @intCast must have a known result type\n")]
    (is (= :unavailable (:status report)))
    (is (nil? (:type report)))
    (is (re-find #"known result type" (:diagnostics report)))))
