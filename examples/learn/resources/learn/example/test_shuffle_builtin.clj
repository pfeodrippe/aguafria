(ns learn.example.test-shuffle-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest vector-shuffle-test
  (let [letters (az/init [\o \l \h \e \r \z \w] [:vector 7 :u8])
        endings (az/init [\w \d \! \x] [:vector 4 :u8])
        hello-mask (az/init [2 3 1 1 0] [:vector 5 :i32])
        hello (k/shuffle (az/type :u8) letters k/undefined hello-mask)
        ;; Negative mask entries select the complemented index in endings.
        world-mask (az/init [-1 0 4 1 -2 -3] [:vector 6 :i32])
        world (k/shuffle (az/type :u8) letters endings world-mask)]
    (try (testing/expectEqualStrings
          "hello" (k/& (k/as hello (az/type [:array 5 :u8])))))
    (try (testing/expectEqualStrings
          "world!" (k/& (k/as world (az/type [:array 6 :u8])))))))

(comment
  (vector-shuffle-test))
