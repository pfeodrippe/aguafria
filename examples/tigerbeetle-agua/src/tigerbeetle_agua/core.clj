(ns tigerbeetle-agua.core
  "A small, REPL-first host for the Aguafria-generated TigerBeetle project."
  (:require
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
  "Return a compact view of the generated main module's compiler state."
  []
  (when-let [info (az/module-info tigerbeetle-main)]
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
     :declaration-count (count (:definitions info))}))

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

(defn await-reload!
  "Wait for the latest evaluated declarations and return main-module status."
  []
  (az/await! tigerbeetle-main)
  (module-summary))

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
