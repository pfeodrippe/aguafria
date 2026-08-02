(ns aguafria.zig.emitter-test
  (:require [aguafria.keyword :as ak]
            [aguafria.zig.emitter :as emit]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest type-emission-test
  (is (= "i32" (emit/emit-type :i32)))
  (is (= "Point" (emit/emit-type 'Point)))
  (is (= "*const i32" (emit/emit-type [:*const :i32])))
  (is (= "[*:0]const u8" (emit/emit-type [:sentinel-const :u8 0])))
  (is (= "[]const u8" (emit/emit-type [:slice-const :u8])))
  (is (= "[4]f32" (emit/emit-type [:array 4 :f32])))
  (is (= "@Vector(4, f32)" (emit/emit-type [:vector 4 :f32])))
  (is (= "[*c]u8" (emit/emit-type [:c-pointer :u8])))
  (is (= "?!i32"
         (emit/emit-type [:optional [:error-union :i32]])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unknown composite Zig type"
                        (emit/emit-type [:mystery :i32]))))

(deftest expression-emission-test
  (is (= "(a + (b * 2))" (emit/emit-expr '(+ a (* b 2)))))
  (is (= "@max(a, b)"
         (emit/emit-expr (the-ns 'aguafria.zig.emitter-test)
                         '(ak/max a b))))
  (is (= "(~bits)" (emit/emit-expr '(op "~" bits))))
  (is (= "(if ((x < 0)) (-x) else x)"
         (emit/emit-expr '(if (< x 0) (- x) x))))
  (is (= "point.x" (emit/emit-expr '(field point x))))
  (is (= "items[start..end]" (emit/emit-expr '(slice items start end))))
  (is (= "0 .. 10" (emit/emit-expr '(op ".." 0 10))))
  (is (= "?*u8" (emit/emit-expr '(type [:optional [:* :u8]]))))
  (is (= "(Foo{.x = 1}).stat()"
         (emit/emit-expr '((field (init Foo {:x 1}) stat)))))
  (is (= ".{.x = 1, .y = 2}" (emit/emit-expr {:y 2 :x 1})))
  (is (= ".{1, 2, 3}" (emit/emit-expr [1 2 3])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"expects at least two operands"
                        (emit/emit-expr '(== 1)))))

(deftest statement-emission-test
  (is (= "const answer: i32 = 42;"
         (emit/emit-stmt '(const answer :i32 42))))
  (is (= "total += value;" (emit/emit-stmt '(+= total value))))
  (is (= "total /= divisor;"
         (emit/emit-stmt '(assign "/=" total divisor))))
  (is (= "defer cleanup();" (emit/emit-stmt '(defer (cleanup)))))
  (is (= "errdefer cleanup();" (emit/emit-stmt '(errdefer (cleanup)))))
  (is (= "comptime validate();"
         (emit/emit-stmt '(comptime-stmt (validate)))))
  (is (= (str "while ((i < n)) {\n"
              "    total += i;\n"
              "    i += 1;\n"
              "}")
         (emit/emit-stmt '(while (< i n) (+= total i) (+= i 1)))))
  (is (= (str "if ((x < 0)) {\n"
              "    return (-x);\n"
              "} else {\n"
              "    return x;\n"
              "}")
         (emit/emit-stmt '(if (< x 0) (return (- x)) (return x))))))

(deftest struct-schema-test
  (testing "Malli-style field entries preserve per-field properties"
    (is (= [{:name :x
             :type :f32
             :properties {:doc "Horizontal component" :align 4}}
            {:name :y :type :f32 :properties {}}]
           (emit/parse-struct-fields
            [[:x {:doc "Horizontal component" :align 4} :f32]
             [:y :f32]]))))
  (testing "metadata on an entry is retained as field properties"
    (is (= {:unit :meters}
           (:properties
            (first (emit/parse-struct-fields
                    [(with-meta [:distance :f32] {:unit :meters})]))))))
  (testing "the old flattened struct syntax is rejected clearly"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Each az/defstruct field must be a vector"
         (emit/parse-struct-fields '[:x :- :f32 :y :- :f32])))))

(deftest declaration-and-module-test
  (let [source (emit/emit-module
                "demo"
                 [{:kind :fn :name 'add :return :i32 :export? true
                  :args [{:name 'a :type :i32} {:name 'b :type :i32}]
                  :body ['(+ a b)]}
                 {:kind :const :name 'factor :type :i32 :value 3}])]
    (testing "top-level declarations are deterministic and constants precede functions"
      (is (< (.indexOf source "pub const factor")
             (.indexOf source "export fn add"))))
    (is (re-find #"export fn add\(a: i32, b: i32\) callconv\(\.c\) i32" source))
    (is (str/includes? source "return (a + b);"))))

(deftest source-metadata-safety-test
  (let [source (emit/emit-module
                "cider-buffer"
                [{:kind :const :name 'answer :type :i32 :value 42
                  :source {:file "(ns broken\n  (:require [evil]))"
                           :line 9 :column 3}}])]
    (testing "multiline editor buffer contents can never be injected as Zig"
      (is (not (str/includes? source "(:require")))
      (is (str/includes? source "// Clojure source: <repl>:9:3")))))

(deftest unresolved-reference-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Unresolved dotted Zig reference"
       (emit/emit-expr '(out_of_nowhere.member 1)))))

(deftest implicit-return-test
  (is (= "return (a + b);"
         (emit/emit-function-body '((+ a b)) :i32)))
  (is (= (str "if ((x < 0)) {\n"
              "    return (-x);\n"
              "} else {\n"
              "    return x;\n"
              "}")
         (emit/emit-function-body '((if (< x 0) (- x) x)) :i32)))
  (is (= "value;" (emit/emit-function-body '(value) :void)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"must produce a value"
                        (emit/emit-function-body '((while ready (continue))) :i32))))
