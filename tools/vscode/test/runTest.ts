import * as path from "node:path";
import { runTests } from "@vscode/test-electron";

async function main(): Promise<void> {
  const extensionDevelopmentPath = path.resolve(__dirname, "../..");
  const extensionTestsPath = path.resolve(__dirname, "suite", "index.js");
  const workspacePath = path.resolve(extensionDevelopmentPath, "../..", "test", "fixtures", "vscode_workspace");
  for (const phase of ["reload-start", "reload-connect"] as const) {
    await runTests({
      version: "1.125.0",
      extensionDevelopmentPath,
      extensionTestsPath,
      extensionTestsEnv: { AGUAFRIA_VSCODE_TEST_PHASE: phase },
      launchArgs: [workspacePath, "--disable-extensions"],
    });
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
