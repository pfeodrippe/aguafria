import * as assert from "node:assert";
import * as os from "node:os";
import * as path from "node:path";
import * as fs from "node:fs/promises";
import { createHash } from "node:crypto";
import { afterEach, describe, it } from "node:test";
import { parseHandshake, readDiscovery } from "../../src/discovery";

const temporaryRoots: string[] = [];

afterEach(async () => {
  await Promise.all(temporaryRoots.splice(0).map((root) => fs.rm(root, { recursive: true, force: true })));
});

async function fixture(projectRoot?: string, authenticationRequired = true): Promise<string> {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "aguafria-discovery-"));
  temporaryRoots.push(root);
  const token = "test-editor-token";
  const runtimeDirectory = path.join(root, ".aguafria");
  await fs.mkdir(runtimeDirectory);
  const effectiveProjectRoot = projectRoot ?? root;
  const runtime = `{:protocol-version 1\n :project-id "project"\n :runtime-id "runtime"\n :workspace-root ${JSON.stringify(root)}\n :project-root ${JSON.stringify(effectiveProjectRoot)}\n :jvm-pid ${process.pid}\n :port 45678\n :bind "127.0.0.1"\n :authentication-required? ${authenticationRequired}\n :token-fingerprint "${createHash("sha256").update(authenticationRequired ? token : "").digest("hex")}"}\n`;
  await Promise.all([
    fs.writeFile(path.join(root, ".nrepl-port"), "45678"),
    fs.writeFile(path.join(runtimeDirectory, "runtime.edn"), runtime),
    ...(authenticationRequired
      ? [fs.writeFile(path.join(runtimeDirectory, "editor-token"), token, { mode: 0o600 })]
      : []),
  ]);
  return root;
}

describe("runtime discovery", () => {
  it("parses and validates the complete local runtime identity", async () => {
    const root = await fixture();
    const discovered = await readDiscovery(root);
    assert.strictEqual(discovered.handshake.projectId, "project");
    assert.strictEqual(discovered.handshake.protocolVersion, 1);
    assert.strictEqual(discovered.handshake.jvmPid, process.pid);
    assert.strictEqual(discovered.handshake.authenticationRequired, true);
    assert.strictEqual(discovered.token, "test-editor-token");
  });

  it("supports ordinary loopback nREPL discovery without token authentication", async () => {
    const root = await fixture(undefined, false);
    const discovered = await readDiscovery(root);
    assert.strictEqual(discovered.handshake.authenticationRequired, false);
    assert.strictEqual(discovered.token, "");
  });

  it("rejects a token that does not match the authenticated handshake", async () => {
    const root = await fixture();
    await fs.writeFile(path.join(root, ".aguafria", "editor-token"), "replaced", { mode: 0o600 });
    await assert.rejects(() => readDiscovery(root), /does not match/);
  });

  it("requires explicit approval for a project root outside the workspace", async () => {
    const external = await fs.mkdtemp(path.join(os.tmpdir(), "aguafria-external-"));
    temporaryRoots.push(external);
    const root = await fixture(external);
    await assert.rejects(() => readDiscovery(root), /escapes the workspace/);
    assert.strictEqual((await readDiscovery(root, true)).handshake.projectRoot, await fs.realpath(external));
  });

  it("requires every identity field", () => {
    assert.throws(() => parseHandshake("{:protocol-version 1}"), /has no :port/);
  });
});
