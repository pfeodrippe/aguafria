(ns aguafria.zig.emitter-test
  (:require [aguafria.zig.emitter :as emit]
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
  (is (= "@max(a, b)" (emit/emit-expr '(builtin max a b))))
  (is (= "(~bits)" (emit/emit-expr '(op "~" bits))))
  (is (= "(if ((x < 0)) (-x) else x)"
         (emit/emit-expr '(if (< x 0) (- x) x))))
  (is (= "(point).x" (emit/emit-expr '(field point x))))
  (is (= "(items)[start..end]" (emit/emit-expr '(slice items start end))))
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
