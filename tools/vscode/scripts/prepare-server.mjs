import { cp, mkdir, rm } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const extensionRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = path.resolve(extensionRoot, "../..");
const destination = path.join(extensionRoot, "dist", "server");

await rm(destination, { recursive: true, force: true });
await mkdir(destination, { recursive: true });
await cp(path.join(repositoryRoot, "src"), path.join(destination, "src"), {
  recursive: true,
});
await cp(path.join(repositoryRoot, "resources"), path.join(destination, "resources"), {
  recursive: true,
});
