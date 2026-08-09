import * as assert from "node:assert";
import { describe, it } from "node:test";
import { decodeOne, encode } from "../../src/protocol";

describe("bencode protocol", () => {
  it("round-trips nested nREPL messages and UTF-8 Zig source", () => {
    const message = {
      op: "aguafria/eval-zig",
      source: "pub const café = \"ação\";",
      version: 42,
      position: { line: 3, character: 7 },
      status: ["queued", "done"],
    };
    const encoded = encode(message);
    const decoded = decodeOne(encoded);
    assert.ok(decoded);
    assert.strictEqual(decoded.bytes, encoded.length);
    assert.deepStrictEqual(decoded.value, message);
  });

  it("waits for a complete streaming value", () => {
    const encoded = encode({ id: "one", value: "hello" });
    assert.strictEqual(decodeOne(encoded.subarray(0, encoded.length - 1)), undefined);
    assert.deepStrictEqual(decodeOne(encoded)?.value, { id: "one", value: "hello" });
  });

  it("sorts dictionary keys as required by bencode", () => {
    assert.strictEqual(encode({ z: 1, a: 2 }).toString("utf8"), "d1:ai2e1:zi1ee");
  });
});
