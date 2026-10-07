(ns aguafria.zig.jvm-scoped-destination-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defunion Ranked {:attrs #{k/enum}}
  [[:first :void]
   [:second :f32]
   (a/fn rank :usize [[self Ranked]]
     (switch self (case [:.first] 1) (case [:.second] 2)))])

(a/defunion NumericValue {:attrs #{k/enum}}
  [[:integer :u32] [:float :f32]])

(a/defn convert-number :u32 [[value NumericValue]]
  (switch value
    (a/inline-case-else [number tag]
      (if (k/== tag :.float) (k/intFromFloat number) number))))

(a/deftest destination-results
  (let [first (k/as :.first Ranked)
        second (Ranked {:second 3.5})]
    (try (testing/expectEqual 1 ((:rank first))))
    (try (testing/expectEqual 2 ((:rank second)))))
  (let [integer (NumericValue {:integer 19})
        floating (NumericValue {:float 42})]
    (try (testing/expectEqual 19 (convert-number integer)))
    (try (testing/expectEqual 42 (convert-number floating)))))

(a/deftest peer-results
  (let [source (k/var 0 [:error-union [:error-set [:A :B :C]] :u32])]
    (k/= :_ (k/& source))
    (let [selected (a/if-capture {:payload [value] :error [err]} source
                     (k/+ value 3)
                     (k/switch err
                       (case [(a/error-value :A)] 0)
                       (case [(a/error-value :B)] 1)
                       (case [(a/error-value :C)] nil)))
          caught (a/catch-capture [err] source
                   (k/switch err
                     (case [(a/error-value :A)] 0)
                     (case [(a/error-value :B)] 1)
                     (case [(a/error-value :C)] nil)))]
      (try (testing/expectEqual (k/as 3 [:optional :u32]) selected))
      (try (testing/expectEqual (k/as 0 [:optional :u32]) caught)))
    (k/= source (a/error-value :A))
    (let [selected (a/if-capture {:payload [value] :error [err]} source
                     (k/+ value 3)
                     (k/switch err
                       (case [(a/error-value :A)] 0)
                       (case [(a/error-value :B)] 1)
                       (case [(a/error-value :C)] nil)))]
      (try (testing/expectEqual (k/as 0 [:optional :u32]) selected)))
    (k/= source (a/error-value :B))
    (let [caught (a/catch-capture [err] source
                   (k/switch err
                     (case [(a/error-value :A)] 0)
                     (case [(a/error-value :B)] 1)
                     (case [(a/error-value :C)] nil)))]
      (try (testing/expectEqual (k/as 1 [:optional :u32]) caught)))
    (k/= source (a/error-value :C))
    (let [selected (a/if-capture {:payload [value] :error [err]} source
                     (k/+ value 3)
                     (k/switch err
                       (case [(a/error-value :A)] 0)
                       (case [(a/error-value :B)] 1)
                       (case [(a/error-value :C)] nil)))]
      (try (testing/expectEqual (k/as nil [:optional :u32]) selected)))))
