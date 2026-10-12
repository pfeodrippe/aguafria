(ns aguafria.zig.string-construction-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
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

(deftest string-storage-cannot-be-made-writable-by-dropping-const
  (with-open [slice (k/as "hello" [:slice-const :u8])]
    (is (thrown? Exception (k/as slice [:slice :u8])))))
