(ns aguafria.keyword-test
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]
            [aguafria.zig.emitter :as emitter]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest generated-keyword-catalog-test
  (let [catalog (ak/entries)
        public-vars (ns-publics 'aguafria.keyword)]
    (testing "every compiler @ function is a documented, inspectable Var"
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
          (is (= :call (get-in metadata [:aguafria/token :kind])) zig-name))))

    (testing "all Zig keywords are Vars while Clojure-native spellings stay usable"
      (is (some #(= "if" (:name %)) (ak/language-keywords)))
      (is (var? (get public-vars 'if)))
      (is (= "if" (get-in (meta (get public-vars 'if))
                           [:aguafria/token :zig-token])))
      (is (var? (get public-vars 'const)))
      (is (var? (get public-vars 'var)))
      (is (= "var" (get-in (meta (get public-vars 'var))
                            [:aguafria/token :zig-token])))
      (is (= :keyword (get-in (meta (get public-vars 'const))
                              [:aguafria/token :kind])))
      (is (some #(= "undefined" (:name %)) (ak/primitives)))
      (is (var? (get public-vars 'undefined)))
      (is (= :primitive (get-in (meta (get public-vars 'undefined))
                                [:aguafria/token :kind])))
      (is (nil? (get public-vars 'builtins))))))

(deftest alias-aware-emission-test
  (testing "the namespace alias resolves generated @ functions"
    (is (= "@intCast(value)"
           (emitter/emit-expr (the-ns 'aguafria.keyword-test)
                              '(ak/intCast value))))
    (is (= "@field(value, \"member\")"
           (az/emit-expr '(ak/field value "member"))))
    (is (= "@Vector(4, i32)"
           (az/emit-type '(ak/Vector 4 :i32)))))

  (testing "bare field is still Aguafria's readable field-access form"
    (is (= "value.member" (az/emit-expr '(field value :member)))))

  (testing "primitive values are qualified atom Vars"
    (is (= "undefined" (az/emit-expr 'ak/undefined)))
    (is (nil? (:aguafria/zig-reference
               (meta (emitter/qualify-form
                      (the-ns 'aguafria.keyword-test) 'ak/undefined)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"use `ak/undefined`"
                          (az/emit-expr 'undefined))))

  (testing "reader-hostile operators have named tokens"
    (is (= "(~bits)" (az/emit-expr '(ak/bit-not bits))))
    (is (= "(left ^ right)" (az/emit-expr '(ak/bit-xor left right))))
    (is (= "total /= divisor;"
           (az/emit-stmt '(ak/div-assign total divisor)))))

  (testing "readable Zig-only syntax also comes from documented Vars"
    (is (= "const value: u32 = 1;"
           (az/emit-stmt '(ak/const value :u32 1))))
    (is (= "total += value;" (az/emit-stmt '(ak/+= total value))))
    (is (= "var value: u32 = 1;"
           (az/emit-stmt '(ak/var value :u32 1))))
    (is (var? (ns-resolve 'aguafria.zig 'while-loop)))
    (is (not (str/blank? (:doc (meta (ns-resolve 'aguafria.zig 'while-loop)))))))

  (testing "bad generated-keyword arity fails before invoking Zig"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"@intCast expects 1 argument"
                          (az/emit-expr '(ak/intCast one two)))))

  (testing "token Vars cannot accidentally execute as Clojure"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"only be used inside an Aguafria form"
                          (ak/intCast 1)))))
