// Node's built-in WASI preview1 host; no npm dependencies.
// node --no-warnings wasi.cjs [--dir=.] module.wasm [program arguments...]
// Also usable with node --no-warnings -e <this source> <arguments...>.
const fs = require('node:fs');
const path = require('node:path');
const { WASI } = require('node:wasi');
const args = process.argv.slice(process.argv[1] === __filename ? 2 : 1);
const preopens = {};
if (args[0] === '--dir=.') {
  args.shift();
  preopens['.'] = process.cwd();
}
const modulePath = args.shift();
if (!modulePath) throw new Error('Expected a WebAssembly module path');
const wasi = new WASI({
  version: 'preview1',
  args: [path.basename(modulePath), ...args],
  env: {},
  preopens,
  returnOnExit: true
});
WebAssembly.compile(fs.readFileSync(modulePath))
  .then(module => WebAssembly.instantiate(module, wasi.getImportObject()))
  .then(instance => { process.exitCode = wasi.start(instance); })
  .catch(error => {
    console.error(error);
    process.exitCode = 1;
  });
