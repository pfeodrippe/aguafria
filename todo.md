Clojure library that does something like
https://github.com/pfeodrippe/vybe/blob/main/src/vybe/c.clj, but for zig.

- [x] zig from clojure data structure to create standalone/hot reloadable functions
  - [x] generate ordinary Zig source; support Zig `comptime` without requiring a comptime-only architecture
- [x] the generate keywords scripts should also be able to expose the symbols dynamically for the entire std and nested, so people can just require normally in their normal jvm clojure using :require to use them (or call them from the repl) or check docs or whatever
- [x] ability to convert a zig file into a well-formatted clojure namespace using aguafria api (az/defn, az/defconst, ak/... etc just like a normal user would do)
  - [x] then do a first test using the `sample` folder here
  - [ ] in the generated code, why are we using defimport for std and builtin or anything else ??? We shouldn't need ANY of it if we will import all the dependencies into clojure namespaces
  - [x] for a more complete test, use https://github.com/tigerbeetle/tigerbeetle so we can see the whole being converted into clojure aguafria namespaces and that it works the same (ofc being hot reloadable) (submodule and complete conversion done; Zig 0.16.0 compatibility overlay remains)
- [x] we should have a ak/var or az/var as the `var` alone is very different from the clojure usage
- [x] comments that have multiline, should be multiline in the generated instead of having `\n` e.g. "Encode or decode a bitset using Daniel Lemire's EWAH codec.\n(\"Histogram-Aware Sorting for Enhanced Word-Aligned Compression in Bitmap Indexes\")\n\nEWAH uses only two types of words, where the first type is a 64-bit verbatim (\"literal\") word.\nThe second type of word is a marker word:\n* The first bit indicates which uniform word will follow.\n* The next 31 bits are used to store the number of uniform words.\n* The last 32 bits are used to store the number of literal words following the uniform words.\nEWAH bitmaps begin with a marker word. A 'marker' looks like (assuming a 64-bit word):\n\n    [uniform_bit:u1][uniform_word_count:u31(LE)][literal_word_count:u32(LE)]\n\nand is immediately followed by `literal_word_count` 64-bit literals.\nWhen decoding a marker, the uniform words precede the literal words.\n\nThis encoding requires that the architecture is little-endian with 64-bit words."
- [x] small game using flecs + zig
  - [x] draw circles
  - [x] use https://github.com/erincatto/box3d and https://github.com/mackron/miniaudio (as submodules and inside vendor folder in the simple game folder) for making small circles spheres (in small quantity) as particles leave from the big circles when we click on it (in 3d space, even if we just see 2d for now) and also so we can play some good click sound when clicking on a circle!
  - [x] text
  - [x] audio
- [ ] zig hot reload
- [ ] just like we did with tigerbeetle, do for ghostty https://github.com/ghostty-org/ghostty (into a submodule that we are going to create a branch from) so we can run it here, make sure it works for this version of os mac and that it's working with our zig v0.16.0, then create a examples/ghostty with 1 or nmore ns to run it and all that for generate, test it in both hot reload and standalone

- [ ] use flecs for compilation ? batch all evaluated expressions and such ?

---

## Other

- [ ] use vybe integrated with https://github.com/SanderMertens/biome so we can show hot reload of systems
  - [ ] normal workflow
  - [ ] gemma for creating systems dynamically
