"""Optional Render build: install a pinned, local YouTube PO-token provider."""
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PROVIDER = ROOT / ".youtube-pot-provider"
VERSION = "2.0.0"
COMMIT = "37169ee2656e08c5c2e5dc9df4c598c0cb4c88a8"
REPOSITORY = "https://github.com/Brainicism/bgutil-ytdlp-pot-provider.git"


def run(*command, cwd=ROOT):
    subprocess.run(command, cwd=cwd, check=True, timeout=600)


def main():
    node = shutil.which("node")
    npm = shutil.which("npm")
    if not node or not npm:
        raise RuntimeError("The optional YouTube provider needs Node.js and npm.")
    version = subprocess.check_output([node, "--version"], text=True).strip()
    major, minor, *_ = map(int, version.lstrip("v").split("."))
    if not ((major == 22 and minor >= 13) or major >= 24):
        raise RuntimeError("The pinned provider needs Node.js 22.13+ LTS or Node.js 24+.")
    print(f"Preparing optional YouTube provider {VERSION} with Node {version}", flush=True)
    run(sys.executable, "-m", "pip", "install", "-r", "requirements.txt")
    run(sys.executable, "-m", "pip", "install", f"bgutil-ytdlp-pot-provider=={VERSION}")
    # Render can restore the provider's files from a previous build without
    # restoring its nested .git directory. Treat that cache as incomplete so
    # the pinned checkout is recreated instead of resolving the parent repo.
    if PROVIDER.exists() and not (PROVIDER / ".git").is_dir():
        shutil.rmtree(PROVIDER)
    if PROVIDER.exists():
        cached = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=PROVIDER, text=True).strip()
        if cached != COMMIT:
            shutil.rmtree(PROVIDER)
    if not PROVIDER.exists():
        run("git", "clone", "--depth", "1", "--branch", VERSION, REPOSITORY, str(PROVIDER))
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=PROVIDER, text=True).strip()
    if actual != COMMIT:
        raise RuntimeError("The optional provider checkout does not match the pinned revision.")
    server = PROVIDER / "server"
    run(npm, "ci", "--no-audit", "--no-fund", cwd=server)
    run(node, str(server / "node_modules/typescript/bin/tsc"), cwd=server)
    run(node, str(server / "build/generate_once.js"), "--version", cwd=server)
    run(sys.executable, "-m", "pip", "check")


if __name__ == "__main__":
    main()
