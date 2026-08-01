(ns aguafria.zig.emitter
  "A deliberately small data representation of Zig syntax.

  Nothing in this namespace evaluates or macroexpands a Zig form as Clojure.
  It only validates data and renders deterministic Zig source."
  (:require [aguafria.keyword :as keyword]
            [clojure.string :as str]))

(defn- fail!
  [message form & [data]]
  (throw (ex-info message (merge {:form form} data))))

(defn identifier
  "Render a Clojure name as a legal, conventional Zig identifier.

  Hyphens become underscores and namespace separators become double
  underscores. Dots are retained so names such as `std.math.sqrt` can name a
  Zig declaration directly."
  [x]
  (let [s (cond
            (symbol? x) (if-let [n (namespace x)]
                          (str n "__" (name x))
                          (name x))
            (keyword? x) (name x)
            (string? x) x
            :else (fail! "Expected a Zig identifier" x))]
    (-> s
        (str/replace "-" "_")
        (str/replace "/" "__"))))

(declare emit-expr emit-stmt emit-statements emit-type)

(def ^:dynamic *keyword-context*
  "Namespace used to resolve aliases such as `ak/intCast` during emission."
  nil)

(defn- current-keyword-token
  [op]
  (keyword/resolve-token (or *keyword-context* *ns*) op))

(declare qualify-form)

