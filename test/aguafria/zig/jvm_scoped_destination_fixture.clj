(ns aguafria.zig.jvm-scoped-destination-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defunion Ranked {:attrs #{enum}}
  [[:first :void]
   [:second :f32]
   (a/fn rank :usize [[self Ranked]]
     (switch self :first 1 :second 2))])

(a/defunion NumericValue {:attrs #{enum}}
  [[:integer :u32] [:float :f32]])

(a/defn convert-number :u32 [[value NumericValue]]
  (switch value

    (inline (a/with-block :_ [number tag] (if (== tag :float) (intFromFloat number) number)))))

(a/deftest destination-results
  (let [first (as :first Ranked)
        second (Ranked {:second 3.5})]
    (try (testing/expectEqual 1 ((:rank first))))
    (try (testing/expectEqual 2 ((:rank second)))))
  (let [integer (NumericValue {:integer 19})
        floating (NumericValue {:float 42})]
    (try (testing/expectEqual 19 (convert-number integer)))
    (try (testing/expectEqual 42 (convert-number floating)))))

(a/deftest peer-results
  (let [source (k/var 0 [:error-union [:error-set [:A :B :C]] :u32])]
    (= :_ (k/& source))
    (let [selected (a/if-capture {:payload [value] :error [err]} source
                                 (+ value 3)
                                 (switch err
                                   (a/error-value :A) 0
                                   (a/error-value :B) 1
                                   (a/error-value :C) nil))
          caught (a/catch-capture [err] source
                                  (switch err
                                    (a/error-value :A) 0
                                    (a/error-value :B) 1
                                    (a/error-value :C) nil))]
      (try (testing/expectEqual (as 3 [:optional :u32]) selected))
      (try (testing/expectEqual (as 0 [:optional :u32]) caught)))
    (= source (a/error-value :A))
    (let [selected (a/if-capture {:payload [value] :error [err]} source
                                 (+ value 3)
                                 (switch err
                                   (a/error-value :A) 0
                                   (a/error-value :B) 1
                                   (a/error-value :C) nil))]
      (try (testing/expectEqual (as 0 [:optional :u32]) selected)))
    (= source (a/error-value :B))
    (let [caught (a/catch-capture [err] source
                                  (switch err
                                    (a/error-value :A) 0
                                    (a/error-value :B) 1
                                    (a/error-value :C) nil))]
      (try (testing/expectEqual (as 1 [:optional :u32]) caught)))
    (= source (a/error-value :C))
    (let [selected (a/if-capture {:payload [value] :error [err]} source
                                 (+ value 3)
                                 (switch err
                                   (a/error-value :A) 0
                                   (a/error-value :B) 1
                                   (a/error-value :C) nil))]
      (try (testing/expectEqual (as nil [:optional :u32]) selected)))))
