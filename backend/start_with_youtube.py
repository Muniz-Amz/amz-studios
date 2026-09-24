"""Opt in to the local PO-token provider without exposing another HTTP service."""
import os
import sys
from pathlib import Path


def main():
    root = Path(__file__).resolve().parent
    server = root / ".youtube-pot-provider" / "server"
    if not (server / "build" / "generate_once.js").is_file():
        raise RuntimeError("Run build_youtube.py before enabling the optional YouTube provider.")
    os.environ["AMZ_YOUTUBE_POT_SERVER_HOME"] = str(server)
    print("YouTube PO-token provider enabled (local script, no account cookies required).", flush=True)
    os.chdir(root)
    os.execv(sys.executable, [sys.executable, str(root / "app.py")])


if __name__ == "__main__":
    main()
