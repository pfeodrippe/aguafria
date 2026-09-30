(ns aguafria.zig.peer-call
  "Find Zig functions that convert their arguments to a common type before
  using them. Those calls can reuse a JVM handler when only argument values change."
  (:require [aguafria.zig.convert :as convert]))

(defn- token-text [parsed index]
  (when-let [[_ offset length] (get (:tokens parsed) index)]
    (String. ^bytes (:source-bytes parsed) (int offset) (int length) "UTF-8")))

(defn- identifier [parsed index]
  (let [node (get (:nodes parsed) index)]
    (when (= :identifier (:tag node))
      (token-text parsed (:main-token node)))))

(defn- function-named [parsed name]
  ;; A nested function with the same name may refer to a different helper.
  (some #(when (= name (token-text parsed (nth % 3))) %)
        (keep (:function-index parsed) (:root-decls parsed))))

(defn- peer-wrapper? [parsed name]
  (when-let [[_ _ body _ return-node _ _ _ _ _ _ _ _ params]
             (function-named parsed name)]
    (let [names (mapv #(token-text parsed (first %)) params)
          statements (second (get (:block-index parsed) body))
          [binding return] statements
          [_ _ _ _ _ _ mut _ align addrspace section init]
          (get (:var-index parsed) binding)
          type-name (when mut (token-text parsed (inc mut)))
          builtin (get (:nodes parsed) init)
          type-args (second (get (:builtin-index parsed) init))
          return-expr (get (:nodes parsed) return)
          [_ callee call-args] (get (:call-index parsed) (:a return-expr))
          helper (function-named parsed (identifier parsed callee))
          [_ _ helper-body _ helper-return _ _ qualifier _ _ _ _ _ helper-params] helper
          [type-param & value-params] helper-params
          helper-type (token-text parsed (first type-param))]
      (and
       (seq params)
       (every? (fn [[_ type qualifier anytype]]
                 (and (nil? type) (nil? qualifier) (some? anytype))) params)
       (= "void" (identifier parsed return-node))
       (= 2 (count statements))
       (= "const" (token-text parsed mut))
       (not-any? some? [align addrspace section])
       (= "@TypeOf" (token-text parsed (:main-token builtin)))
       (= names (mapv #(identifier parsed %) type-args))
       (= :return (:tag return-expr))
       (= (into [type-name] names) (mapv #(identifier parsed %) call-args))
       ;; The helper must receive ordinary typed arguments, not comptime
       ;; values. An inline helper could still depend on a literal's value.
       helper-body (nil? qualifier)
       (= "void" (identifier parsed helper-return))
       (= "comptime" (token-text parsed (nth type-param 2 nil)))
       (= "type" (identifier parsed (second type-param)))
       (= (count params) (count value-params))
       (every? (fn [[_ type qualifier anytype]]
                 (and (= helper-type (identifier parsed type))
                      (nil? qualifier) (nil? anytype))) value-params)))))

(def peer-wrapper-source?
  "Check the Zig source for a function that converts all arguments to a common
  type, then passes them to a normal typed helper. Cache by source content."
  (memoize (fn [source name]
             (boolean (peer-wrapper? (convert/parse-source source) name)))))
