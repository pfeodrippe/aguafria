(ns learn.example.string-literals
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.mem :as mem] ; will be used to compare bytes
            [aguafria.zig :as a]))

(a/defn main :void
  []
  (let [bytes "hello"]
    (debug/print "{}\n" [(k/TypeOf bytes)]) ; *const [5:0]u8
    (debug/print "{d}\n" [(:len bytes)]) ; 5
    (debug/print "{c}\n" [(a/get bytes 1)]) ; 'e'
    (debug/print "{d}\n" [(a/get bytes 5)]) ; 0
    (debug/print "{}\n" [(k/== \e (a/char-literal "'\\x65'"))]) ; true
    (debug/print "{d}\n" [(a/char-literal "'\\u{1f4a9}'")]) ; 128169
    (debug/print "{d}\n" [(a/char-literal "'💯'")]) ; 128175
    (debug/print "{u}\n" [\⚡])
    (debug/print "{}\n"
                 [(mem/eql :u8 "hello" (a/string-literal "\"h\\x65llo\""))]) ; true
    (debug/print "{}\n"
                 [(mem/eql :u8 "💯" (a/string-literal "\"\\xf0\\x9f\\x92\\xaf\""))]) ; also true
    (let [invalid-utf8 (a/string-literal "\"\\xff\\xfe\"")] ; non-UTF-8 strings are possible with \xNN notation.
      (debug/print "0x{x}\n" [(a/get invalid-utf8 1)]) ; indexing them returns individual bytes...
      (debug/print "0x{x}\n" [(a/get "💯" 1)])))) ; ...as does indexing part-way through non-ASCII characters

(comment
  (main))
