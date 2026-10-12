(ns aguafria.zig.string-construction-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.value :as value]
            [clojure.test :refer [deftest is]]))

(deftest literal-construction-keeps-sentinels-but-cannot-invent-them-for-slices
  (with-open [sentinel-slice (k/as "hello" [:* {:size :slice :const? true :sentinel 0} :u8])
              pointer (k/as sentinel-slice [:sentinel-const :u8 0])
              ordinary-slice (k/as "hello" [:slice-const :u8])]
    (is (k/== (a/type [:* {:size :slice :const? true :sentinel 0} :u8])
              (k/TypeOf sentinel-slice)))
    (is (= 111 (a/value (a/get pointer 4))))
    (is (= 0 (a/value (a/get pointer 5))))
    (is (thrown? Exception
                 (k/as ordinary-slice [:* {:size :slice :const? true :sentinel 0} :u8])))))

(deftest aggregate-string-pointers-have-owned-utf8-sentinel-storage
  (with-open [array (a/init ["hé" ""] [:array 2 [:sentinel-const :u8 0]])]
    (let [text (a/get array 0)
          empty-text (a/get array 1)]
      (is (= [104 195 169 0] (mapv #(a/value (a/get text %)) (range 4))))
      (is (= 0 (a/value (a/get empty-text 0))))))
  (is (thrown? clojure.lang.ExceptionInfo
               (a/init ["hello"] [:array 1 [:sentinel :u8 0]])))
  (is (thrown? clojure.lang.ExceptionInfo
               (a/init ["hello"] [:array 1 [:sentinel-const :u8 255]]))))

(a/defn echo-c-string [:sentinel-const :u8 0]
  [[_ {:zig/prefix "comptime"} :type] [text [:sentinel-const :u8 0]]]
  text)

(deftest fixed-c-string-arguments-own-their-backing-through-returned-views
  (doseq [[text bytes] [["hé" [104 195 169 0]] ["" [0]]]]
    (with-open [result (echo-c-string :u8 text)]
      (System/gc)
      (is (= bytes (mapv #(a/value (a/get result %)) (range (count bytes)))))))
  (with-open [slice (k/as "hello" [:slice-const :u8])]
    (is (thrown? Exception (echo-c-string :u8 slice)))))

(deftest fixed-c-string-planning-does-not-allocate-application-storage
  (with-redefs [value/owned-c-string-pointer
                (fn [_] (throw (ex-info "preparation must not allocate" {})))]
    (let [plan (#'jvm/prepared-call-inputs
                [{:literal "hé"}] [{:name 'text :type [:sentinel-const :u8 0]}])]
      (is (= [:sentinel-const :u8 0] (-> plan :parameters first :type))))))

(deftest string-storage-cannot-be-made-writable-by-dropping-const
  (with-open [slice (k/as "hello" [:slice-const :u8])]
    (is (thrown? Exception (k/as slice [:slice :u8])))))
