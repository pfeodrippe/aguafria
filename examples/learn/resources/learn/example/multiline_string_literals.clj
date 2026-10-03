(ns learn.example.multiline-string-literals
  (:require [aguafria.zig :as a]))

(a/defconst hello-world-in-c
  (a/multiline-string
   ["#include <stdio.h>"
    ""
    "int main(int argc, char **argv) {"
    "    printf(\"hello world\\n\");"
    "    return 0;"
    "}"]))
