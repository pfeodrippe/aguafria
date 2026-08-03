(ns aguafria.zig.host
  "Development hosts for long-lived native Aguafria programs.

  This API is intentionally separate from `aguafria.zig`: starting and waiting
  for a whole native program is operational tooling, not a common declaration
  form."
  (:refer-clojure :exclude [await])
  (:require [aguafria.zig.runtime :as runtime]))

(defn start!
  "Start a public `fn main(std.process.Init) !void` in this JVM.

  `arguments` excludes argv[0]. Options accept `:argv0` and `:share-state?`
  (default true). A state-sharing host temporarily owns the live state capsules,
  so only one may run at a time; false creates an isolated host. The returned
  handle is plain data and can be passed to `await!` or `info`. While the host
  is running, compatible Aguafria Var publications update its native dispatch
  and state cells atomically. A breaking type/container publication pins an
  existing host to its complete old dispatch generation until it exits and a
  replacement host is started. Selective live adoption of a breaking object
  graph requires an explicit host quiescence/migration API and is not guessed;
  `info` exposes `:dispatch-frozen?` and the schema that caused the pin."
  ([main arguments]
   (runtime/start-process-main! main arguments))
  ([main arguments options]
   (runtime/start-process-main! main arguments options)))

(defn await!
  "Wait for a host and return its process-style exit result. Completion means
  shared state ownership is back in the JVM and native host arenas are closed,
  making this the safe boundary for structural migration/replacement."
  [host]
  (runtime/await-host! host))

(defn restart!
  "Wait for `host` to quiesce naturally, then run the current Aguafria
  generation in a replacement native host inside the same JVM process.

  Use this after breaking function/type publications and any required explicit
  state migrations. Compatible edits do not need a restart. Options may
  override `:arguments` (excluding argv[0]), `:argv0`, and `:share-state?`.
  The replacement handle exposes `:replaces-host-id` for inspection. Aguafria
  never kills an arbitrary Zig main or guesses how to migrate stack/heap
  objects; a long-running program must reach a cooperative/natural safe point."
  ([host]
   (runtime/restart-process-main! host))
  ([host options]
   (runtime/restart-process-main! host options)))

(defn info
  "Return serializable status for one host."
  [host]
  (runtime/host-info host))

(defn stats
  "Return serializable status for every native host, newest first."
  []
  (runtime/host-stats))
