# Prism 1.30.0

Pinned, unmodified core and Clojure grammar from the upstream release:
https://github.com/PrismJS/prism/tree/v1.30.0

- `components/prism-core.min.js`
- `components/prism-clojure.js`
- `LICENSE` (MIT, retained here)

The offline HTML embeds these two scripts. Automatic highlighting is disabled;
only Aguafria source is tokenized. Our small metadata/character-literal extensions
live in `reference.js`, not in the vendor files. Text-node rendering preserves
the exact source for copying and does not execute Clojure or interpret it as HTML.
