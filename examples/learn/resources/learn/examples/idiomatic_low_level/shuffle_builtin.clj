(ns learn.examples.idiomatic-low-level.shuffle-builtin
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest vector-shuffle-test
  (let [letters (az/array-init [:vector 7 :u8] [\o \l \h \e \r \z \w])
        endings (az/array-init [:vector 4 :u8] [\w \d \! \x])
        hello-mask (az/array-init [:vector 5 :i32] [2 3 1 1 0])
        hello (ak/shuffle (az/type :u8) letters ak/undefined hello-mask)
        ;; Negative mask entries select the complemented index in endings.
        world-mask (az/array-init [:vector 6 :i32] [-1 0 4 1 -2 -3])
        world (ak/shuffle (az/type :u8) letters endings world-mask)]
    (try (testing/expectEqualStrings
           "hello" (& (ak/as (az/type [:array 5 :u8]) hello))))
    (try (testing/expectEqualStrings
           "world!" (& (ak/as (az/type [:array 6 :u8]) world))))))