(defn- qualify-seq
  [context-ns form]
  (let [[op & raw-args] form
        token (keyword/resolve-token context-ns op)
        args (mapv #(qualify-form context-ns %) raw-args)
        qualified-op (if token
                       (:symbol token)
                       (qualify-form context-ns op))]
    (when token
      (keyword/validate-call! token args form))
    (with-meta (apply list qualified-op args) (meta form))))

(defn qualify-form
  "Replace keyword aliases with canonical `aguafria.keyword/...` Var symbols.

  The Zig form itself remains intact; no intermediate `builtin` form is
  introduced. This makes stored declaration metadata readable and allows the
  emitter to consume generated keyword Vars directly."
  [context-ns form]
  (cond
    (seq? form) (qualify-seq context-ns form)
    (vector? form) (with-meta (mapv #(qualify-form context-ns %) form)
                              (meta form))
    (map? form) (with-meta
                  (into (empty form)
                        (map (fn [[key value]]
                               [(qualify-form context-ns key)
                                (qualify-form context-ns value)]))
                        form)
                  (meta form))
    (set? form) (with-meta (into #{} (map #(qualify-form context-ns %)) form)
                           (meta form))
    :else form))

(defn prepare-declaration
  "Qualify every Zig form in a declaration using its defining Clojure ns."
  [context-ns declaration]
  (cond-> declaration
    (contains? declaration :type)
    (update :type #(when (some? %) (qualify-form context-ns %)))

    (contains? declaration :return)
    (update :return #(qualify-form context-ns %))

    (contains? declaration :value)
    (update :value #(qualify-form context-ns %))

    (contains? declaration :body)
    (update :body #(mapv (partial qualify-form context-ns) %))

    (contains? declaration :args)
    (update :args #(mapv (fn [arg]
                           (update arg :type (partial qualify-form context-ns)))
                         %))

    (contains? declaration :fields)
    (update :fields #(mapv (fn [field]
                             (update field :type
                                     (partial qualify-form context-ns)))
                           %))))

(def ^:dynamic *source-mapping?*
  "When true, statement emission includes Clojure line/column marker comments.
  Complete modules enable this automatically; standalone emitter calls stay
  uncluttered."
  false)

(defn- emit-type*
  "Emit a Zig type from a keyword/symbol/string or a compositional vector.

  Supported vectors include `[:* t]`, `[:*const t]`, `[:many t]`,
  `[:many-const t]`, `[:sentinel t n]`, `[:slice t]`, `[:slice-const t]`,
  `[:array n t]`, `[:vector n t]`, `[:c-pointer t]`, `[:optional t]`, and
  `[:error-union t]`. A generated keyword call may also produce a type."
  [t]
  (cond
    (or (keyword? t) (symbol? t) (string? t))
    (identifier t)

    (vector? t)
    (let [[op & xs] t]
      (case op
        :* (if (= 1 (count xs))
             (str "*" (emit-type (first xs)))
             (fail! "Pointer type expects one child type" t))
        :*const (if (= 1 (count xs))
                  (str "*const " (emit-type (first xs)))
                  (fail! "Const pointer type expects one child type" t))
        :many (if (= 1 (count xs))
                (str "[*]" (emit-type (first xs)))
                (fail! "Many pointer type expects one child type" t))
        :many-const (if (= 1 (count xs))
                      (str "[*]const " (emit-type (first xs)))
                      (fail! "Const many pointer type expects one child type" t))
        :sentinel (if (= 2 (count xs))
                    (str "[*:" (emit-expr (second xs)) "]"
                         (emit-type (first xs)))
                    (fail! "Sentinel pointer type expects a child type and sentinel" t))
        :sentinel-const (if (= 2 (count xs))
                          (str "[*:" (emit-expr (second xs)) "]const "
                               (emit-type (first xs)))
                          (fail! "Const sentinel pointer type expects a child type and sentinel" t))
        :slice (if (= 1 (count xs))
                 (str "[]" (emit-type (first xs)))
                 (fail! "Slice type expects one child type" t))
        :slice-const (if (= 1 (count xs))
                       (str "[]const " (emit-type (first xs)))
                       (fail! "Const slice type expects one child type" t))
        :array (if (= 2 (count xs))
                 (str "[" (emit-expr (first xs)) "]" (emit-type (second xs)))
                 (fail! "Array type expects a length and child type" t))
        :vector (if (= 2 (count xs))
                  (str "@Vector(" (emit-expr (first xs)) ", "
                       (emit-type (second xs)) ")")
                  (fail! "SIMD vector type expects a length and child type" t))
        :c-pointer (if (= 1 (count xs))
                     (str "[*c]" (emit-type (first xs)))
                     (fail! "C pointer type expects one child type" t))
        :optional (if (= 1 (count xs))
                    (str "?" (emit-type (first xs)))
                    (fail! "Optional type expects one child type" t))
        :error-union (if (= 1 (count xs))
                       (str "!" (emit-type (first xs)))
                       (fail! "Error union type expects one child type" t))
        :const (if (= 1 (count xs))
                 (str "const " (emit-type (first xs)))
                 (fail! "Const type expects one child type" t))
        (fail! "Unknown composite Zig type" t {:operator op})))

    (seq? t)
    (emit-expr t)

    :else
    (fail! "Cannot emit Zig type" t)))

(defn emit-type
  "Emit a Zig type, resolving generated keyword Vars directly."
  ([t]
   (emit-type* t))
  ([context-ns t]
   (binding [*keyword-context* context-ns]
     (emit-type* t))))

(def ^:private infix-operators
  {"+" "+", "-" "-", "*" "*", "/" "/", "%" "%"
   "+%" "+%", "-%" "-%", "*%" "*%"
   "+|" "+|", "-|" "-|", "*|" "*|"
   "==" "==", "!=" "!=", "<" "<", "<=" "<=", ">" ">", ">=" ">="
   "and" "and", "or" "or", "xor" "xor"
   "&" "&", "|" "|", "^" "^", "<<" "<<", ">>" ">>"
   "orelse" "orelse", "catch" "catch"})

(def ^:private prefix-operators
  {"!" "!", "~" "~", "-" "-", "+" "+", "&" "&"})

(def ^:private assignment-operators
  {"=" "=", "+=" "+=", "-=" "-=", "*=" "*=", "/=" "/=", "%=" "%="
   "+%=" "+%=", "-%=" "-%=", "*%=" "*%="
   "+|=" "+|=", "-|=" "-|=", "*|=" "*|="
   "&=" "&=", "|=" "|=", "^=" "^=", "<<=" "<<=", ">>=" ">>="})

(defn- operator-name
  [op]
  (when (symbol? op) (name op)))

(defn- zig-string
  [s]
  ;; Clojure's string escapes are accepted by Zig for the common escape set.
  (pr-str s))

(defn- emit-map-literal
  [m]
  (str ".{"
       (->> m
            (sort-by (comp str key))
            (map (fn [[k v]]
                   (str "." (identifier k) " = " (emit-expr v))))
            (str/join ", "))
       "}"))

(defn- emit-vector-literal
  [xs]
  (str ".{" (str/join ", " (map emit-expr xs)) "}"))

(defn- parenthesized-infix
  [operator args form]
  (when (< (count args) 2)
    (fail! "Infix Zig operator expects at least two operands" form
           {:operator operator}))
  (str "(" (str/join (str " " operator " ") (map emit-expr args)) ")"))

(defn- emit-if-expr
  [[test then else :as args] form]
  (when-not (= 3 (count args))
    (fail! "Zig if expression expects test, then, and else" form))
  (str "(if (" (emit-expr test) ") " (emit-expr then)
       " else " (emit-expr else) ")"))

(defn- emit-keyword-expr
  [token args form]
  (keyword/validate-call! token args form)
  (case (:kind token)
    :call
    (str (:zig-name token) "("
         (str/join ", " (map emit-expr args)) ")")

    :operator
    (let [operator (:zig-token token)]
      (cond
        (and (= 1 (count args)) (contains? prefix-operators operator))
        (str "(" operator (emit-expr (first args)) ")")

        (contains? infix-operators operator)
        (parenthesized-infix operator args form)

        :else
        (fail! "Generated keyword names an unsupported Zig operator"
               form {:token token})))

    :assignment
    (fail! "Assignment keyword cannot be used as a Zig expression"
           form {:token token})

    (fail! "Unknown generated Zig keyword kind" form {:token token})))

(defn- emit-expr*
  [form]
  (cond
    (nil? form) "null"
    (true? form) "true"
    (false? form) "false"
    (string? form) (zig-string form)
    (char? form) (str "'" (case form
                            \newline "\\n"
                            \return "\\r"
                            \tab "\\t"
                            \' "\\'"
                            \\ "\\\\"
                            (str form)) "'")
    (number? form) (str form)
    (symbol? form) (identifier form)
    (keyword? form) (let [n (name form)]
                      (if (str/starts-with? n ".")
                        n
                        (identifier form)))
    (map? form) (emit-map-literal form)
    (vector? form) (emit-vector-literal form)

    (seq? form)
    (let [[op & args] form
          token (current-keyword-token op)]
      (cond
        token
        (emit-keyword-expr token args form)

        (= op 'raw)
        (if (and (= 1 (count args)) (string? (first args)))
          (first args)
          (fail! "raw expects exactly one string" form))

        (= op 'op)
        (let [[operator & operands] args
              operator (cond
                         (string? operator) operator
                         (or (symbol? operator) (keyword? operator)) (name operator)
                         :else nil)]
          (cond
            (and operator (= 1 (count operands))
                 (contains? prefix-operators operator))
            (str "(" (get prefix-operators operator)
                 (emit-expr (first operands)) ")")

            (and operator (contains? infix-operators operator))
            (parenthesized-infix (get infix-operators operator) operands form)

            :else
            (fail! "op expects a supported Zig operator token and operands" form
                   {:operator operator})))

        (= op 'if)
        (emit-if-expr args form)

        (= op 'field)
        (if (= 2 (count args))
          (str "(" (emit-expr (first args)) ")." (identifier (second args)))
          (fail! "field expects a target and field name" form))

        (= op 'deref)
        (if (= 1 (count args))
          (str "(" (emit-expr (first args)) ").*")
          (fail! "deref expects one pointer expression" form))

        (= op 'unwrap)
        (if (= 1 (count args))
          (str "(" (emit-expr (first args)) ").?")
          (fail! "unwrap expects one optional expression" form))

        (= op 'index)
        (if (= 2 (count args))
          (str "(" (emit-expr (first args)) ")[" (emit-expr (second args)) "]")
          (fail! "index expects a target and index" form))

        (= op 'slice)
        (if (<= 2 (count args) 3)
          (let [[target start end] args]
            (str "(" (emit-expr target) ")[" (emit-expr start) ".."
                 (when (some? end) (emit-expr end)) "]"))
          (fail! "slice expects a target, start, and optional end" form))

        (= op 'try)
        (if (= 1 (count args))
          (str "try " (emit-expr (first args)))
          (fail! "try expects one expression" form))

        (= op 'comptime)
        (if (= 1 (count args))
          (str "comptime " (emit-expr (first args)))
          (fail! "comptime expects one expression" form))

        (= op 'nosuspend)
        (if (= 1 (count args))
          (str "nosuspend " (emit-expr (first args)))
          (fail! "nosuspend expects one expression" form))

        (contains? infix-operators (operator-name op))
        (if (and (= 1 (count args))
                 (contains? prefix-operators (operator-name op)))
          (str "(" (get prefix-operators (operator-name op))
               (emit-expr (first args)) ")")
          (parenthesized-infix (get infix-operators (operator-name op)) args form))

        (and (contains? prefix-operators (operator-name op)) (= 1 (count args)))
        (str "(" (get prefix-operators (operator-name op))
             (emit-expr (first args)) ")")

        (or (symbol? op) (keyword? op) (string? op) (seq? op))
        (str (if (seq? op)
               (str "(" (emit-expr op) ")")
               (identifier op))
             "(" (str/join ", " (map emit-expr args)) ")")

        :else
        (fail! "Cannot emit Zig invocation" form {:operator op})))

    :else
    (fail! "Cannot emit Zig expression" form {:class (class form)})))

(defn emit-expr
  "Emit one Zig expression, resolving `aguafria.keyword` Vars directly."
  ([form]
   (emit-expr* form))
  ([context-ns form]
   (binding [*keyword-context* context-ns]
     (emit-expr* form))))

(defn- indent
  [level text]
  (let [padding (apply str (repeat (* 4 level) " "))]
    (->> (str/split-lines (str text))
         (map #(if (str/blank? %) % (str padding %)))
         (str/join "\n"))))

(defn- form-source-comment
  [form]
  (let [{:keys [line column]} (meta form)]
    (when (and *source-mapping?* line)
      (str "// Clojure form: " line (when column (str ":" column)) "\n"))))

(defn- braced
  [forms level]
  (if (seq forms)
    (str "{\n" (indent (inc level) (emit-statements forms (inc level))) "\n"
         (indent level "}"))
    "{}"))

(defn- branch-forms
  [form]
  (if (and (seq? form) (= 'do (first form)))
    (rest form)
    [form]))

(defn- emit-local
  [kind args form]
  (let [[n a b] args
        [t value] (case (count args)
                    2 [nil a]
                    3 [a b]
                    (fail! (str kind " expects name, optional type, and value") form))]
    (str kind " " (identifier n)
         (when (and t (not= t '_)) (str ": " (emit-type t)))
         " = " (emit-expr value) ";")))

(defn- emit-if-stmt
  [args level form]
  (let [[test then else :as all] args]
    (when-not (<= 2 (count all) 3)
      (fail! "Zig if statement expects test, then, and optional else" form))
    (str "if (" (emit-expr test) ") "
         (braced (branch-forms then) level)
         (when (some? else)
           (str " else " (if (and (seq? else) (= 'if (first else)))
                            (emit-if-stmt (rest else) level else)
                            (braced (branch-forms else) level)))))))

(defn- emit-loop
  [kind args level form]
  (let [[test & body] args]
    (when (or (nil? test) (empty? body))
      (fail! (str kind " expects a condition/iterable and a body") form))
    (str kind " (" (emit-expr test) ") " (braced body level))))

(defn- emit-for
  [args level form]
  (let [[bindings & body] args]
    (when-not (and (vector? bindings) (= 2 (count bindings)) (seq body))
      (fail! "for expects [capture iterable] and a body" form))
    (let [[capture iterable] bindings
          captures (if (vector? capture) capture [capture])]
      (when-not (every? #(or (symbol? %) (keyword? %)) captures)
        (fail! "for captures must be identifiers" capture))
      (str "for (" (emit-expr iterable) ") |"
           (str/join ", " (map identifier captures)) "| "
           (braced body level)))))

(defn emit-stmt
  "Emit one Zig statement. `level` is used only for nested block indentation."
  ([form] (emit-stmt form 0))
  ([form level]
   (let [rendered
         (if-not (seq? form)
           (str (emit-expr form) ";")
           (let [[op & args] form
                 token (current-keyword-token op)]
             (cond
               (and token (= :assignment (:kind token)))
               (do
                 (keyword/validate-call! token args form)
                 (let [[target value] args]
                   (str (emit-expr target) " " (:zig-token token) " "
                        (emit-expr value) ";")))

               (= op 'do) (emit-statements args level)
               (= op 'raw) (emit-expr form)
               (= op 'comment) (if (and (= 1 (count args)) (string? (first args)))
                                 (->> (str/split-lines (first args))
                                      (map #(str "// " %))
                                      (str/join "\n"))
                                 (fail! "comment expects one string" form))
               (= op 'return) (case (count args)
                                0 "return;"
                                1 (str "return " (emit-expr (first args)) ";")
                                (fail! "return expects zero or one expression" form))
               (= op 'const) (emit-local "const" args form)
               (= op 'var) (emit-local "var" args form)
               (= op 'set!) (if (= 2 (count args))
                              (str (emit-expr (first args)) " = "
                                   (emit-expr (second args)) ";")
                              (fail! "set! expects a target and value" form))
               (= op 'assign)
               (let [[operator target value :as assignment] args]
                 (when-not (and (= 3 (count assignment))
                                (string? operator)
                                (contains? (set (vals assignment-operators)) operator))
                   (fail! "assign expects an assignment operator string, target, and value"
                          form {:operator operator}))
                 (str (emit-expr target) " " operator " " (emit-expr value) ";"))
               (contains? assignment-operators (operator-name op))
               (if (= 2 (count args))
                 (str (emit-expr (first args)) " "
                      (get assignment-operators (operator-name op)) " "
                      (emit-expr (second args)) ";")
                 (fail! "Assignment operator expects a target and value" form
                        {:operator op}))
               (= op 'if) (emit-if-stmt args level form)
               (= op 'while) (emit-loop "while" args level form)
               (= op 'for) (emit-for args level form)
               (= op 'block) (braced args level)
               (= op 'defer) (if (= 1 (count args))
                               (str "defer " (if (and (seq? (first args))
                                                      (= 'do (ffirst args)))
                                               (braced (rest (first args)) level)
                                               (emit-stmt (first args) level)))
                               (fail! "defer expects one statement or do block" form))
               (= op 'errdefer) (if (= 1 (count args))
                                  (str "errdefer " (if (and (seq? (first args))
                                                           (= 'do (ffirst args)))
                                                    (braced (rest (first args)) level)
                                                    (emit-stmt (first args) level)))
                                  (fail! "errdefer expects one statement or do block" form))
               (= op 'break) (case (count args)
                               0 "break;"
                               1 (str "break " (emit-expr (first args)) ";")
                               2 (str "break :" (identifier (first args)) " "
                                      (emit-expr (second args)) ";")
                               (fail! "break expects optional value or label and value" form))
               (= op 'continue) (case (count args)
                                  0 "continue;"
                                  1 (str "continue :" (identifier (first args)) ";")
                                  (fail! "continue expects at most one label" form))
               (= op 'unreachable) (if (empty? args)
                                     "unreachable;"
                                     (fail! "unreachable takes no arguments" form))
               :else (str (emit-expr form) ";"))))]
     (str (form-source-comment form) rendered))))

(defn emit-stmt-in
  "Emit one statement while resolving aliases in `context-ns`."
  [context-ns form]
  (binding [*keyword-context* context-ns]
    (emit-stmt form)))

(defn emit-statements
  ([forms] (emit-statements forms 0))
  ([forms level]
   (str/join "\n" (map #(emit-stmt % level) forms))))

(declare emit-returning-statements)

(defn- returning-braced
  [forms level]
  (str "{\n"
       (indent (inc level) (emit-returning-statements forms (inc level)))
       "\n" (indent level "}")))

(def ^:private non-value-statement-ops
  #{"const" "var" "set!" "assign" "while" "for" "block" "defer" "errdefer"
    "break" "continue" "unreachable" "comment"
    "=" "+=" "-=" "*=" "/=" "%=" "+%=" "-%=" "*%="
    "+|=" "-|=" "*|=" "&=" "|=" "^=" "<<=" ">>="})

(defn- emit-returning-tail
  [form level]
  (let [rendered
        (if-not (seq? form)
          (str "return " (emit-expr form) ";")
          (let [[op & args] form]
            (cond
              (= op 'return)
              ;; emit-stmt already writes this form's source marker.
              (emit-stmt form level)

              (= op 'do)
              (emit-returning-statements args level)

              (= op 'if)
              (let [[test then else :as all] args]
                (when-not (= 3 (count all))
                  (fail! "A tail if in a non-void function requires an else branch" form))
                (str "if (" (emit-expr test) ") "
                     (returning-braced (branch-forms then) level)
                     " else "
                     (returning-braced (branch-forms else) level)))

              (contains? non-value-statement-ops (operator-name op))
              (fail! "The final form of a non-void Zig function must produce a value"
                     form {:operator op})

              :else
              (str "return " (emit-expr form) ";"))))]
    (if (and (seq? form) (= 'return (first form)))
      rendered
      (str (form-source-comment form) rendered))))

(defn- emit-returning-statements
  [forms level]
  (when-not (seq forms)
    (fail! "A non-void Zig function requires a result expression" forms))
  (str/join "\n"
            (concat (map #(emit-stmt % level) (butlast forms))
                    [(emit-returning-tail (last forms) level)])))

(defn emit-function-body
  "Emit a function body. Non-void functions implicitly return their final
  expression; explicit `return` remains available for early exits."
  [forms return-type]
  (if (= :void return-type)
    (emit-statements forms 0)
    (emit-returning-statements forms 0)))

(defn parse-typed-bindings
  "Parse `[x :- :i32 y :- :f64]`, `[x :i32 y :f64]`, or
  `[[x :i32] [y :f64]]` into `[{:name ... :type ...}]`."
  [bindings]
  (when-not (vector? bindings)
    (fail! "Typed bindings must be a vector" bindings))
  (cond
    (every? vector? bindings)
    (mapv (fn [pair]
            (when-not (= 2 (count pair))
              (fail! "Typed binding pair expects a name and type" pair))
            {:name (first pair) :type (second pair)})
          bindings)

    (some #{':-} bindings)
    (do
      (when-not (zero? (mod (count bindings) 3))
        (fail! "Typed bindings using :- must contain name :- type triples" bindings))
      (mapv (fn [[n marker t :as triple]]
              (when-not (= marker ':-)
                (fail! "Expected :- in typed binding" triple))
              {:name n :type t})
            (partition 3 bindings)))

    :else
    (do
      (when-not (zero? (mod (count bindings) 2))
        (fail! "Typed bindings must contain name/type pairs" bindings))
      (mapv (fn [[n t]] {:name n :type t}) (partition 2 bindings)))))

(defn- source-comment
  [{:keys [file line column]}]
  (when (or file line)
    (str "// Clojure source: " (or file "<repl>")
         (when line (str ":" line))
         (when column (str ":" column)) "\n")))

(defn- declaration-sort-key
  [{:keys [kind name]}]
  [({:raw 0 :struct 1 :const 2 :var 3 :fn 4} kind 9)
   (str name)])

(defn emit-declaration
  "Emit a normalized declaration descriptor."
  [{:keys [kind name type value fields body args return export? public? layout
           source code] :as declaration}]
  (str
   (source-comment source)
   (case kind
     :raw
     (if (string? code) code (fail! "Raw declaration requires string :code" declaration))

     :const
     (str (when (not= false public?) "pub ") "const " (identifier name)
          (when type (str ": " (emit-type type)))
          " = " (emit-expr value) ";")

     :var
     (str (when (not= false public?) "pub ") "var " (identifier name)
          (when type (str ": " (emit-type type)))
          " = " (emit-expr value) ";")

     :struct
     (str (when (not= false public?) "pub ") "const " (identifier name) " = "
          (case layout
            :extern "extern struct"
            :packed "packed struct"
            :normal "struct"
            nil "extern struct"
            (fail! "Struct :layout must be :extern, :packed, or :normal" declaration))
          " {\n"
          (indent 1 (->> fields
                         (map (fn [{:keys [name type]}]
                                (str (identifier name) ": " (emit-type type) ",")))
                         (str/join "\n")))
          "\n};")

     :fn
     (let [body-source (emit-function-body body return)]
       (str (cond
              export? "export "
              public? "pub "
              :else "")
            "fn " (identifier name) "("
            (->> args
                 (map (fn [{:keys [name type]}]
                        (str (identifier name) ": " (emit-type type))))
                 (str/join ", "))
            ")"
            (when export? " callconv(.c)")
            " " (emit-type return) " {\n"
            (when (seq body-source) (str (indent 1 body-source) "\n"))
            "}"))

     (fail! "Unknown Zig declaration kind" declaration {:kind kind}))))

(defn emit-module
  "Emit a complete deterministic Zig source module from declarations."
  ([module-name declarations]
   (binding [*source-mapping?* true]
     (str "// Generated by Aguafria. Edit the Clojure declarations, not this file.\n"
          "// Module: " module-name "\n\n"
          (->> declarations
               (sort-by declaration-sort-key)
               (map emit-declaration)
               (str/join "\n\n"))
          "\n")))
  ([context-ns module-name declarations]
   (emit-module module-name
                (mapv (partial prepare-declaration context-ns) declarations))))
