(ns aguafria.keywords-test
  (:require [aguafria.keywords :as ak]
            [aguafria.zig :as az]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest generated-builtin-catalog-test
  (let [catalog (ak/builtins)
        public-vars (ns-publics 'aguafria.keywords)]
    (testing "every compiler builtin is a documented, inspectable Var"
      (is (> (count catalog) 100))
      (is (re-matches #"[0-9]+\.[0-9]+\.[0-9]+.*"
                      (:zig-version (ak/catalog-info))))
      (doseq [{:keys [name zig-name]} catalog]
        (let [v (get public-vars (symbol name))
              metadata (meta v)]
          (is (var? v) zig-name)
          (is (= zig-name (:zig/name metadata)) zig-name)
          (is (str/starts-with? (:zig/signature metadata) zig-name) zig-name)
          (is (not (str/blank? (:doc metadata))) zig-name)
          (is (= :builtin (get-in metadata [:aguafria/token :kind])) zig-name))))

    (testing "ordinary readable Zig keywords stay ordinary forms"
      (is (some #(= "if" (:name %)) (ak/language-keywords)))
      (is (nil? (get public-vars 'if))))))

(deftest alias-aware-emission-test
  (testing "the namespace alias resolves generated @builtins"
    (is (= "@intCast(value)" (az/emit-expr '(ak/intCast value))))
    (is (= "@field(value, \"member\")"
           (az/emit-expr '(ak/field value "member"))))
    (is (= "@Vector(4, i32)"
           (az/emit-type '(ak/Vector 4 :i32)))))

  (testing "bare field is still Aguafria's readable field-access form"
    (is (= "(value).member" (az/emit-expr '(field value member)))))

  (testing "reader-hostile operators have named tokens"
    (is (= "(~bits)" (az/emit-expr '(ak/bit-not bits))))
    (is (= "(left ^ right)" (az/emit-expr '(ak/bit-xor left right))))
    (is (= "total /= divisor;"
           (az/emit-stmt '(ak/div-assign total divisor)))))

  (testing "bad builtin arity fails before invoking Zig"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"@intCast expects 1 argument"
                          (az/emit-expr '(ak/intCast one two)))))

  (testing "token Vars cannot accidentally execute as Clojure"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"only be used inside an Aguafria form"
                          (ak/intCast 1)))))
