(ns aguafria.zig.syntax-sugar-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.value :as value]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(a/defenum Tags [:little :big])
(a/defn little-tag? :bool [[tag Tags]] (k/== tag :little))
(a/defn successful-branch :!void [] (k/return))
(a/defn failing-branch :!void [] (k/return (a/error-value :SelectedFailure)))

(deftest flat-switch-normalizes-to-native-clauses
  (let [emit #(emitter/emit-expr (the-ns 'aguafria.zig.syntax-sugar-test) %)]
    (is (= (emit '(k/switch tag (case [:.little] 1) (case [:.big] 2)))
           (emit '(k/switch tag :little 1 :big 2))))
    (is (= (emit '(k/switch tag (case [:.little :.big] 1) (a/case-else 2)))
           (emit '(k/switch tag [:little :big] 1 2))))
    (is (str/includes? (emit '(k/switch tag :type 1 2)) ".type => 1"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Do not mix"
                          (emit '(k/switch tag (case [0] 1) :big 2))))))

(deftest enum-keywords-are-values-without-changing-types-or-field-names
  (is (= ".little" (emitter/emit-expr :little)))
  (is (= ".some_tag" (emitter/emit-expr :some-tag)))
  (is (= "u32" (emitter/emit-expr :u32)))
  (is (= "u32" (emitter/emit-type :u32)))
  (is (= "Point" (emitter/emit-type :Point)))
  (is (= ".little" (emitter/emit-expr :.little)))
  (is (jvm/native-literal? :little))
  (is (not (jvm/native-literal? :u32)))
  (is (str/includes? (emitter/emit-expr '(field point :little)) ".little")))

(deftest flat-switch-runs-only-the-selected-branch-with-ordinary-jvm-captures
  (with-open [tag (k/as :little Tags)
              counter (k/var 0 :usize)]
    (is (true? (little-tag? :little)))
    (is (false? (little-tag? :big)))
    (is (true? (k/== tag :little)))
    (k/switch tag
      :little (k/+= counter 1)
      :big (k/+= counter 100))
    (is (= 1 @counter))
    (k/switch tag
      :big (k/+= counter 100)
      (k/+= counter 2))
    (is (= 3 @counter))))

(deftest qualified-native-try-propagates-only-from-the-selected-switch-branch
  (with-open [little (k/as :little Tags)
              big (k/as :big Tags)]
    (is (nil? (k/switch little
                :little (k/try (successful-branch))
                :big (k/try (failing-branch)))))
    (is (= :SelectedFailure
           (try
             (k/switch big
               :little (k/try (successful-branch))
               :big (k/try (failing-branch)))
             (catch clojure.lang.ExceptionInfo error
               (:error-name (ex-data error))))))))

(deftest native-try-is-not-clojure-exception-handling
  (let [failure {:error (value/->ZigError "SugarFailure" :anyerror)}]
    ;; The Clojure special form by itself merely returns this ordinary value.
    (is (= failure (try failure)))
    (is (= :SugarFailure
           (try (k/try failure)
                (catch clojure.lang.ExceptionInfo error
                  (:error-name (ex-data error))))))
    (is (= 42 (k/try {:ok 42})))))
