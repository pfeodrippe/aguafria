Clojure library that does something like
https://github.com/pfeodrippe/vybe/blob/main/src/vybe/c.clj, but for zig.

- [ ] zig comptime that read edn and parses it into code
- [x] zig from clojure data structure to create standalone/hot reloadable functions
  - [x] generate ordinary Zig source; support Zig `comptime` without requiring a comptime-only architecture
- [ ] use flecs for compilation ? batch all evaluated expressions and such ?
- [ ] small game using flecs + zig
  - [ ] draw circles
  - [ ] text
  - [ ] audio
