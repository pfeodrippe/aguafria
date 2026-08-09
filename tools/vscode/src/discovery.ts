import * as path from "node:path";
import * as fs from "node:fs/promises";
import { createHash } from "node:crypto";

export type Handshake = {
  protocolVersion: number;
  port: number;
  projectId: string;
  runtimeId: string;
  workspaceRoot: string;
  projectRoot: string;
  jvmPid: number;
  bind: string;
  authenticationRequired: boolean;
  tokenFingerprint: string;
};

export type Discovery = {
  handshake: Handshake;
  token: string;
};

export function parseHandshake(source: string): Handshake {
  const readString = (name: string): string => {
    const match = source.match(new RegExp(`:${name}\\s+"([^"]+)"`));
    if (!match?.[1]) throw new Error(`Aguafria runtime handshake has no :${name}`);
    return match[1];
  };
  const readInteger = (name: string): number => {
    const match = source.match(new RegExp(`:${name}\\s+([0-9]+)`));
    if (!match?.[1]) throw new Error(`Aguafria runtime handshake has no :${name}`);
    const value = Number.parseInt(match[1], 10);
    if (!Number.isSafeInteger(value)) {
      throw new Error(`Aguafria runtime handshake has an invalid :${name}`);
    }
    return value;
  };
  const readBoolean = (name: string): boolean => {
    const match = source.match(new RegExp(`:${name}\\?\\s+(true|false)`));
    if (!match?.[1]) throw new Error(`Aguafria runtime handshake has no :${name}?`);
    return match[1] === "true";
  };
  return {
    protocolVersion: readInteger("protocol-version"),
    port: readInteger("port"),
    projectId: readString("project-id"),
    runtimeId: readString("runtime-id"),
    workspaceRoot: readString("workspace-root"),
    projectRoot: readString("project-root"),
    jvmPid: readInteger("jvm-pid"),
    bind: readString("bind"),
    authenticationRequired: readBoolean("authentication-required"),
    tokenFingerprint: readString("token-fingerprint"),
  };
}

function isWithin(parent: string, candidate: string): boolean {
  const relative = path.relative(parent, candidate);
  return relative === "" || (!path.isAbsolute(relative)
    && relative !== ".."
    && !relative.startsWith(`..${path.sep}`));
}

async function validateOwner(file: string, secret = false): Promise<void> {
  const stats = await fs.stat(file);
  const uid = process.getuid?.();
  if (uid !== undefined && stats.uid !== uid) {
    throw new Error(`Aguafria discovery file is owned by another user: ${file}`);
  }
  if (secret && process.platform !== "win32" && (stats.mode & 0o077) !== 0) {
    throw new Error(`Aguafria editor token permissions are not user-only: ${file}`);
  }
}

function processIsAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

export async function readDiscovery(
  workspaceRoot: string,
  allowExternalProjectRoot = false,
): Promise<Discovery> {
  const root = await fs.realpath(workspaceRoot);
  const portFile = path.join(root, ".nrepl-port");
  const runtimeFile = path.join(root, ".aguafria", "runtime.edn");
  const tokenFile = path.join(root, ".aguafria", "editor-token");
  await Promise.all([
    validateOwner(portFile),
    validateOwner(runtimeFile),
  ]);
  const [portText, runtimeText] = await Promise.all([
    fs.readFile(portFile, "utf8"),
    fs.readFile(runtimeFile, "utf8"),
  ]);
  const handshake = parseHandshake(runtimeText);
  const token = handshake.authenticationRequired
    ? await (async () => {
        await validateOwner(tokenFile, true);
        return fs.readFile(tokenFile, "utf8");
      })()
    : "";
  if (handshake.protocolVersion !== 1) {
    throw new Error(`Unsupported Aguafria discovery protocol ${handshake.protocolVersion}`);
  }
  if (handshake.bind !== "127.0.0.1") {
    throw new Error(`Aguafria discovery is not loopback-only: ${handshake.bind}`);
  }
  if (handshake.port < 1 || handshake.port > 65535) {
    throw new Error(`Aguafria discovery has an invalid port ${handshake.port}`);
  }
  if (!/^[0-9]+$/.test(portText.trim())
      || handshake.port !== Number.parseInt(portText.trim(), 10)) {
    throw new Error(".nrepl-port does not match .aguafria/runtime.edn");
  }
  if (!processIsAlive(handshake.jvmPid)) {
    throw new Error(`Aguafria discovery points to stale JVM pid ${handshake.jvmPid}`);
  }
  const discoveredWorkspace = await fs.realpath(handshake.workspaceRoot);
  if (discoveredWorkspace !== root) {
    throw new Error("Aguafria discovery belongs to a different canonical workspace");
  }
  const projectRoot = await fs.realpath(handshake.projectRoot);
  if (!allowExternalProjectRoot && !isWithin(root, projectRoot)) {
    throw new Error(
      "Aguafria project root escapes the workspace; explicitly enable aguafria.allowExternalProjectRoot",
    );
  }
  const fingerprint = createHash("sha256").update(token, "utf8").digest("hex");
  if (fingerprint !== handshake.tokenFingerprint) {
    throw new Error("Aguafria editor token does not match the runtime handshake");
  }
  return { handshake: { ...handshake, workspaceRoot: root, projectRoot }, token };
}
