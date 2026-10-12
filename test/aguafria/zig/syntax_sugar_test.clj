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

(a/defunion BranchPayload {:attrs #{k/enum}} [[:number :u32] [:empty :void]])
(a/defn bump-branch :u32 [[payload [:* BranchPayload]]]
  (k/switch @payload
    :number (a/with-block :result [(k/* n)]
              (k/+= @n 1)
              (k/break :result @n))
    :empty 0))
(a/defn return-from-branch :u32 [[payload BranchPayload]]
  (k/switch payload
    :number (a/with-block :_ [n] (k/return n))
    :empty (k/return 0)))

(deftest switch-block-captures-preserve-storage-and-function-returns
  (with-open [payload (k/var (BranchPayload {:number 41}))]
    (is (= 42 (a/value (bump-branch (k/& payload)))))
    (is (= 42 (a/value (a/field payload :number))))
    (is (= 42 (a/value (return-from-branch payload))))))

(deftest switches-use-only-selector-result-pairs
  (let [emit #(emitter/emit-expr (the-ns 'aguafria.zig.syntax-sugar-test) %)]
    (is (str/includes? (emit '(k/switch tag :little 1 :big 2)) ".little => 1"))
    (is (str/includes? (emit '(k/switch tag [:little :big] 1 2)) ".little, .big => 1"))
    (is (str/includes? (emit '(k/switch tag :type 1 2)) ".type => 1"))
    (is (str/includes? (emit '(k/switch tag :a (a/with-block :_ [x] (k/+ x 1)) 0)) ".a => |x|"))
    (is (str/includes? (emit '(k/switch tag (0 1) (k/inline (a/with-block :_ [i] i)) 2)) "inline 0, 1 => |i| i"))
    (is (str/includes? (emit '(k/switch tag (k/inline (a/with-block :_ [x t] x)))) "inline else => |x, t| x"))
    (doseq [form ['(k/switch tag (case [0] 1) :big 2)
                  '(k/switch tag (case [:little] 1) (a/case-else 2))
                  '(k/switch tag (a/inline-case [0 1] [x] x))
                  '(k/switch tag (a/inline-case-else [x] x))]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Legacy switch clauses"
                            (emit form))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"metadata is not supported"
                          (emit '(k/switch tag :a ^{:capture [x]} x))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not fn"
                          (emit '(k/switch tag :a (fn [x] x)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"binding vector, and body"
                          (emit '(k/switch tag :a (a/with-block :_ [x])))))
    (is (str/includes? (emit '(k/switch tag ((:a Item) (:b Item)) 1 0)) "Item.a, Item.b => 1"))
    (is (str/includes? (emit '(k/switch tag :a (a/with-block :blk [(k/* x)] (k/break :blk @x)) 0))
                       ".a => |*x| blk:"))
    (is (str/includes? (emit '(k/switch [dispatch tag] :a (k/continue dispatch :b) :b 42))
                       "dispatch: switch (tag)"))
    (is (not-any? (emitter/syntax-operators) '[case inline-case case-else inline-case-else]))
    (doseq [name '[case-else inline-case switch switch-stmt labeled-switch labeled-switch-stmt]]
      (is (nil? (ns-resolve 'aguafria.zig name))))
    (is (= :ordinary (case :a :a :ordinary :other)))))

(deftest enum-keywords-are-values-without-changing-types-or-field-names
  (is (= ".little" (emitter/emit-expr :little)))
  (is (= ".some_tag" (emitter/emit-expr :some-tag)))
  (is (= "u32" (emitter/emit-expr :u32)))
  (is (= "u32" (emitter/emit-type :u32)))
  (is (= "Point" (emitter/emit-type :Point)))
  (is (= ".little" (emitter/emit-expr :little)))
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
