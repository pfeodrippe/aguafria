(ns aguafria.zig
  "Define ordinary Zig declarations with Clojure data.

  Require this namespace as `az`. Declaration macros capture their bodies;
  the bodies are emitted as Zig and are never evaluated as Clojure."
  (:refer-clojure :exclude [defn defstruct])
  (:require [aguafria.keywords :as keywords]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.runtime :as runtime]))

(clojure.core/defn emit-expr "Emit one Zig expression." [form]
  (emitter/emit-expr (keywords/normalize-form *ns* form)))
(clojure.core/defn emit-stmt "Emit one Zig statement." [form]
  (emitter/emit-stmt (keywords/normalize-form *ns* form)))
(clojure.core/defn emit-type "Emit one Zig type." [type]
  (emitter/emit-type (keywords/normalize-form *ns* type)))
(clojure.core/defn emit-module "Emit a complete Zig module." [module declarations]
  (emitter/emit-module
   module
   (mapv (fn [declaration]
           (cond-> declaration
             (:type declaration)
             (update :type #(keywords/normalize-form *ns* %))

             (:return declaration)
             (update :return #(keywords/normalize-form *ns* %))

             (:value declaration)
             (update :value #(keywords/normalize-form *ns* %))

             (:body declaration)
             (update :body #(mapv (partial keywords/normalize-form *ns*) %))

             (:args declaration)
             (update :args #(mapv (fn [arg]
                                    (update arg :type
                                            (partial keywords/normalize-form *ns*)))
                                  %))

             (:fields declaration)
             (update :fields #(mapv (fn [field]
                                      (update field :type
                                              (partial keywords/normalize-form *ns*)))
                                    %))))
         declarations)))
(clojure.core/defn source "Return a module's current generated Zig source." [module]
  (runtime/source module))
(clojure.core/defn module-info "Return inspectable loaded-module information." [module]
  (runtime/module-info module))
(clojure.core/defn stats
  "Return monitor-friendly compilation statistics globally or for one module."
  ([] (runtime/stats))
  ([module] (runtime/stats module)))
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
(clojure.core/defn await!
  "Wait for the newest async build of one module or every known module."
  ([] (runtime/await!))
  ([module]
   (runtime/await! module)
   (runtime/module-info module)))

(defn- source-location
  [form]
  (merge {:file *file* :ns (str *ns*)}
         (select-keys (meta form) [:line :column])))

(defn- declaration-options
  [name]
  (let [m (meta name)]
    {:export? (not= false (:export m))
     :public? (if (contains? m :public) (:public m) (not (:private m)))}))

(defn- parse-defn-declaration
  [form name declaration]
  (let [[docstring declaration] (if (string? (first declaration))
                                  [(first declaration) (next declaration)]
                                  [nil declaration])
        [marker return bindings & body] declaration]
    (when-not (= marker ':-)
      (throw (ex-info "az/defn expects: name :- return-type [typed args] body..."
                      {:form form :name name :declaration declaration})))
    (let [qualified-name (symbol (str *ns*) (str name))
          normalize (partial keywords/normalize-form *ns*)]
      (merge {:kind :fn
              :name name
              :qualified-name qualified-name
              :declaration-key [:fn name]
              :module (str *ns*)
              :doc docstring
              :return (normalize return)
              :args (mapv #(update % :type normalize)
                          (emitter/parse-typed-bindings bindings))
              :body (mapv normalize body)
              :clojure-form form
              :source (source-location form)}
             (declaration-options name)))))

(defmacro defn
  "Define, compile, and expose a Zig function.

      (az/defn add :- :i32
        [a :- :i32 b :- :i32]
        (+ a b))

  The generated function is exported with the C calling convention by
  default, making supported scalar signatures callable from Clojure. Attach
  `^{:export false}` to the name for a Zig-only function. Non-void functions
  implicitly return their final expression."
  [name & declaration]
  (let [descriptor (parse-defn-declaration &form name declaration)
        qualified-name (:qualified-name descriptor)
        docstring (:doc descriptor)
        arglist (->> (:args descriptor)
                     (mapcat (fn [{:keys [name type]}]
                               [name ':- type]))
                     vec)
        quoted-descriptor (list 'quote descriptor)]
    `(do
       (runtime/register-declaration! ~quoted-descriptor)
       (clojure.core/defn ~(with-meta name (meta name))
         [& arguments#]
         (runtime/invoke! '~qualified-name arguments#))
       (alter-meta! (var ~name) merge
                    {:doc ~docstring
                     :arglists '~(list arglist)
                     :aguafria/declaration ~quoted-descriptor})
       (var ~name))))

(defmacro defconst
  "Define a public Zig top-level constant. Type may be `_` to infer it."
  [name type value]
  (let [normalize (partial keywords/normalize-form *ns*)
        descriptor {:kind :const
                    :name name
                    :declaration-key [:const name]
                    :module (str *ns*)
                    :type (when-not (= '_ type) (normalize type))
                    :value (normalize value)
                    :clojure-form &form
                    :public? (not (:private (meta name)))
                    :source (source-location &form)}]
    `(do
       (runtime/register-declaration! '~descriptor)
       (def ~(with-meta name (assoc (meta name) :doc "A Zig top-level constant."))
         {:aguafria/declaration '~descriptor})
       (var ~name))))

(defmacro defvar
  "Define a public Zig top-level variable. Type may be `_` to infer it."
  [name type value]
  (let [normalize (partial keywords/normalize-form *ns*)
        descriptor {:kind :var
                    :name name
                    :declaration-key [:var name]
                    :module (str *ns*)
                    :type (when-not (= '_ type) (normalize type))
                    :value (normalize value)
                    :clojure-form &form
                    :public? (not (:private (meta name)))
                    :source (source-location &form)}]
    `(do
       (runtime/register-declaration! '~descriptor)
       (def ~(with-meta name (assoc (meta name) :doc "A Zig top-level variable."))
         {:aguafria/declaration '~descriptor})
       (var ~name))))

(defmacro defstruct
  "Define a Zig struct from typed fields. The default is `extern struct`;
  attach `^{:layout :normal}` or `^{:layout :packed}` to the name to change it."
  [name fields]
  (let [normalize (partial keywords/normalize-form *ns*)
        descriptor {:kind :struct
                    :name name
                    :declaration-key [:struct name]
                    :module (str *ns*)
                    :fields (mapv #(update % :type normalize)
                                  (emitter/parse-typed-bindings fields))
                    :layout (or (:layout (meta name)) :extern)
                    :clojure-form &form
                    :public? (not (:private (meta name)))
                    :source (source-location &form)}]
    `(do
       (runtime/register-declaration! '~descriptor)
       (def ~(with-meta name (assoc (meta name) :doc "A Zig struct declaration."))
         {:aguafria/declaration '~descriptor})
       (var ~name))))

(defmacro defraw
  "Register a named top-level Zig source fragment, useful for imports and
  declarations not yet represented by the data emitter."
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
                    :source (source-location &form)}]
    `(do
       (runtime/register-declaration! '~descriptor)
       (def ~(with-meta name (assoc (meta name) :doc "A raw Zig declaration."))
         {:aguafria/declaration '~descriptor})
       (var ~name))))
