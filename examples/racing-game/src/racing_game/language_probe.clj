(ns racing-game.language-probe
  "JVM-free diagnostic for the same native text inference used during QA.
  Run from the racing-game project directory; no action heads or game world."
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as a]
            [aguafria.std.debug :as debug]
            [racing-game.inference :as inference]))

(a/defn print-reply! :void [[prompt [:slice-const :u8]]]
  (let [result (inference/generate-language! 0 (a/field prompt ptr) (a/field prompt len) 24)]
    (debug/print "prompt: {s}\nvalid={} stop={d} input={d} output={d}\nreply: {s}\n"
      [prompt (a/field result valid) (a/field result stop)
       (a/field result input_tokens) (a/field result output_tokens)
       (a/slice (a/field result bytes) 0 (a/field result byte_count))])))

(a/defn main :void []
  (debug/assert (a/field (inference/load-model! "resources/models/granite-4.0-h-350m-Q4_0.gguf") valid))
  (ak/defer (inference/unload-model!))
  (debug/assert (inference/initialize-sequences!))
  (ak/defer (inference/free-sequences!))
  (print-reply! "What is 2 + 2? Reply with only the number.")
  (print-reply! "Please list one IBM Research laboratory located in the United States. You should only output its name and location.")
  (print-reply! "You are a racing engineer. A car is stopped ahead and neither side is clear. Should your driver accelerate or brake? Reply briefly."))
