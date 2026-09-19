(ns learn.example.string-literals
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.std.mem :as mem] ; used to compare bytes
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [bytes "hello"]
    (debug/print "{}\n" [(ak/TypeOf bytes)]) ; *const [5:0]u8
    (debug/print "{d}\n" [(az/field bytes :len)]) ; 5
    (debug/print "{c}\n" [(az/index bytes 1)]) ; e
    (debug/print "{d}\n" [(az/index bytes 5)]) ; 0
    (debug/print "{}\n" [(== \e (az/char-literal "'\\x65'"))]) ; true
    (debug/print "{d}\n" [(az/char-literal "'\\u{1f4a9}'")]) ; 128169
    (debug/print "{d}\n" [(az/char-literal "'💯'")]) ; 128175
    (debug/print "{u}\n" [\⚡])
    (debug/print "{}\n"
      [(mem/eql :u8 "hello" (az/string-literal "\"h\\x65llo\""))]) ; true
    (debug/print "{}\n"
      [(mem/eql :u8 "💯" (az/string-literal "\"\\xf0\\x9f\\x92\\xaf\""))]) ; also true
    ;; Non-UTF-8 strings are possible with Zig's \xNN notation.
    (let [invalid-utf8 (az/string-literal "\"\\xff\\xfe\"")]
      ;; Indexing returns individual bytes...
      (debug/print "0x{x}\n" [(az/index invalid-utf8 1)])
      ;; ...including part-way through a non-ASCII character.
      (debug/print "0x{x}\n" [(az/index "💯" 1)]))))
