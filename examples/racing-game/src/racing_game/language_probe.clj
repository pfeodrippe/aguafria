(ns racing-game.language-probe
  "JVM-free diagnostic for the same native text inference used during QA.
  Run from the racing-game project directory; no action heads or game world."
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]
            [aguafria.std.debug :as debug]
            [racing-game.inference :as inference]))

(az/defn print-reply!
  :- :void [[prompt [:slice-const :u8]]]
  (let [result (inference/generate-language! 0 (az/field prompt ptr) (az/field prompt len) 24)]
    (debug/print "prompt: {s}\nvalid={} stop={d} input={d} output={d}\nreply: {s}\n"
      [prompt (az/field result valid) (az/field result stop)
       (az/field result input_tokens) (az/field result output_tokens)
       (az/slice (az/field result bytes) 0 (az/field result byte_count))])))

(az/defn main :- :void []
  (debug/assert (az/field (inference/load-model! "resources/models/granite-4.0-h-350m-Q4_0.gguf") valid))
  (ak/defer (inference/unload-model!))
  (debug/assert (inference/initialize-sequences!))
  (ak/defer (inference/free-sequences!))
  (print-reply! "What is 2 + 2? Reply with only the number.")
  (print-reply! "Please list one IBM Research laboratory located in the United States. You should only output its name and location.")
  (print-reply! "You are a racing engineer. A car is stopped ahead and neither side is clear. Should your driver accelerate or brake? Reply briefly."))
