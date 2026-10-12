(ns aguafria.zig.primitive-type-vars-test
  (:require [aguafria.keyword :as k]
            [aguafria.std :as std]
            [aguafria.zig :as a]
            [aguafria.zig.emitter :as emitter]
            [clojure.test :refer [deftest is]]))

(a/defn add k/u32 [[x k/u32] [y k/u32]] (k/+ x y))
(a/defn add-keywords :u32 [[x :u32] [y :u32]] (k/+ x y))
(a/defn type-identity k/type [[T {:attrs #{k/comptime}} k/type]] T)
(a/defn succeeds [:! k/void] [] (k/return))
(a/defstruct Pair [[:x k/u32] [:y k/u32]])
(a/defstruct PairKeywords [[:x :u32] [:y :u32]])
(a/defunion Payload {:attrs #{k/enum}} [[:number k/u32] [:empty k/void]])
(a/defunion PayloadKeywords {:attrs #{k/enum}} [[:number :u32] [:empty :void]])
(a/defenum Small {:type k/u8} [:zero :one])
(a/defenum SmallKeywords {:type :u8} [:zero :one])

(deftest qualified-primitives-work-as-types-and-constructors
  (is (= 42 (a/value (add 20 22)) (a/value (add-keywords 20 22))))
  (is (= (select-keys (:aguafria/declaration (meta #'add)) [:return :args])
         (select-keys (:aguafria/declaration (meta #'add-keywords)) [:return :args])))
  (is (= 7 (a/value (k/u32 7))))
  (with-open [v (k/var 9 k/u32)] (is (= 9 @v)))
  (is (= "u32" (emitter/emit-type (the-ns 'aguafria.zig.primitive-type-vars-test) 'k/u32)))
  (is (= "!void" (emitter/emit-type (the-ns 'aguafria.zig.primitive-type-vars-test) '[:! k/void])))
  (is (true? (k/== (type-identity k/u32) k/u32)))
  (is (true? (k/== (type-identity k/void) k/void)))
  (is (true? (k/== (a/type [:array 2 k/u8]) (a/type [:array 2 :u8]))))
  (is (some? (std/ArrayList k/u21)))
  (let [expression (with-meta '(k/TypeOf (k/u32 1)) {:proof-owner :retained})
        qualified (emitter/qualify-type (the-ns 'aguafria.zig.primitive-type-vars-test)
                                        [:* expression])]
    (is (= :retained (:proof-owner (meta (second qualified)))))
    (is (= '(aguafria.keyword/u32 1) (second (second qualified)))))
  (is (= [:* {:align 8} :u32]
         (emitter/qualify-type (the-ns 'aguafria.zig.primitive-type-vars-test)
                               '[:* {:align 8} k/u32])))
  (is (= 3 (a/value (a/field (Pair {:x 3 :y 4}) :x))))
  (is (nil? (k/try (succeeds)))))

(deftest keyword-and-qualified-container-types-have-the-same-layout-not-identity
  (doseq [[qualified keywords] [[#'Pair #'PairKeywords] [#'Payload #'PayloadKeywords]]]
    (is (= (:fields (:aguafria/declaration (meta qualified)))
           (:fields (:aguafria/declaration (meta keywords))))))
  (is (= 3 (a/value (a/field (PairKeywords {:x 3 :y 4}) :x))))
  (is (= 42 (a/value (a/field (Payload {:number 42}) :number))
         (a/value (a/field (PayloadKeywords {:number 42}) :number))))
  (is (false? (k/== Pair PairKeywords)))
  (is (false? (k/== Payload PayloadKeywords)))
  (is (= 1 (a/value (k/backingInt (:one Small)))
         (a/value (k/backingInt (:one SmallKeywords)))))
  (is (true? (k/== k/u8 (k/TypeOf (k/backingInt (:one Small))))))
  (is (true? (k/== k/u8 (k/TypeOf (k/backingInt (:one SmallKeywords)))))))
