(ns aguafria.zig.jvm-private-scope-test
  (:require [aguafria.keyword :as k]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as az]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def reflected-handle
  [:optional
   [:* '(aguafria.zig/field
         (aguafria.zig/field
          (aguafria.keyword/typeInfo
           (aguafria.zig/field
            (aguafria.zig/field
             (aguafria.keyword/typeInfo
              (aguafria.zig/unwrap
              (aguafria.zig/index
                 (aguafria.zig/field
                  (aguafria.zig/field
                   (aguafria.keyword/typeInfo
                    (aguafria.keyword/TypeOf aguafria.zig.jvm-private-scope-fixture/parameter-owner))
                   :fn)
                  :param_types)
                 0)))
             :optional)
            :child))
          :pointer)
         :child)]])

(deftest nested-private-reflection-and-callback-address-are-prepared-in-owner-scope
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.jvm-private-scope-fixture))
  (let [callback @(resolve 'aguafria.zig.jvm-private-scope-fixture/callback)
        source-before (runtime/source 'aguafria.zig.jvm-private-scope-fixture)
        fail! (fn [& _] (throw (ex-info "Native invocation during preparation" {})))
        reports
        (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
          (binding [runtime/*compile-only?* true]
            [(jvm/precompile-coercion! reflected-handle)
             (jvm/precompile-conversion! [:optional [:* :i32]] reflected-handle)
             (jvm/precompile-construction! reflected-handle {:literal nil :type :null})
             (jvm/precompile-call! {:function 'aguafria.std.mem/zeroes
                                    :args [{:comptime-type [:array 2 reflected-handle]}]})
             ;; This fixture's parameter declares i32. Zig's value transport
             ;; represents the returned array structurally, rather than by the
             ;; equivalent reflection expression used to request zeroes.
             (jvm/precompile-coercion! [:array 2 [:optional [:* :i32]]])
             (jvm/precompile-storage!
              {:kind :address
               :reference 'aguafria.zig.jvm-private-scope-fixture/callback
               :receiver '(aguafria.keyword/TypeOf aguafria.zig.jvm-private-scope-fixture/callback)
               :address [:*const '(aguafria.keyword/TypeOf aguafria.zig.jvm-private-scope-fixture/callback)]})]))
        commands (atom [])
        original shell/sh]
    (is (every? #(= :prepared (:status %)) reports) (pr-str reports))
    (is (str/includes? source-before "fn parameter_owner("))
    (is (str/includes? source-before "fn callback("))
    (is (not (str/includes? (runtime/source 'aguafria.zig.jvm-private-scope-fixture)
                            "pub fn callback(")))
    (with-redefs [shell/sh (fn [& arguments]
                             (when (= "build-lib" (second arguments))
                               (swap! commands conj (vec arguments)))
                             (apply original arguments))]
      (with-open [handle (k/as nil reflected-handle)
                  array (mem/zeroes [:array 2 reflected-handle])]
        (is (nil? (az/value handle)))
        (is (= [nil nil] (az/value array))))
      (with-open [native (k/& callback)]
        (let [pointer (az/value native)]
          (is (value/zig-pointer? pointer))
          (is (pos? (value/pointer-address pointer))))))
    ;; The callback's address and typed constructors must use the same planner
    ;; during preparation and invocation. The assertion below detects a miss.
    (is (empty? @commands) (pr-str @commands))))

(deftest conflicting-private-owner-scopes-fail-before-compilation
  (let [left (create-ns 'aguafria.zig.private-scope-left)
        right (create-ns 'aguafria.zig.private-scope-right)
        context (create-ns 'aguafria.jvm.private-scope-conflict)]
    (doseq [owner [left right]]
      (alter-meta! (intern owner 'hidden nil) assoc :aguafria/declaration
                   {:public? false :module (str (ns-name owner))}))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"private declarations from different Zig modules"
         (#'aguafria.zig.jvm/private-adapter-context
          context ['aguafria.zig.private-scope-left/hidden
                   'aguafria.zig.private-scope-right/hidden])))))

(deftest compound-assignment-preserves-nested-private-type-scope
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.jvm-private-scope-fixture))
  (let [scalar (last (second reflected-handle))
        fail! (fn [& _] (throw (ex-info "Native invocation during preparation" {})))
        commands (atom [])
        original shell/sh]
    (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
      (binding [runtime/*compile-only?* true]
        (is (= :prepared (:status (jvm/precompile-literal-coercion! scalar 7))))
        (is (= :prepared
               (:status (jvm/precompile-assignment!
                         {:function 'aguafria.keyword/+= :operation "+="
                          :target scalar :operand {:literal 1 :type :comptime_int}}))))))
    (with-redefs [shell/sh (fn [& arguments]
                             (when (= "build-lib" (second arguments))
                               (swap! commands conj (vec arguments)))
                             (apply original arguments))]
      (with-open [slot (k/var (k/as 7 scalar))]
        (k/+= slot 1)
        (is (= 8 (az/value slot)))))
    (is (empty? @commands) (pr-str @commands))))
