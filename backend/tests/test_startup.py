"""Check startup imports and HTTP health without connecting to external services."""

import os
from pathlib import Path
import subprocess
import sys
import unittest


BACKEND = Path(__file__).resolve().parents[1]


class StartupTest(unittest.TestCase):
    def test_startup_and_health_without_credentials(self):
        # Keep OS runtime settings only; never load local or CI service credentials.
        env = {
            key: value
            for key, value in os.environ.items()
            if key.upper() in {"PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "LANG", "LC_ALL"}
        }
        env.update({
            "PYTHON_DOTENV_DISABLED": "1",
            "MONGO_URI": "mongodb://127.0.0.1:27017/?connect=false",
        })
        result = subprocess.run(
            [sys.executable, "-c", """
import importlib
import sys
from unittest.mock import PropertyMock, patch

def forbid_network(event, args):
    if event in {"socket.connect", "socket.bind", "socket.getaddrinfo"}:
        raise RuntimeError("Startup smoke test must not access the network")

sys.addaudithook(forbid_network)

import app
from bot import EXTENSIONS
from PIL import Image, ImageStat

try:
    for extension in EXTENSIONS:
        importlib.import_module(extension)

    with Image.new("RGB", (2, 2), (10, 20, 30)) as picture:
        assert ImageStat.Stat(picture).mean == [10, 20, 30]

    client = app.app.test_client()
    assert client.get("/").status_code == 200
    offline = client.get("/api/health")
    assert offline.status_code == 503
    assert offline.json["bot"] == "offline"
    with patch.object(app.bot, "is_ready", return_value=True), \
         patch.object(type(app.bot), "latency", new_callable=PropertyMock, return_value=0.05):
        online = client.get("/api/health")
        assert online.status_code == 200
        assert online.json["bot"] == "online"
    print(f"Startup OK: API, {len(EXTENSIONS)} bot extensions, Pillow and health responses")
finally:
    import database
    database.client.close()
"""],
            cwd=BACKEND,
            env=env,
            capture_output=True,
            text=True,
            timeout=60,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
