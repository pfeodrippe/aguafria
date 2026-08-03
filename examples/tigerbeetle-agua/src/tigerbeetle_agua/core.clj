(ns tigerbeetle-agua.core
  "A small, REPL-first host for the Aguafria-generated TigerBeetle project."
  (:require
   [aguafria.keyword :as ak]
   [aguafria.zig :as az]
   [aguafria.zig.host :as host]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [tigerbeetle.src.constants :as tiger-constants]
   [tigerbeetle.src.tigerbeetle.main :as tiger-main]
   [tigerbeetle.src.vsr :as vsr]))

(def tigerbeetle-main
  "The ordinary Clojure namespace generated from TigerBeetle's main.zig."
  'tigerbeetle.src.tigerbeetle.main)

(defn defaults
  "Read the editable example defaults from the project resources."
  []
  (-> "tigerbeetle_agua/defaults.edn" io/resource slurp edn/read-string))

(defn module-summary
  "Return a compact view of one generated module's compiler state."
  ([] (module-summary tigerbeetle-main))
  ([module]
   (when-let [info (az/module-info module)]
     {:module (:module info)
      :status (cond
                (:error info) :failed
                (:pending? info) :compiling
                (:source-only? info) :source-only
                (:published-generation info) :finished
                :else :registered)
      :source-only? (:source-only? info)
      :pending? (:pending? info)
      :error (:error info)
      :requested-generation (:requested-generation info)
      :published-generation (:published-generation info)
      :native-generation-count (:native-generation-count info)
      :declaration-count (count (:definitions info))})))

(defn load!
  "Load and compile generated TigerBeetle in this JVM.

  Compilation is asynchronous by default so large dependency components can
  advance in parallel. This function waits at the boundary and returns a small
  inspectable status map. Set :reload? true after regenerating the files."
  ([] (load! {}))
  ([{:keys [async? reload?]
     :or {async? true reload? false}}]
   (az/configure! {:async? async?})
   (when reload?
     (require tigerbeetle-main :reload))
   (az/await! tigerbeetle-main)
   (or (module-summary)
       (throw (ex-info "TigerBeetle loaded without registering its Zig module"
                       {:module tigerbeetle-main})))))

(defn main-var
  "Return TigerBeetle's normally required generated main Var."
  []
  #'tiger-main/main)

(defn start-command!
  "Start a converted TigerBeetle CLI command on a native thread in this JVM.

  Arguments exclude argv[0]. Hosts are isolated by default so a replica and
  one or more clients can coexist in the same JVM."
  ([arguments] (start-command! arguments {}))
  ([arguments options]
   (load!)
   (host/start! (main-var)
                (mapv str arguments)
                (merge {:argv0 "tigerbeetle"
                        :share-state? false}
                       options))))

(defn run-command!
  "Run a finite TigerBeetle CLI command and return its host completion map."
  ([arguments] (run-command! arguments {}))
  ([arguments options]
   (host/await! (start-command! arguments options))))

(defn version!
  "Run the generated TigerBeetle `version` command."
  []
  (run-command! ["version"]))

(defn format-replica!
  "Format a replica data file. This intentionally performs TigerBeetle's
  normal on-disk format operation and therefore should be called explicitly."
  ([] (format-replica! {}))
  ([options]
   (let [{:keys [cluster replica replica-count development? data-file]}
         (merge (defaults) options)]
     (io/make-parents (io/file data-file))
     (run-command!
      (cond-> ["format"
               (str "--cluster=" cluster)
               (str "--replica=" replica)
               (str "--replica-count=" replica-count)]
        development? (conj "--development")
        true (conj data-file))))))

(defn start-replica!
  "Start a long-running replica and return its inspectable native host handle."
  ([] (start-replica! {}))
  ([options]
   (let [{:keys [addresses development? data-file]} (merge (defaults) options)]
     (start-command!
      (cond-> ["start" (str "--addresses=" addresses)]
        development? (conj "--development")
        true (conj data-file))))))

(defn- statement-argument
  [statement]
  (-> (str statement)
      str/trim
      (str/replace #";+\s*$" "")))

(defn run-statement!
  "Connect through one converted TigerBeetle client and run one REPL request."
  ([statement] (run-statement! statement {}))
  ([statement options]
   (let [{:keys [cluster addresses]} (merge (defaults) options)
         statement (statement-argument statement)]
     (when (str/blank? statement)
       (throw (ex-info "run-statement! requires a non-blank statement" {})))
     (run-command! ["repl"
                    (str "--cluster=" cluster)
                    (str "--addresses=" addresses)
                    (str "--command=" statement)]))))

(defn run-statements!
  "Run several TigerBeetle REPL requests sequentially inside this JVM.

  TigerBeetle's noninteractive client completes after one request, so each
  statement gets a finite native client host. Returns their completion maps."
  ([statements] (run-statements! statements {}))
  ([statements options]
   (when-not (and (sequential? statements) (seq statements))
     (throw (ex-info "run-statements! requires a non-empty sequence"
                     {:statements statements})))
   (mapv #(run-statement! % options) statements)))

(def demo-statements
  "A repeatable-in-a-fresh-data-file account and transfer walkthrough."
  ["create_accounts id=1 code=10 ledger=700, id=2 code=10 ledger=700"
   (str "create_transfers id=1 debit_account_id=1 credit_account_id=2 "
        "amount=10 ledger=700 code=10")
   "lookup_accounts id=1, id=2"])

(defn create-demo-accounts!
  "Create the two accounts used by the example transfer."
  ([] (create-demo-accounts! {}))
  ([options]
   (run-statement! (first demo-statements) options)))

(defn create-demo-transfer!
  "Transfer 10 units from demo account 1 to account 2."
  ([] (create-demo-transfer! {}))
  ([options]
   (run-statement! (second demo-statements) options)))

(defn lookup-demo-accounts!
  "Read both demo accounts and their current balances."
  ([] (lookup-demo-accounts! {}))
  ([options]
   (run-statement! (nth demo-statements 2) options)))

(defn run-demo!
  "Create accounts, make a transfer, and read the resulting balances."
  ([] (run-demo! {}))
  ([options]
   (run-statements! demo-statements options)))

(defn await-module-reload!
  "Wait for one module's latest declarations and return compact status."
  [module]
  (az/await! module)
  (module-summary module))

(defn await-reload!
  "Wait for the latest evaluated declarations and return main-module status."
  []
  (await-module-reload! tigerbeetle-main))

(defn status
  "Return compiler and native-host statistics suitable for a future monitor."
  []
  {:module (module-summary)
   :compiler (:summary (az/stats))
   :hosts (host/stats)})

(defn check!
  "Compile the generated main module and execute its finite version command."
  []
  {:load (load!)
   :version (version!)
   :status (status)})

(defn- usage
  []
  (str "TigerBeetle Aguafria example\n\n"
       "  clojure -M:check\n"
       "  clojure -M -m tigerbeetle-agua.core version\n"))

(defn -main
  [& [command]]
  (try
    (case command
      "check" (pprint/pprint (check!))
      "version" (pprint/pprint (version!))
      (println (usage)))
    (finally
      ;; Compiler work may use Clojure's agent pools. Terminal
      ;; aliases should exit; an nREPL deliberately keeps its pools alive.
      (shutdown-agents))))

(comment

  ;; Generation is deliberately separate because this namespace normally
  ;; requires the output. It also works from a clean terminal with -M:generate.
  (require '[tigerbeetle-agua.generate :as generate])
  (generate/generated?)
  (generate/generate!)

  ;; Load/compile everything and inspect what Aguafria is doing.
  (load!)
  (status)

  ;; Run a finite command using the generated main Var.
  (version!)

  ;; Generated functions with supported ABIs are normal, directly callable
  tiger-constants/sector_size
  (vsr/sector_floor 4097)
  (vsr/sector_ceil 4097)

  ;; Start a fresh one-replica development cluster. Formatting writes data.
  (format-replica!)
  (def replica (start-replica!))
  (host/info replica)

  ;; Use the converted CLI client for its existing high-level transaction
  ;; parser. These clients are native threads in this same JVM too.
  (create-demo-accounts!)
  (create-demo-transfer!)
  (lookup-demo-accounts!)
  ;; Or run the whole sequence against a newly formatted data file:
  (run-demo!)

  ;; Edit/evaluate an az/defn in any generated namespace with Calva, then wait
  ;; for publication. Compatible callers in the running replica swap live.
  (await-reload!)
  (status)

  ;; Long-running Zig mains stop at their own cooperative/natural safe point;
  ;; Aguafria deliberately does not kill arbitrary native execution.)

  )

(comment

  ;; COMPLETE TRANSACTION-VISIBLE HOT-RELOAD WALKTHROUGH
  ;;
  ;; Start from examples/tigerbeetle-agua in a terminal with:
  ;;
  ;;   clojure -M:nrepl
  ;;
  ;; Connect Calva/CIDER to the printed port, open this file, evaluate its ns
  ;; form, and then evaluate the expressions below one at a time. Everything
  ;; runs inside that one JVM. This uses a fresh disposable TigerBeetle data
  ;; file and an available local port; it does not touch the default data file.
  (def hot-reload-directory
    (.toFile
     (java.nio.file.Files/createTempDirectory
      "tigerbeetle-agua-hot-reload-"
      (make-array java.nio.file.attribute.FileAttribute 0))))

  (def hot-reload-address
    (with-open [socket (java.net.ServerSocket. 0)]
      (str (.getLocalPort socket))))

  (def hot-reload-options
    {:addresses hot-reload-address
     :data-file (str (io/file hot-reload-directory "0_0.tigerbeetle"))})

  ;; EDIT 1 OF 3 — SIMPLE FUNCTION BODY
  ;;
  ;; Evaluate this function and call it like an ordinary Clojure Var.
  (az/defn live-transfer-amount :- :u128
    []
    10)

  (live-transfer-amount)
  ;; => 10
  ;;
  ;; Change only `10` above to `12`, evaluate that ONE az/defn again, then:
  (await-module-reload! 'tigerbeetle-agua.core)
  (live-transfer-amount)
  ;; => 12. The existing Var now dispatches to the new native body.

  ;; START THE REAL TIGERBEETLE SERVER
  ;;
  ;; Compile the converted program, format one replica, and start it on a
  ;; native thread. Keep `hot-reload-replica` running throughout every step.
  (load!)
  (format-replica! hot-reload-options)
  (def hot-reload-replica (start-replica! hot-reload-options))
  (host/info hot-reload-replica)
  ;; => {:status :running, :active? true, ...}

  ;; EDIT 2 OF 3 — REAL TIGERBEETLE TRANSACTION LOGIC
  ;;
  ;; Interact through TigerBeetle's converted REPL. Create two accounts and
  ;; create transfer 100 WITHOUT an `amount`. The checked parser defaults it
  ;; to zero, so the lookup printed by TigerBeetle shows debits_posted=0 for
  ;; account 1 and credits_posted=0 for account 2.
  (run-statement!
   "create_accounts id=1 code=10 ledger=700, id=2 code=10 ledger=700"
   hot-reload-options)

  (run-statement!
   (str "create_transfers id=100 debit_account_id=1 credit_account_id=2 "
        "ledger=700 code=10")
   hot-reload-options)

  (run-statement! "lookup_accounts id=1, id=2" hot-reload-options)
  ;; TigerBeetle prints, in part:
  ;;   account 1: debits_posted=0
  ;;   account 2: credits_posted=0

  ;; Open this generated Clojure namespace in the same editor:
  ;;
  ;;   ../../generated/tigerbeetle/tigerbeetle/src/repl/parser.clj
  ;;
  ;; Find the `az/defn object_default` form and change only its
  ;; `:.create_transfers` case from:
  ;;
  ;;   (std-mem/zeroInit (az/field tb Transfer) (az/object []))
  ;;
  ;; to:
  ;;
  ;;   (std-mem/zeroInit (az/field tb Transfer) (az/object [[:amount 25]]))
  ;;
  ;; Evaluate that ONE complete `az/defn object_default` form with Calva/CIDER.
  ;; It is a real comptime-dependent TigerBeetle parser function. Wait for its
  ;; new generation and all compatible dependents to publish:
  (await-module-reload! 'tigerbeetle.src.repl.parser)
  (host/info hot-reload-replica)
  ;; => still {:status :running, :active? true, ...}; no restart occurred.

  ;; Use TigerBeetle's REPL again. Transfer 101 also omits `amount`, but the
  ;; hot parser now supplies 25. The live replica applies the real transaction,
  ;; and the next lookup makes the reload visible in durable ledger state.
  (run-statement!
   (str "create_transfers id=101 debit_account_id=1 credit_account_id=2 "
        "ledger=700 code=10")
   hot-reload-options)

  (run-statement! "lookup_accounts id=1, id=2" hot-reload-options)
  ;; TigerBeetle now prints, in part:
  ;;   account 1: debits_posted=25
  ;;   account 2: credits_posted=25

  ;; Undo the one-line parser edit, evaluate `object_default` once more,
  ;; and await it. A third omitted amount is zero again; balances remain 25.
  (await-module-reload! 'tigerbeetle.src.repl.parser)

  (run-statement!
   (str "create_transfers id=102 debit_account_id=1 credit_account_id=2 "
        "ledger=700 code=10")
   hot-reload-options)

  (run-statement! "lookup_accounts id=1, id=2" hot-reload-options)
  ;; => debits_posted=25 and credits_posted=25.

  ;; EDIT 3 OF 3 — COMPTIME FUNCTION RETURNING A STRUCT TYPE
  ;;
  ;; Evaluate both definitions. HotAmountType is a comptime type factory whose
  ;; returned struct has data plus a method. Its attrs make it a normal public
  ;; Zig declaration with an implicit return, without incorrectly C-exporting
  ;; a comptime signature. comptime-amount is an ordinary compiled caller.
  (az/defn HotAmountType
    {:attrs #{:public :implicit-return}}
    :-
    :type
    [[bonus {:zig/prefix "comptime"} :u64]]
    (az/container
     {:kind :struct}
     (az/field-decl base :u64)
     (az/const-decl Self (ak/This))
     (az/fn-decl amount
       {:attrs #{:implicit-return}}
       :-
       :u64
       [[self [:*const Self]]]
       (+ (az/field self base) bonus))))

  (az/defn comptime-amount :- :u64
    [[base :u64]]
    (ak/var calculator
      (HotAmountType 5)
      (az/object [[:base base]]))
    ((az/field calculator amount)))

  (await-module-reload! 'tigerbeetle-agua.core)
  (comptime-amount 10)
  ;; => 15
  ;;
  ;; In HotAmountType, change only the method's final expression from:
  ;;
  ;;   (+ (az/field self base) bonus)
  ;;
  ;; to:
  ;;
  ;;   (+ (az/field self base) (* bonus 2))
  ;;
  ;; Evaluate that ONE complete HotAmountType az/defn, not comptime-amount.
  (await-module-reload! 'tigerbeetle-agua.core)
  (comptime-amount 10)
  ;; => 20. The existing caller was republished against the compatible new
  ;; struct method; its public signature and the struct layout did not change.

  (mapv #(select-keys % [:generation :status :active? :schema-fingerprint])
        (az/type-versions #'HotAmountType))
  ;; => one active version whose :status is :compatible.
  ;;
  ;; Body-only function/method edits publish into existing live callers and
  ;; objects immediately. No restart or migration is needed. Only an ABI or
  ;; stored-layout change creates a new version: old objects/callers stay valid
  ;; on that old version while new code uses the new version, until the user
  ;; chooses an explicit safe migration/restart.

  )
