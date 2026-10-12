(ns aguafria.zig.comptime-string-test
  (:require [aguafria.zig :as a]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.value :as value]
            [clojure.test :refer [deftest is]]))

(a/defconst format-text "old")
(a/defconst typed-text [:slice-const :u8] "typed")
(a/defvar mutable-text "mutable")

(a/defn literal-length :usize
  [[text {:zig/prefix "comptime"} [:slice-const :u8]]]
  (:len text))

(deftest inferred-string-provenance-is-retained-only-where-exact
  (is (= "old" (value/declared-comptime-literal format-text)))
  ;; Explicit types and mutable storage must not be replaced with a pointer
  ;; literal: doing so would erase their type/qualifiers or read mutable state.
  (is (nil? (value/declared-comptime-literal typed-text)))
  (is (nil? (value/declared-comptime-literal mutable-text)))
  (is (nil? (value/declared-comptime-literal "host string"))))

(deftest comptime-use-still-validates-the-native-declaration
  (let [unavailable (value/native-value
                     {:kind :const :comptime-literal "not validated"}
                     #(throw (ex-info "invalid native declaration" {:validation true})))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid native declaration"
                          (#'jvm/call-inputs
                           [{:type [:slice-const :u8] :properties {:zig/prefix "comptime"}}]
                           [unavailable])))))

(deftest old-handles-do-not-use-reloaded-string-contents
  (let [old format-text
        context (the-ns 'aguafria.zig.comptime-string-test)]
    (value/realize! old)
    (try
      (binding [*ns* context] (eval '(a/defconst format-text "newer")))
      (is (= "old" (value/declared-comptime-literal old)))
      (is (= "newer" (value/declared-comptime-literal format-text)))
      (with-open [old-result (literal-length old)
                  new-result (literal-length format-text)]
        (is (= 3 (a/value old-result)))
        (is (= 5 (a/value new-result))))
      (finally
        (binding [*ns* context] (eval '(a/defconst format-text "old")))))))
