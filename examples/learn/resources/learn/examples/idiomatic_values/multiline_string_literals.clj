(ns learn.examples.idiomatic-values.multiline-string-literals
  "Converted from multiline_string_literals.zig"
  (:require [aguafria.zig :as az]))

(az/defconst hello-world-in-c
  (az/multiline-string
    ["#include <stdio.h>"
     ""
     "int main(int argc, char **argv) {"
     "    printf(\"hello world\\n\");"
     "    return 0;"
     "}"]))
