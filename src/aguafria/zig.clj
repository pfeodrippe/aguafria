(ns aguafria.zig
  "Define ordinary Zig declarations with Clojure data.

  Require this namespace as `az`. Declaration macros capture their bodies;
  the bodies are emitted as Zig and are never evaluated as Clojure."
  (:refer-clojure :exclude [cast defn defn- defstruct])
  (:require [aguafria.keyword :as keyword]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.project :as project]
            [aguafria.zig.runtime :as runtime]
            [aguafria.zig.value :as value]
            [clojure.string :as str]))

;; A previous REPL load may have interned structural placeholder Vars under
;; names that are ordinary Clojure forms. Restore those core mappings before
;; compiling this namespace again; the declaration bodies themselves remain
;; quoted data and are interpreted by the Zig emitter.
(clojure.core/doseq [operator '[let when when-not for dotimes case]]
  (clojure.core/when
   (:aguafria/syntax (meta (get (ns-interns *ns*) operator)))
    (ns-unmap *ns* operator)))
(refer 'clojure.core :only '[let when when-not for dotimes case])

(clojure.core/defn emit-expr "Emit one Zig expression." [form]
  (emitter/emit-expr *ns* form))

(clojure.core/defn emit-stmt "Emit one Zig statement." [form]
  (emitter/emit-stmt-in *ns* form))

(clojure.core/defn emit-type "Emit one Zig type." [type]
  (emitter/emit-type *ns* type))

(clojure.core/defn emit-module "Emit a complete Zig module." [module declarations]
  (emitter/emit-module *ns* module declarations))

(clojure.core/defn source "Return a module's current generated Zig source." [module]
  (runtime/source module))

(clojure.core/defn module-info "Return inspectable loaded-module information." [module]
  (runtime/module-info module))

(clojure.core/defn zig-value?
  "True for an exact native Zig value handle."
  [candidate]
  (value/zig-value? candidate))

(clojure.core/defn zig-type?
  "True for an ordinary callable Aguafria Zig type constructor."
  [candidate]
  (value/zig-type? candidate))

(clojure.core/defn zig-pointer?
  "True for a typed borrowed Zig pointer returned by az/value."
  [candidate]
  (value/zig-pointer? candidate))

(clojure.core/defn pointer-address
  "Return a borrowed Zig pointer's native address."
  [pointer]
  (value/pointer-address pointer))

(clojure.core/defn pointer-type
  "Return a borrowed Zig pointer's exact Aguafria type form."
  [pointer]
  (value/pointer-type pointer))

(clojure.core/defn pointer-segment
  "Return a borrowed FFM MemorySegment of `byte-size` bytes at a Zig pointer."
  [pointer byte-size]
  (value/pointer-segment pointer byte-size))

(clojure.core/defn value-info
  "Inspect a native Zig value without forcing it."
  [zig-value]
  (value/info zig-value))

(clojure.core/defn native-segment
  "Return a Zig value's authoritative FFM MemorySegment."
  [zig-value]
  (value/segment zig-value))

(clojure.core/defn native-bytes
  "Copy a Zig value's exact native representation into a byte vector."
  [zig-value]
  (value/bytes zig-value))

(clojure.core/defn value
  "Return the semantic Clojure value represented by a native Zig value.
  Scalars remain scalars; structs become maps and arrays/vectors become
  vectors. The ZigValue itself retains the authoritative native bytes."
  [zig-value]
  (if (value/zig-value? zig-value)
    (value/decoded zig-value)
    zig-value))

(clojure.core/defn close!
  "Release a native Zig value explicitly. Closing is idempotent; otherwise
  the backing memory is released automatically when the value becomes
  unreachable."
  [zig-value]
  (when (value/zig-value? zig-value)
    (.close ^java.lang.AutoCloseable zig-value))
  nil)

(clojure.core/defn set-value!
  "Write a checked Clojure value into an az/defvar's actual native storage.
  This is an in-place REPL operation, not a compilation. Coordinate with
  running native threads exactly as ordinary Zig code must."
  [zig-var value]
  (when-not (value/zig-value? zig-var)
    (throw (ex-info "az/set-value! expects the value of an az/defvar Var"
                    {:value zig-var
                     :clojure-type (clojure.core/type zig-var)})))
  (value/set-value! zig-var value))

(clojure.core/defn function-versions
  "Return loaded ABI versions for an exported Zig Var or qualified symbol."
  [function]
  (runtime/function-versions function))

(clojure.core/defn invoke-version!
  "Invoke a retained scalar ABI version by its fingerprint."
  [function abi-fingerprint arguments]
  (runtime/invoke-version! function abi-fingerprint arguments))

(clojure.core/defn stats
  "Return monitor-friendly compilation statistics globally or for one module."
  ([] (runtime/stats))
  ([module] (runtime/stats module)))

(clojure.core/defn toolchain-information
  "Return the identity and materialization state of the embedded Zig toolchain."
  []
  (runtime/toolchain-information))

(clojure.core/defn zig-executable
  "Return the absolute path of Aguafria's verified embedded Zig compiler."
  []
  (runtime/zig-executable))

(clojure.core/defn build!
  "Build a generated module as a standalone Zig artifact."
  ([module] (runtime/build! module))
  ([module options] (runtime/build! module options)))

(clojure.core/defn configure! "Merge Aguafria compiler configuration." [options]
  (runtime/configure! options))

(clojure.core/defn configuration "Return current Aguafria configuration." []
  (runtime/configuration))

(clojure.core/defn clear! "Forget loaded modules and build history." []
  (runtime/clear!))

(clojure.core/defn recompile!
  "Recompile one module or every known module using current configuration."
  ([] (runtime/recompile!))
  ([module] (runtime/recompile! module)))

(clojure.core/defn recompile-component!
  "Atomically prepare and publish every module in a dependency SCC."
  [module]
  (runtime/recompile-component! module))

(clojure.core/defn recompile-affected!
  "Recompile a module SCC followed by every transitively dependent SCC."
  [module]
  (runtime/recompile-affected! module))

(clojure.core/defn state-versions
  "Return the retained native state generations for an az/defvar."
  [state]
  (runtime/state-versions state))

(clojure.core/defn type-versions
  "Return retained schema generations for a defstruct/container type Var."
  [type]
  (runtime/type-versions type))

(clojure.core/defn migrate-state!
  "Apply an explicit Zig migration to a breaking az/defvar schema change."
  [state migration]
  (runtime/migrate-state! state migration))

(clojure.core/defn await!
  "Wait for the newest async build of one module or every known module."
  ([] (runtime/await!))
  ([module]
   (runtime/await! module)
   (runtime/module-info module)))

(clojure.core/defn- source-file?
  [value]
  (and (string? value)
       (not (str/blank? value))
       (< (count value) 4096)
       (not (re-find #"[\r\n]" value))))

(clojure.core/defn- source-location
  [form]
  (let [file (some #(when (source-file? %) %)
                   [(:file (meta form)) *file*])]
    (cond-> (merge {:ns (str *ns*)}
                   (select-keys (meta form) [:line :column]))
      file (assoc :file file))))

(clojure.core/defn- import-reference-namespace
  [context-ns import-alias]
  (let [safe #(str/replace (str %) #"[^A-Za-z0-9_.-]" "_")]
    (symbol (str "aguafria.zig.import."
                 (safe (ns-name context-ns)) "." (safe import-alias)))))

(clojure.core/defn- unavailable-import-reference
  [reference]
  (fn [& arguments]
    (throw
     (ex-info
      (str (:symbol reference)
           " is a Zig import member and can only be called inside az/defn")
      {:reference reference :arguments arguments}))))

(clojure.core/defn ^:no-doc install-import-references!
  "Install the real Vars backing an `az/defimport` alias. Public only because
  macro expansions must restore the aliases when source is loaded or reloaded."
  [context-ns import-alias zig-alias import-name members]
  (let [context-ns (if (instance? clojure.lang.Namespace context-ns)
                     context-ns
                     (the-ns context-ns))
        target-name (import-reference-namespace context-ns import-alias)
        target-ns (or (find-ns target-name) (create-ns target-name))]
    (doseq [[sym v] (ns-interns target-ns)
            :when (:aguafria/import-reference (meta v))]
      (ns-unmap target-ns sym))
    (doseq [{:keys [clojure-name zig-name]} members]
      (let [reference {:kind :import-member
                       :import import-name
                       :module zig-alias
                       :member zig-name
                       :zig-name (str zig-alias "." zig-name)
                       :symbol (symbol (str target-name) (str clojure-name))}
            v (intern target-ns clojure-name
                      (unavailable-import-reference reference))]
        (alter-meta! v merge
                     {:aguafria/import-reference true
                      :aguafria/zig-reference reference
                      :arglists '([& arguments])
                      :doc (str "Zig import member `" (:zig-name reference)
                                "` from `" import-name "`. Only valid inside "
                                "an Aguafria declaration.")})))
    (binding [*ns* context-ns]
      (when-let [old-target (get (ns-aliases context-ns) import-alias)]
        (when-not (= target-ns old-target)
          (ns-unalias context-ns import-alias)))
      (when-not (= target-ns (get (ns-aliases context-ns) import-alias))
        (alias import-alias target-name)))
    target-ns))

(clojure.core/defn- normalize-import-member
  [form member]
  (let [[clojure-name zig-name]
        (cond
          (symbol? member) [member (emitter/identifier member)]
          (and (vector? member)
               (= 2 (count member))
               (symbol? (first member))
               (string? (second member)))
          member
          :else
          (throw (ex-info
                  "az/defimport members must be symbols or [clojure-name \"zig.path\"] pairs"
                  {:form form :member member})))
        clojure-name-text (name clojure-name)]
    (when (or (namespace clojure-name)
              (str/includes? clojure-name-text "."))
      (throw (ex-info
              "az/defimport member Var names must be unqualified and cannot contain dots"
              {:form form :member member :clojure-name clojure-name})))
    (when (or (str/blank? zig-name) (re-find #"[\r\n]" zig-name))
      (throw (ex-info "az/defimport Zig member paths must be non-empty single-line strings"
                      {:form form :member member :zig-name zig-name})))
    {:clojure-name clojure-name :zig-name zig-name}))

(clojure.core/defn- declaration-options
  ([name] (declaration-options name nil))
  ([name attributes]
   (project/ensure-source-catalog! *file*)
   (let [m (merge (meta name) attributes)
         context (or project/*catalog-namespace* (ns-name *ns*))
         converted? (project/converted-module? context)
         compact? (or (contains? m :attrs)
                      (project/compact-default? context name))
         attrs (set (:attrs m))]
     {:export? (cond
                 (contains? m :export) (not= false (:export m))
                 (contains? attrs :export) true
                 :else false)
      :public? (cond
                 (contains? m :public) (not= false (:public m))
                 (:private m) false
                 (contains? attrs :public) true
                 converted? false
                 :else true)
      :source-order (or (:zig/order m)
                        (project/declaration-source-order
                         (ns-name *ns*) name))
      :leading-source (:zig/leading m)
      :zig-prefix (:zig/prefix m)
      :zig-qualifiers (:zig/qualifiers m)
      :zig-name (:zig/name m)
      :implicit-return? (cond
                          (contains? m :implicit-return)
                          (not= false (:implicit-return m))

                          (contains? attrs :explicit-return)
                          false

                          (contains? attrs :implicit-return)
                          true

                          converted?
                          false

                          :else
                          true)
      :emit-source-comment? (if compact? (contains? attrs :source-comment)
                                (not= false (:source-comment m)))
      :comments (:comments m)
      :attributes (or attributes {})})))

(clojure.core/defn- leading-doc-and-attributes
  [declaration]
  (let [[docstring declaration]
        (if (and (string? (first declaration)) (next declaration))
          [(first declaration) (next declaration)]
          [nil declaration])
        [attributes declaration]
        (if (and (map? (first declaration)) (next declaration))
          [(first declaration) (next declaration)]
          [{} declaration])]
    [(or docstring (:doc attributes)) attributes declaration]))

(clojure.core/defn- declaration-reference
  [declaration]
  (let [{:keys [kind module name zig-name value return logical-id abi-fingerprint
                schema-fingerprint implementation-fingerprint]}
        (runtime/declaration-info declaration)]
    (cond-> {:kind :declaration
             :declaration-kind kind
             :module module
             :zig-name (emitter/identifier (or zig-name name))
             :symbol (symbol module (str name))}
      logical-id (assoc :logical-id logical-id)
      abi-fingerprint (assoc :abi-fingerprint abi-fingerprint)
      implementation-fingerprint
      (assoc :implementation-fingerprint implementation-fingerprint)
      schema-fingerprint (assoc :schema-fingerprint schema-fingerprint)
      (= :var kind)
      (assoc :state-accessor (:accessor (runtime/state-reference declaration)))
      (or (= :struct kind)
          (and (= :const kind)
               (or (and (seq? value)
                        (= 'container (first value)))
                   (and (symbol? value)
                        (-> value meta :aguafria/zig-reference
                            :type-reference?)))))
      (assoc :type-reference? true))))

(clojure.core/defn- descriptor-expression
  "Serialize macro data so very large Zig forms do not exceed the JVM's
  per-string or per-method classfile limits."
  [descriptor]
  (let [descriptor (runtime/declaration-info descriptor)
        text (binding [*print-meta* true] (pr-str descriptor))
        size 12000
        chunks (->> (range 0 (count text) size)
                    (mapv #(subs text % (min (count text) (+ % size)))))]
    `(runtime/read-declaration ~chunks)))

(clojure.core/defn- development-c-abi-type?
  "True only for scalar Zig types whose C ABI is guaranteed on supported
  targets. Rich Zig values use Aguafria's generated JVM trampoline instead of
  changing the user's function calling convention."
  [type]
  (contains? #{:void :bool
               :i8 :i16 :i32 :i64 :i128 :isize
               :u8 :u16 :u32 :u64 :u128 :usize
               :f32 :f64
               :c_char :c_short :c_int :c_long :c_longlong
               :c_uchar :c_ushort :c_uint :c_ulong :c_ulonglong}
             type))

(clojure.core/defn- parse-defn-declaration
  [form name declaration private?]
  (let [[docstring attributes declaration]
        (leading-doc-and-attributes declaration)
        [marker return bindings & body] declaration]
    (when-not (= marker ':-)
      (throw (ex-info "az/defn expects: name :- return-type [typed args] body..."
                      {:form form :name name :declaration declaration})))
    (let [qualified-name (symbol (str *ns*) (str name))
          args (emitter/parse-typed-bindings bindings)
          generic?
          (boolean
           (some (fn [{:keys [type properties]}]
                   (or (= "comptime" (:zig/prefix properties))
                       (contains? #{:anytype 'anytype :type 'type} type)))
                 args))
          attrs (set (:attrs attributes))
          context (or project/*catalog-namespace* (ns-name *ns*))
          converted? (project/converted-module? context)
          explicit-public?
          (or (contains? attrs :public)
              (contains? attributes :public)
              (contains? attributes :private)
              (contains? (meta name) :public)
              (contains? (meta name) :private))
          explicit-export?
          (or (contains? attrs :export)
              (true? (:export attributes)))
          explicit-export-setting?
          (or (contains? attrs :export)
              (contains? attributes :export)
              (contains? (meta name) :export))
          _ (when (and generic? explicit-export?)
              (throw
               (ex-info
                "A generic/comptime Zig function cannot use :export"
                {:form form
                 :name name
                 :arguments args
                 :hint "Remove :export; concrete Aguafria callers remain hot-reloadable."})))
          options (cond-> (declaration-options name attributes)
                    (and (not private?) (not converted?)
                         (not explicit-public?))
                    (assoc :public? true)
                    (and (not private?) (not converted?) (not generic?)
                         (not explicit-export-setting?)
                         (development-c-abi-type? return)
                         (every? (comp development-c-abi-type? :type) args)
                         (str/blank? (or (:zig/prefix attributes) ""))
                         (str/blank? (or (:zig/qualifiers attributes) "")))
                    ;; The development dylib may expose a C-callable wrapper
                    ;; even though the user's standalone Zig declaration is
                    ;; only `pub fn`. This hidden bit preserves versioned JVM
                    ;; and cross-module hot reload without changing emitted
                    ;; release semantics or the user-facing `:export?` value.
                    (assoc :development-export? true)
                    private? (assoc :public? false
                                    :export? explicit-export?
                                    :development-export? false)
                    generic? (assoc :export? false
                                    :development-export? false))]
      (emitter/prepare-declaration
       *ns*
       (merge {:kind :fn
               :name name
               :qualified-name qualified-name
               :declaration-key [:fn name]
               :module (str *ns*)
               :doc docstring
               :return return
               :args args
               :body (vec body)
               :clojure-form form
               :source (source-location form)}
              options)))))

(clojure.core/defn- defn-expansion
  [form name declaration private?]
  (let [descriptor (parse-defn-declaration form name declaration private?)
        qualified-name (:qualified-name descriptor)
        docstring (:doc descriptor)
        clojure-name (cond-> name
                       private? (vary-meta assoc :private true))
        arglist (->> (:args descriptor)
                     (mapcat (fn [{:keys [name type]}]
                               [name ':- type]))
                     vec)
        descriptor-form (descriptor-expression descriptor)
        quoted-reference (list 'quote (declaration-reference descriptor))]
    `(let [descriptor# ~descriptor-form]
       (runtime/register-declaration! descriptor#)
       (clojure.core/defn ~(with-meta clojure-name (meta clojure-name))
         [& arguments#]
         (runtime/invoke! '~qualified-name arguments#))
       (alter-meta! (var ~clojure-name) merge
                    {:doc ~docstring
                     :arglists '~(list arglist)
                     :aguafria/declaration descriptor#
                     :aguafria/zig-reference ~quoted-reference})
       (runtime/refresh-declaration-var! descriptor#)
       (var ~clojure-name))))

(defmacro defn
  "Define, compile, and expose a Zig function.

      (az/defn add :- :i32
        [a :- :i32 b :- :i32]
        (+ a b))

  The Zig declaration is public to its module and remains directly callable
  from Clojure through Aguafria's generated development bridge. Add
  `{:attrs #{:export}}` only when an external C-ABI symbol is intentionally
  required. Generic/comptime functions retain Zig's native ABI and are reached
  through concrete callers. Non-void functions implicitly return their final
  expression."
  [name & declaration]
  (defn-expansion &form name declaration false))

(defmacro defn-
  "Define a private, hot-reloadable Zig function.

      (az/defn- add-internal :- :i32
        [[a :i32] [b :i32]]
        (+ a b))

  The Zig declaration is private to its module and the corresponding Clojure
  Var carries ordinary `:private` metadata. It remains callable from its own
  namespace and from the REPL through Aguafria's generated development bridge.
  Non-void functions implicitly return their final expression."
  [name & declaration]
  (defn-expansion &form name declaration true))

(defmacro defconst
  "Define a Zig top-level constant.

  Accepts an optional docstring and attr-map after the name. Use either
  `value` for inferred type or `type value` for an explicit Zig type."
  [name & declaration]
  (let [[docstring attributes declaration]
        (leading-doc-and-attributes declaration)
        [type value]
        (case (count declaration)
          1 [nil (first declaration)]
          2 [(first declaration) (second declaration)]
          (throw (ex-info
                  "az/defconst expects name, optional doc/attr-map, optional type, and value"
                  {:form &form :name name :declaration declaration})))
        descriptor (emitter/prepare-declaration
                    *ns*
                    (merge {:kind :const
                            :name name
                            :declaration-key [:const name]
                            :module (str *ns*)
                            :doc docstring
                            :type (when-not (or (nil? type) (= '_ type)) type)
                            :value value
                            :clojure-form &form
                            :source (source-location &form)}
                           (declaration-options name attributes)))
        descriptor-form (descriptor-expression descriptor)]
    `(let [descriptor# ~descriptor-form]
       (runtime/register-declaration! descriptor#)
       (def ~(with-meta name (assoc (meta name) :doc (or docstring "A Zig top-level constant.")))
         (runtime/declaration-root-value descriptor#))
       (alter-meta! (var ~name) merge
                    {:aguafria/declaration descriptor#
                     :aguafria/zig-reference '~(declaration-reference descriptor)})
       (runtime/refresh-declaration-var! descriptor#)
       (var ~name))))

(defmacro defvar
  "Define a Zig top-level variable with optional docstring, attr-map, and type."
  [name & declaration]
  (let [[docstring attributes declaration]
        (leading-doc-and-attributes declaration)
        [type value]
        (case (count declaration)
          1 [nil (first declaration)]
          2 [(first declaration) (second declaration)]
          (throw (ex-info
                  "az/defvar expects name, optional doc/attr-map, optional type, and value"
                  {:form &form :name name :declaration declaration})))
        descriptor (emitter/prepare-declaration
                    *ns*
                    (merge {:kind :var
                            :name name
                            :declaration-key [:var name]
                            :module (str *ns*)
                            :doc docstring
                            :type (when-not (or (nil? type) (= '_ type)) type)
                            :value value
                            :clojure-form &form
                            :source (source-location &form)}
                           (declaration-options name attributes)))
        descriptor-form (descriptor-expression descriptor)]
    `(let [descriptor# ~descriptor-form]
       (runtime/register-declaration! descriptor#)
       (def ~(with-meta name (assoc (meta name) :doc (or docstring "A Zig top-level variable.")))
         (runtime/declaration-state-value descriptor#))
       (alter-meta! (var ~name) merge
                    {:aguafria/declaration descriptor#
                     :aguafria/zig-reference '~(declaration-reference descriptor)})
       (runtime/refresh-declaration-var! descriptor#)
       (var ~name))))

(defmacro defstruct
  "Define a Zig struct from Malli-style field entries.

      (az/defstruct Vector2
        [[:x :f32]
         [:y {:doc \"Vertical component\"} :f32]])

  Each entry is `[field type]` or `[field properties type]`; properties remain
  inspectable in declaration metadata. The default is an ordinary Zig
  `struct`; pass `{:layout :extern}` or `{:layout :packed}` to change it. A
  known struct Var is also a constructor form inside Zig code, so
  `(Vector2 {:x 1.0 :y 2.0})` emits `Vector2{ .x = 1.0, .y = 2.0 }`."
  [name & declaration]
  (let [[docstring attributes declaration]
        (leading-doc-and-attributes declaration)
        _ (when-not (= 1 (count declaration))
            (throw (ex-info
                    "az/defstruct expects name, optional doc/attr-map, and fields"
                    {:form &form :name name :declaration declaration})))
        fields (first declaration)
        descriptor (emitter/prepare-declaration
                    *ns*
                    (merge {:kind :struct
                            :name name
                            :declaration-key [:struct name]
                            :module (str *ns*)
                            :doc docstring
                            :fields (emitter/parse-struct-fields fields)
                            :layout (or (:layout attributes) (:layout (meta name)) :normal)
                            :clojure-form &form
                            :source (source-location &form)}
                           (declaration-options name attributes)))
        descriptor-form (descriptor-expression descriptor)]
    `(let [descriptor# ~descriptor-form]
       (runtime/register-declaration! descriptor#)
       (def ~(with-meta name (assoc (meta name) :doc (or docstring "A Zig struct declaration.")))
         (runtime/declaration-type-value descriptor#))
       (alter-meta! (var ~name) merge
                    {:aguafria/declaration descriptor#
                     :aguafria/zig-reference '~(declaration-reference descriptor)})
       (runtime/refresh-declaration-var! descriptor#)
       (var ~name))))

(defmacro defimport
  "Import a Zig module and expose its named members as real Clojure Vars.

      (az/defimport extra-math \"extra_math\" [quadruple])
      (az/defn four-times :- :i32 [x :- :i32]
        (extra-math/quadruple x))

  A member can be `[clojure-name \"zig.nested.path\"]` when its Zig path is not
  a valid Clojure Var name. The module root path is still supplied separately
  through `az/configure!`'s `:modules` map when it is not Zig's `std`."
  [name import-name members]
  (when-not (and (symbol? name) (nil? (namespace name)))
    (throw (ex-info "az/defimport expects an unqualified Clojure alias"
                    {:form &form :name name})))
  (when-not (and (string? import-name)
                 (not (str/blank? import-name))
                 (not (re-find #"[\r\n]" import-name)))
    (throw (ex-info "az/defimport expects a non-empty single-line import name"
                    {:form &form :import-name import-name})))
  (when-not (vector? members)
    (throw (ex-info "az/defimport expects a vector of member Vars"
                    {:form &form :members members})))
  (let [normalized-members (mapv (partial normalize-import-member &form) members)
        zig-alias (emitter/identifier name)
        descriptor {:kind :import
                    :name name
                    :declaration-key [:import name]
                    :module (str *ns*)
                    :import-name import-name
                    :members normalized-members
                    :clojure-form &form
                    :source (source-location &form)}
        descriptor (merge descriptor (declaration-options name))
        descriptor-form (descriptor-expression descriptor)]
    ;; Install during macro expansion so following az/defn forms resolve the
    ;; alias, and again in the expansion so compiled/reloaded source restores it.
    (install-import-references! *ns* name zig-alias import-name normalized-members)
    `(let [descriptor# ~descriptor-form]
       (install-import-references! (the-ns '~(ns-name *ns*)) '~name ~zig-alias
                                   ~import-name '~normalized-members)
       (runtime/register-declaration! descriptor#)
       (def ~(with-meta name
               (assoc (meta name)
                      :doc (str "Zig module imported from `" import-name "`.")))
         {:aguafria/declaration descriptor#})
       (alter-meta! (var ~name) merge
                    {:aguafria/declaration descriptor#
                     :aguafria/zig-reference '~(declaration-reference descriptor)})
       (runtime/refresh-declaration-var! descriptor#)
       (var ~name))))

(defmacro defraw
  "Register a named top-level Zig source fragment for declarations not yet
  represented by the data emitter. Use `defimport` for imports."
  [name code]
  (when-not (string? code)
    (throw (ex-info "az/defraw code must be a string literal"
                    {:form &form :name name :code code})))
  (let [descriptor {:kind :raw
                    :name name
                    :declaration-key [:raw name]
                    :module (str *ns*)
                    :code code
                    :clojure-form &form
                    :source (source-location &form)}
        descriptor (merge descriptor (declaration-options name))
        descriptor-form (descriptor-expression descriptor)]
    `(let [descriptor# ~descriptor-form]
       (runtime/register-declaration! descriptor#)
       (def ~(with-meta name (assoc (meta name) :doc "A raw Zig declaration."))
         {:aguafria/declaration descriptor#})
       (alter-meta! (var ~name) merge
                    {:aguafria/declaration descriptor#
                     :aguafria/zig-reference '~(declaration-reference descriptor)})
       (runtime/refresh-declaration-var! descriptor#)
       (var ~name))))

(defmacro deffield
  "Define a field of a Zig file/container root.

      (az/deffield count :u64 0)

  An optional docstring and attr-map follow the name. The initializer is
  optional. Converted files that use Zig's file-as-struct pattern emit this
  form rather than disguising fields as raw declarations."
  [name & declaration]
  (let [[docstring attributes declaration]
        (leading-doc-and-attributes declaration)
        [type & initializer] declaration]
    (when-not type
      (throw (ex-info "az/deffield requires a type"
                      {:form &form :name name :declaration declaration})))
  (when (> (count initializer) 1)
    (throw (ex-info "az/deffield accepts at most one initializer"
                    {:form &form :name name :initializer initializer})))
  (let [options (merge (meta name) attributes)
        descriptor (emitter/prepare-declaration
                    *ns*
                    (merge {:kind :field
                            :name name
                            :declaration-key [:field name]
                            :module (str *ns*)
                            :type type
                            :has-value? (boolean (seq initializer))
                            :value (first initializer)
                            :align (:zig/align options)
                            :doc docstring
                            :clojure-form &form
                            :source (source-location &form)}
                           (declaration-options name attributes)))
        descriptor-form (descriptor-expression descriptor)]
    `(let [descriptor# ~descriptor-form]
       (runtime/register-declaration! descriptor#)
       (def ~(with-meta name (assoc (meta name) :doc
                                    (or docstring "A Zig container-root field.")))
         {:aguafria/declaration descriptor#})
       (alter-meta! (var ~name) merge
                    {:aguafria/declaration descriptor#
                     :aguafria/zig-reference '~(declaration-reference descriptor)})
       (runtime/refresh-declaration-var! descriptor#)
       (var ~name)))))

(defmacro defcomptime
  "Define a named, inspectable top-level Zig `comptime` block. The Clojure name
  is for REPL inspection only and is not emitted into Zig. Accepts an optional
  docstring and ordinary attr-map after the name."
  [name & declaration]
  (let [[docstring attributes body]
        (leading-doc-and-attributes declaration)
        descriptor (emitter/prepare-declaration
                    *ns*
                    (merge {:kind :comptime
                            :name name
                            :declaration-key [:comptime name]
                            :module (str *ns*)
                            :body (vec body)
                            :doc docstring
                            :implicit-return? false
                            :clojure-form &form
                            :source (source-location &form)}
                           (declaration-options name attributes)))
        descriptor-form (descriptor-expression descriptor)]
    `(let [descriptor# ~descriptor-form]
       (runtime/register-declaration! descriptor#)
       (def ~(with-meta name (assoc (meta name) :doc
                                    (or docstring "A Zig top-level comptime block.")))
         {:aguafria/declaration descriptor#})
       (alter-meta! (var ~name) assoc :aguafria/declaration descriptor#)
       (runtime/refresh-declaration-var! descriptor#)
       (var ~name))))

(defmacro defextern
  "Declare an external Zig function prototype without a body.

      (az/defextern GetCommandLineW :- windows/LPWSTR [])

  Prefix/library/calling-convention spelling is retained in the optional
  attr-map. The Var is usable from Zig declarations but is not a JVM FFM
  export of the generated module."
  [name & declaration]
  (let [[docstring attributes declaration]
        (leading-doc-and-attributes declaration)
        [marker return bindings] declaration]
  (when-not (= marker ':-)
    (throw (ex-info "az/defextern expects: name :- return-type [typed args]"
                    {:form &form :name name})))
  (let [qualified-name (symbol (str *ns*) (str name))
        descriptor (emitter/prepare-declaration
                    *ns*
                    (merge {:kind :fn-proto
                            :name name
                            :qualified-name qualified-name
                            :declaration-key [:fn-proto name]
                            :module (str *ns*)
                            :doc docstring
                            :return return
                            :args (emitter/parse-typed-bindings bindings)
                            :clojure-form &form
                            :source (source-location &form)}
                           (declaration-options name attributes)))
        descriptor-form (descriptor-expression descriptor)]
    `(let [descriptor# ~descriptor-form]
       (runtime/register-declaration! descriptor#)
       (clojure.core/defn ~(with-meta name (meta name))
         [& arguments#]
         (throw (ex-info "A Zig extern declaration cannot be called directly from Clojure"
                         {:function '~qualified-name :arguments arguments#})))
       (alter-meta! (var ~name) merge
                    {:doc ~docstring
                     :aguafria/declaration descriptor#
                     :aguafria/zig-reference '~(declaration-reference descriptor)})
       (runtime/refresh-declaration-var! descriptor#)
       (var ~name)))))

(defmacro defexternvar
  "Declare an external Zig/C global variable without claiming ownership of
  its storage. The resulting Var is an inspectable Zig reference; standalone
  and development links resolve the symbol from the configured native input.

      (az/defexternvar errno :- :c_int)"
  [name & declaration]
  (let [[docstring attributes declaration]
        (leading-doc-and-attributes declaration)
        [marker type] declaration]
    (when-not (and (= marker ':-) type (= 2 (count declaration)))
      (throw (ex-info "az/defexternvar expects: name :- type"
                      {:form &form :name name})))
    (let [descriptor (emitter/prepare-declaration
                      *ns*
                      (merge {:kind :extern-var
                              :name name
                              :declaration-key [:extern-var name]
                              :module (str *ns*)
                              :doc docstring
                              :type type
                              :clojure-form &form
                              :source (source-location &form)}
                             (declaration-options name attributes)))
          descriptor-form (descriptor-expression descriptor)]
      `(let [descriptor# ~descriptor-form]
         (runtime/register-declaration! descriptor#)
         (def ~(with-meta name (assoc (meta name) :doc
                                      (or docstring
                                          "An external Zig/C global variable.")))
           {:aguafria/declaration descriptor#})
         (alter-meta! (var ~name) merge
                      {:aguafria/declaration descriptor#
                       :aguafria/zig-reference
                       '~(declaration-reference descriptor)})
         (runtime/refresh-declaration-var! descriptor#)
         (var ~name)))))

(defmacro deftest
  "Define a Zig `test` declaration. The name may be a string, symbol, or nil.
  A leading attr-map supports the same compact `:attrs` set as declarations."
  [& declaration]
  (let [[options declaration] (if (map? (first declaration))
                                [(first declaration) (rest declaration)]
                                [{:attrs #{}} declaration])
        [name & body] declaration
        internal-name (symbol (str "zig-test-" (Math/abs (hash [name &form]))))
        declaration-options (declaration-options internal-name options)
        descriptor (emitter/prepare-declaration
                    *ns*
                    (merge {:kind :test
                            :name internal-name
                            :test-name name
                            :declaration-key [:test internal-name]
                            :module (str *ns*)
                            :body (vec body)
                            :implicit-return? false
                            :clojure-form &form
                            :source (source-location &form)}
                           (select-keys declaration-options
                                        [:source-order :leading-source
                                         :emit-source-comment? :comments])))
        descriptor-form (descriptor-expression descriptor)]
    `(runtime/register-declaration! ~descriptor-form)))

(clojure.core/defn- unavailable-syntax-form
  [operator]
  (fn [& arguments]
    (throw (ex-info (str "`az/" operator "` is Aguafria Zig syntax and can only "
                         "be used inside an Aguafria declaration")
                    {:operator operator :arguments arguments}))))

(clojure.core/defn- resolved-declaration
  [symbol]
  (when (symbol? symbol)
    (some-> (ns-resolve *ns* symbol) meta :aguafria/declaration)))

(clojure.core/defn- expression-zig-type
  [form]
  (cond
    (symbol? form)
    (:type (resolved-declaration form))

    (seq? form)
    (let [operator (first form)
          operator-name (some-> operator name)
          declaration (resolved-declaration operator)]
      (cond
        (= "&" operator-name)
        (when-let [child-type (expression-zig-type (second form))]
          [:* child-type])

        declaration
        (:return declaration)))

    :else nil))

(clojure.core/defn- optional-zig-type?
  [type]
  (and (vector? type) (= "optional" (some-> type first name))))

(defmacro cast
  "Cast an optional opaque/C pointer to `output-type`, checking alignment.

  Inside an Aguafria declaration:

      (-> optional-pointer
          (az/cast [:* Widget]))

  rewrites to existing Aguafria forms for Zig's optional unwrap, `@alignCast`,
  `@ptrCast`, and `@as`. It creates no runtime wrapper or new emitter syntax."
  [value output-type]
  (let [input-type (expression-zig-type value)
        ;; Preserve the original convenience for an untyped opaque expression:
        ;; those C APIs conventionally return optionals. When Aguafria can
        ;; prove the input is non-optional (for example `&waveform`), avoid
        ;; emitting the invalid `.?` unwrap.
        pointer (if (or (nil? input-type)
                        (optional-zig-type? input-type))
                  (list 'unwrap value)
                  value)]
    (list 'aguafria.keyword/as
          (list 'type output-type)
          (list 'aguafria.keyword/ptrCast
                (list 'aguafria.keyword/alignCast pointer)))))

;; Structural forms are real, documented Vars so generated Clojure never
;; relies on an unresolved list head. Clojure-native forms (`if`, `do`, `for`,
;; and so on) and Zig keyword/operator Vars owned by `ak` stay in their natural
;; namespaces.
(doseq [operator (emitter/syntax-operators)
        :when (and (not (contains? '#{let when when-not for dotimes case}
                                   operator))
                   (not (special-symbol? operator))
                   (nil? (keyword/token-name (name operator))))]
  (when (contains? (ns-map *ns*) operator)
    (ns-unmap *ns* operator))
  (let [syntax {:kind :syntax
                :name operator
                :symbol (symbol "aguafria.zig" (name operator))}
        v (intern *ns* operator (unavailable-syntax-form operator))]
    (alter-meta! v merge
                 {:aguafria/syntax syntax
                  :arglists '([& forms])
                  :doc (str "Aguafria structural Zig form `" operator
                            "`. Valid only inside `az/defn`, `az/defconst`, "
                            "or another Aguafria declaration.")})))
