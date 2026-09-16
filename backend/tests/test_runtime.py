"""Exercise runtime failures in isolated subprocesses without service credentials."""

import os
from pathlib import Path
import subprocess
import sys
import unittest


BACKEND = Path(__file__).resolve().parents[1]
PRELUDE = """
import asyncio
import sys
import threading
from unittest.mock import AsyncMock, MagicMock, PropertyMock, patch

# Windows creates a loopback socket pair for the event loop's internal wakeup
# pipe. Create that infrastructure before banning network access by app code.
runner = asyncio.Runner()
runner.get_loop()

def forbid_network(event, args):
    if event in {"socket.connect", "socket.bind", "socket.getaddrinfo"}:
        raise RuntimeError("Runtime test must not access the network")

sys.addaudithook(forbid_network)
import app
"""


class RuntimeTest(unittest.TestCase):
    def run_isolated(self, scenario):
        env = {
            key: value for key, value in os.environ.items()
            if key.upper() in {"PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "LANG", "LC_ALL"}
        }
        env.update({
            "PYTHON_DOTENV_DISABLED": "1",
            "MONGO_URI": "mongodb://127.0.0.1:27017/?connect=false",
        })
        code = PRELUDE + "\ntry:\n" + "\n".join("    " + line for line in scenario.splitlines())
        code += "\nfinally:\n    import database\n    database.client.close()\n    runner.close()\n"
        result = subprocess.run(
            [sys.executable, "-c", code], cwd=BACKEND, env=env,
            capture_output=True, text=True, timeout=25,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_health_handles_discord_latency_before_first_heartbeat(self):
        self.run_isolated("""
client = app.app.test_client()
with patch.object(app.bot, "is_ready", return_value=True):
    for value, expected in [(float('nan'), None), (float('inf'), None),
                            (float('-inf'), None), (-1, None), (None, None), (0.05, 50)]:
        with patch.object(type(app.bot), "latency", new_callable=PropertyMock, return_value=value):
            response = client.get('/api/health')
            assert response.status_code == 200, response.data
            assert response.headers['Cache-Control'] == 'no-store'
            assert client.get('/api/status').json['latencia_ms'] == expected
            assert client.head('/api/health').status_code == 200
with patch.object(app.bot, "is_ready", return_value=False):
    assert client.get('/api/health').status_code == 503
""")

    def test_health_reports_only_valid_commit_and_redacts_startup_error(self):
        self.run_isolated("""
client = app.app.test_client()
secret = 'credential-that-must-not-be-returned'
with patch.object(app.bot, 'last_start_error', secret, create=True), \
     patch.dict(app.os.environ, {'DISCORD_TOKEN': secret, 'RENDER_GIT_COMMIT': 'a' * 40}):
    response = client.get('/api/health')
    assert response.json['git_commit'] == 'a' * 40
    assert secret not in response.get_data(as_text=True)
    assert response.json['erro_inicializacao'] is not None
with patch.dict(app.os.environ, {'RENDER_GIT_COMMIT': secret}):
    assert client.get('/api/health').json['git_commit'] is None
""")

    def test_failed_bind_never_starts_discord(self):
        self.run_isolated("""
async def check():
    with patch.object(app, 'criar_servidor_http', side_effect=OSError('port occupied')), \
         patch.object(app, 'iniciar_bot_supervisionado', new_callable=AsyncMock) as start:
        try:
            await app.main()
        except OSError as error:
            assert str(error) == 'port occupied'
        else:
            raise AssertionError('HTTP bind error was swallowed')
        start.assert_not_awaited()
runner.run(check())
""")

    def test_failed_bind_releases_dispatcher_and_sockets(self):
        self.run_isolated("""
canais = {}
with patch.object(app, 'ThreadedTaskDispatcher') as dispatcher, \
     patch.object(app, 'create_server', side_effect=OSError('port occupied')), \
     patch.object(app.wasyncore, 'close_all') as close:
    try:
        app.criar_servidor_http(5000, canais)
    except OSError:
        pass
    else:
        raise AssertionError('HTTP bind error was swallowed')
    close.assert_called_once_with(canais)
    dispatcher.return_value.shutdown.assert_called_once()
    dispatcher.return_value.set_thread_count.assert_not_called()
""")

    def test_http_failure_cancels_discord_and_watchdog(self):
        self.run_isolated("""
cancelled = []
async def wait_for_shutdown(name):
    try:
        await asyncio.Event().wait()
    finally:
        cancelled.append(name)
async def check():
    with patch.object(app, 'criar_servidor_http'), \
         patch.object(app, 'executar_servidor_http', side_effect=OSError('HTTP failed')), \
         patch.object(app, 'iniciar_bot_supervisionado', new=lambda: wait_for_shutdown('bot')), \
         patch.object(app, 'monitorar_bot_watchdog', new=lambda: wait_for_shutdown('watchdog')):
        try:
            await app.main()
        except OSError as error:
            assert str(error) == 'HTTP failed'
        else:
            raise AssertionError('HTTP runtime failure was swallowed')
    assert sorted(cancelled) == ['bot', 'watchdog'], cancelled
runner.run(check())
""")

    def test_unexpected_http_return_is_failure(self):
        self.run_isolated("""
async def forever():
    await asyncio.Event().wait()
async def check():
    with patch.object(app, 'criar_servidor_http'), \
         patch.object(app, 'executar_servidor_http', return_value=None), \
         patch.object(app, 'iniciar_bot_supervisionado', side_effect=forever), \
         patch.object(app, 'monitorar_bot_watchdog', side_effect=forever):
        try:
            await app.main()
        except RuntimeError as error:
            assert 'inesperadamente' in str(error)
        else:
            raise AssertionError('Unexpected HTTP exit was accepted')
runner.run(check())
""")

    def test_cancellation_stops_http_and_background_tasks(self):
        self.run_isolated("""
started = threading.Event()
stopped = threading.Event()
cancelled = []
def fake_http(server, channels, stop):
    started.set()
    assert stop.wait(5), 'HTTP was not asked to stop'
    stopped.set()
async def forever(name):
    try:
        await asyncio.Event().wait()
    finally:
        cancelled.append(name)
async def check():
    with patch.object(app, 'criar_servidor_http'), \
         patch.object(app, 'executar_servidor_http', side_effect=fake_http), \
         patch.object(app, 'iniciar_bot_supervisionado', new=lambda: forever('bot')), \
         patch.object(app, 'monitorar_bot_watchdog', new=lambda: forever('watchdog')):
        task = asyncio.create_task(app.main())
        while not started.is_set():
            await asyncio.sleep(0.01)
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass
        else:
            raise AssertionError('Shutdown cancellation was swallowed')
        assert stopped.is_set()
        assert sorted(cancelled) == ['bot', 'watchdog'], cancelled
runner.run(check())
""")

    def test_http_loop_closes_resources_after_poll_failure(self):
        self.run_isolated("""
server = MagicMock()
channels = {'connection': object()}
with patch.object(app.wasyncore, 'loop', side_effect=OSError('poll failed')), \
     patch.object(app.wasyncore, 'close_all') as close:
    try:
        app.executar_servidor_http(server, channels, threading.Event())
    except OSError:
        pass
    else:
        raise AssertionError('HTTP poll failure was swallowed')
    close.assert_called_once_with(channels)
    server.task_dispatcher.shutdown.assert_called_once_with(timeout=5)
""")


if __name__ == '__main__':
    unittest.main()
