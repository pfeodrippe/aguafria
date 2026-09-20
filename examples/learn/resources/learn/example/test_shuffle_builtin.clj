(ns learn.example.test-shuffle-builtin
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest vector-shuffle-test
  (let [letters (az/array-init [\o \l \h \e \r \z \w] [:vector 7 :u8])
        endings (az/array-init [\w \d \! \x] [:vector 4 :u8])
        hello-mask (az/array-init [2 3 1 1 0] [:vector 5 :i32])
        hello (ak/shuffle (az/type :u8) letters ak/undefined hello-mask)
        ;; Negative mask entries select the complemented index in endings.
        world-mask (az/array-init [-1 0 4 1 -2 -3] [:vector 6 :i32])
        world (ak/shuffle (az/type :u8) letters endings world-mask)]
    (try (testing/expectEqualStrings
          "hello" (& (ak/as hello (az/type [:array 5 :u8])))))
    (try (testing/expectEqualStrings
          "world!" (& (ak/as world (az/type [:array 6 :u8])))))))

(comment
  (vector-shuffle-test))
