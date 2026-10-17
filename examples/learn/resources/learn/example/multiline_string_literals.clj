(ns learn.example.multiline-string-literals
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defconst hello-world-in-c
  (a/multiline-string
   ["#include <stdio.h>"
    ""
    "int main(int argc, char **argv) {"
    "    printf(\"hello world\\n\");"
    "    return 0;"
    "}"]))
