Clojure library that does something like
https://github.com/pfeodrippe/vybe/blob/main/src/vybe/c.clj, but for zig.

- [x] zig from clojure data structure to create standalone/hot reloadable functions
  - [x] generate ordinary Zig source; support Zig `comptime` without requiring a comptime-only architecture
- [ ] ability to convert a zig file into a well-formatted clojure namespace using aguafria api (az/defn, az/defconst, ak/... etc just like a normal user would do)
  - [ ] then do a first test using the `sample` folder here
  - [ ] for a more complete test, use https://github.com/tigerbeetle/tigerbeetle so we can see the whole being converted into clojure aguafria namespaces and that it works the same (ofc being hot reloadable) (add it as a submodule to the `vendor` folder and make it work with the zig we have (0.16.0))
- [ ] use flecs for compilation ? batch all evaluated expressions and such ?
- [ ] small game using flecs + zig
  - [ ] draw circles
  - [ ] text
  - [ ] audio
- [ ] zig comptime that read edn and parses it into code
