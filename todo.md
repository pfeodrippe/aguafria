Clojure library that does something like
https://github.com/pfeodrippe/vybe/blob/main/src/vybe/c.clj, but for zig.

- [x] zig from clojure data structure to create standalone/hot reloadable functions
  - [x] generate ordinary Zig source; support Zig `comptime` without requiring a comptime-only architecture
- [x] the generate keywords scripts should also be able to expose the symbols dynamically for the entire std and nested, so people can just require normally in their normal jvm clojure using :require to use them (or call them from the repl) or check docs or whatever
- [x] ability to convert a zig file into a well-formatted clojure namespace using aguafria api (az/defn, az/defconst, ak/... etc just like a normal user would do)
  - [x] then do a first test using the `sample` folder here
  - [ ] in the generated code, why are we using defimport for std and builtin or anything else ??? We shouldn't need ANY of it if we will import all the dependencies into clojure namespaces
  - [ ] for a more complete test, use https://github.com/tigerbeetle/tigerbeetle so we can see the whole being converted into clojure aguafria namespaces and that it works the same (ofc being hot reloadable) (submodule and complete conversion done; Zig 0.16.0 compatibility overlay remains)
- [ ] use flecs for compilation ? batch all evaluated expressions and such ?
- [ ] small game using flecs + zig
  - [ ] draw circles
  - [ ] text
  - [ ] audio
- [ ] zig comptime that read edn and parses it into code
- [ ] mechanically convert flecs c to aguafria just like we did to convert zig files
