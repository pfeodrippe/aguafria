(ns aguafria.zig.let-destructuring-test
  (:require aguafria.keyword
            [aguafria.zig.emitter :as emit]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.util.regex Pattern]))

(defn- occurrences [source text]
  (count (re-seq (re-pattern (Pattern/quote text)) source)))

(deftest fixed-vector-let-declares-all-leaves
  (let [source (emit/emit-stmt '(let [[left right] (read-pair)]
                                (consume left right)) 0)]
    (is (str/includes? source "const left, const right = read_pair();"))
    (is (str/includes? source "consume(left, right);"))
    (is (= 1 (occurrences source "read_pair()")))
    (is (str/starts-with? source "{"))
    (is (str/ends-with? source "}"))))

(deftest destructured-leaves-retain-types-mutability-and-discards
  (let [source (emit/emit-stmt
                '(let [[^{:zig/type :u8} small
                        ^{:var :i32} mutable
                        ^:var inferred
                        ^{:tag :u16} tagged
                        _] (read-values)]
                   (set! mutable 7)
                   (set! inferred 8)
                   (consume small mutable inferred tagged)) 0)]
    (is (str/includes? source
                       "const small: u8, var mutable: i32, var inferred, const tagged: u16, _ = read_values();"))
    (is (str/includes? source "mutable = 7;"))
    (is (str/includes? source "inferred = 8;"))
    (is (not (str/includes? source "const _")))
    (is (not (str/includes? source "var _")))
    (is (= 1 (occurrences source "read_values()")))))

(deftest singleton-destructuring-indexes-one-initializer
  (doseq [[form declaration]
          [['(let [[only] (read-single)] (consume only)) "const only"]
           ['(let [[^{:var :i32} only] (read-single)] (set! only 8)) "var only: i32"]
           ['(let [[_] (read-single)]) "_"]]]
    (let [source (emit/emit-stmt form 0)]
      (is (str/includes? source (str declaration " = (read_single())[0];")))
      (is (= 1 (occurrences source "read_single()"))))))

(deftest initializer-is-evaluated-once-even-with-discarded-leaves
  (doseq [pattern '[[left right] [_ right] [left _] [_ _] [only] [_]]]
    (let [source (emit/emit-stmt
                  (list 'let [pattern '(next-values)] '(consume)) 0)]
      (is (= 1 (occurrences source "next_values()")) (pr-str pattern)))))

(deftest vector-set-assigns-existing-targets
  (is (= "left, right = read_pair();"
         (emit/emit-stmt '(set! [left right] (read-pair)) 0)))
  (is (= "left, _ = read_pair();"
         (emit/emit-stmt '(set! [left _] (read-pair)) 0)))
  (is (= "left = (read_single())[0];"
         (emit/emit-stmt '(set! [left] (read-single)) 0)))
  (is (= "_ = (read_single())[0];"
         (emit/emit-stmt '(set! [_] (read-single)) 0)))
  (let [source (emit/emit-stmt
                '(set! [(field point :x) (index values 1) (deref pointer)]
                       (read-triple)) 0)]
    (is (= "point.x, values[1], pointer.* = read_triple();" source))
    (is (= 1 (occurrences source "read_triple()")))
    (is (not (str/includes? source "const ")))
    (is (not (str/includes? source "var ")))))

(deftest destructured-let-can-be-an-expression
  (let [source (emit/emit-expr '(let [[left right] (read-pair)] (+ left right)))
        label (second (re-find #"^(aguafria_let_[0-9a-f]+):" source))]
    (is (some? label))
    (is (str/includes? source "const left, const right = read_pair();"))
    (is (str/includes? source (str "break :" label " (left + right);")))
    (is (= 1 (occurrences source "read_pair()")))))

(deftest destructured-let-supports-implicit-return-and-following-bindings
  (let [source (emit/emit-function-body
                '((let [[left right] (read-pair)
                        total (+ left right)]
                    total)) :i32)]
    (is (str/includes? source "const left, const right = read_pair();"))
    (is (str/includes? source "const total = (left + right);"))
    (is (str/includes? source "return total;"))
    (is (not (str/includes? source "return const")))))

(deftest nested-let-expression-scopes-keep-separate-break-labels
  (let [source (emit/emit-expr
                '(let [[left right] (read-pair)]
                   (+ left (let [[third fourth] (read-other-pair)]
                             (+ third fourth)))))
        labels (map second (re-seq #"(aguafria_let_[0-9a-f]+): \{" source))]
    (is (= 2 (count labels)))
    (is (= 2 (count (set labels))))
    (doseq [label labels]
      (is (= 1 (occurrences source (str "break :" label " "))))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"result expression"
                       (emit/emit-expr '(let [[left right] (read-pair)])))))

(deftest unsupported-let-patterns-fail-before-zig
  (doseq [pattern '[[[left right] third] [left & remaining] [] [left :as pair]
                   [left {:right right}]]]
    (testing (pr-str pattern)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fixed vector of names"
                           (emit/emit-stmt (list 'let [pattern '(read-values)]) 0)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fixed vector of names"
                           (emit/emit-expr (list 'let [pattern '(read-values)] 1)))))))

(deftest unsupported-vector-assignment-patterns-fail-before-zig
  (doseq [pattern '[[[left right] third] [left & remaining] []]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (emit/emit-stmt (list 'set! pattern '(read-values)) 0))
        (pr-str pattern))))

(deftest scalar-local-metadata-is-preserved
  (let [source (emit/emit-stmt
                '(let [^{:var :i32 :zig/prefix "comptime"} count 0
                       ^{:var [:array 4 :u8] :zig/align 16} bytes aguafria.keyword/undefined]
                   (set! count 1)
                   (consume (& bytes))) 0)]
    (is (str/includes? source "comptime var count: i32 = 0;"))
    (is (str/includes? source "var bytes: [4]u8 align(16) = undefined;"))))

(deftest destructured-local-metadata-is-preserved
  (let [source (emit/emit-stmt
                '(let [[^{:var :i32 :zig/prefix "comptime"} count
                        ^{:zig/type :i32 :zig/align 16} aligned]
                       (read-pair)]
                   (set! count 1)
                   (consume aligned)) 0)]
    (is (str/includes? source
                       "comptime var count: i32, const aligned: i32 align(16) = read_pair();")))
  (let [source (emit/emit-stmt
                '(let [[^{:var :i32 :zig/prefix "comptime" :zig/align 8} only]
                       (read-single)]
                   (set! only 1)) 0)]
    (is (str/includes? source
                       "comptime var only: i32 align(8) = (read_single())[0];"))))
