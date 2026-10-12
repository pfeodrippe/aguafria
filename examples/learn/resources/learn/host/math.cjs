// The upstream WebAssembly host, with the module path supplied by the caller.
// Run as: node math.cjs /path/to/math.wasm
// Or: node -e "$(cat math.cjs)" /path/to/math.wasm
const fs = require('node:fs');
const modulePath = process.argv[1].endsWith('.cjs') ? process.argv[2] : process.argv[1];
const source = fs.readFileSync(modulePath);
const typedArray = new Uint8Array(source);

WebAssembly.instantiate(typedArray, {
  env: {
    print: (result) => { console.log(`The result is ${result}`); }
  }
}).then(result => {
  const add = result.instance.exports.add;
  add(1, 2);
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});
