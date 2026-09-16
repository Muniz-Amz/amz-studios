"""Offline checks for admission, cleanup and conversion of generated audio."""

import io
import os
from pathlib import Path
import shutil
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import wave

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import app as api
import mp3_service as service_module


class ServiceTest(unittest.TestCase):
    def setUp(self):
        self.network = patch("socket.socket.connect", side_effect=AssertionError("Network forbidden in tests"))
        self.network.start()
        self.addCleanup(self.network.stop)
        self.client = api.app.test_client()
        with api.JOBS_LOCK:
            api.JOBS.clear()
            api.REQUEST_LOG.clear()

    def tearDown(self):
        with api.JOBS_LOCK:
            directories = [job.get("temp_dir") for job in api.JOBS.values()]
            api.JOBS.clear()
            api.REQUEST_LOG.clear()
        for directory in directories:
            if directory:
                shutil.rmtree(directory, ignore_errors=True)

    def test_health_and_status(self):
        for route in ("/health", "/", "/api/mp3/status"):
            with self.subTest(route=route):
                self.assertEqual(self.client.get(route).status_code, 200)
        self.assertEqual(self.client.get("/api/mp3/status").json["queue"]["running"], 0)

    def test_invalid_requests_do_not_start_jobs(self):
        with patch.object(api.EXECUTOR, "submit") as submit:
            for url in ("", "file:///etc/passwd", "http://127.0.0.1/", "https://example.com/file"):
                with self.subTest(url=url):
                    self.assertEqual(self.client.post("/api/mp3/jobs", json={"url": url}).status_code, 400)
            self.assertEqual(self.client.post("/api/mp3/uploads").status_code, 415)
            self.assertEqual(self.client.post("/api/mp3/uploads", content_type="multipart/form-data").status_code, 400)
            submit.assert_not_called()

    def test_full_queue_rejects_work_and_keeps_health_available(self):
        with api.JOBS_LOCK:
            for index in range(api.MAX_QUEUE_SIZE):
                api.JOBS[str(index)] = {"id": str(index), "status": "running", "created_at": time.time()}
        with patch.object(api.EXECUTOR, "submit") as submit:
            response = self.client.post("/api/mp3/jobs", json={"url": "https://www.youtube.com/watch?v=BaW_jenozKc"})
            self.assertEqual(response.status_code, 429)
            submit.assert_not_called()
        self.assertEqual(self.client.get("/health").status_code, 200)

    def test_repeated_request_reuses_queued_job(self):
        with patch.object(api.EXECUTOR, "submit") as submit:
            payload = {"url": "https://www.youtube.com/watch?v=BaW_jenozKc"}
            first = self.client.post("/api/mp3/jobs", json=payload)
            second = self.client.post("/api/mp3/jobs", json=payload)
            self.assertEqual(first.status_code, 202)
            self.assertEqual(second.status_code, 202)
            self.assertEqual(first.json["job"]["id"], second.json["job"]["id"])
            self.assertTrue(second.json["reutilizado"])
            submit.assert_called_once()

    def test_cleanup_preserves_running_files_and_expires_finished_files(self):
        with tempfile.TemporaryDirectory(prefix="amz-mp3-test-") as directory:
            running = Path(directory, "running")
            finished = Path(directory, "finished")
            running.mkdir()
            finished.mkdir()
            old = time.time() - api.JOB_TTL_SECONDS - 100
            with api.JOBS_LOCK:
                api.JOBS["running"] = {"status": "running", "updated_at": old, "temp_dir": str(running)}
                api.JOBS["finished"] = {"status": "done", "updated_at": old, "temp_dir": str(finished)}
            api._cleanup_old_jobs()
            self.assertTrue(running.is_dir())
            self.assertIsNotNone(api._get_job("running"))
            self.assertFalse(finished.exists())
            self.assertIsNone(api._get_job("finished"))

    def test_generated_wav_converts_and_downloads(self):
        probe = api.mp3_service.ffprobe
        if not (Path(probe).is_file() or shutil.which(probe)):
            self.skipTest("ffprobe is required; installed in the Docker build and Linux CI")
        audio = io.BytesIO()
        with wave.open(audio, "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(16000)
            wav.writeframes(b"\x00\x00" * 16000)
        audio.seek(0)
        created = self.client.post("/api/mp3/uploads", data={"file": (audio, "generated.wav", "audio/wav")})
        self.assertEqual(created.status_code, 202, created.json)
        job_id = created.json["job"]["id"]
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            job = self.client.get(f"/api/mp3/jobs/{job_id}").json["job"]
            if job["status"] in {"done", "error"}:
                break
            time.sleep(0.05)
        self.assertEqual(job["status"], "done", job)
        with self.client.get(f"/api/mp3/jobs/{job_id}/download") as result:
            self.assertEqual(result.status_code, 200)
            self.assertEqual(result.mimetype, "audio/mpeg")
            self.assertGreater(len(result.data), 100)
            self.assertEqual(result.headers["Cache-Control"], "no-store")

    def test_permanent_ytdlp_error_is_not_retried(self):
        service = service_module.Mp3DownloadService(service_module.Mp3Limits(retries=3))
        calls = []

        class PermanentFailure:
            def __init__(self, *_args, **_kwargs):
                pass

            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def extract_info(self, _url, download=True):
                calls.append(download)
                raise RuntimeError("Sign in to confirm you're not a bot")

        with patch.object(service_module, "YoutubeDL", PermanentFailure), patch.object(service_module.time, "sleep") as sleep:
            with self.assertRaises(service_module.Mp3DownloadError):
                service._run_ytdlp({}, "https://www.youtube.com/watch?v=test", None)

        self.assertEqual(calls, [True])
        sleep.assert_not_called()

    def test_temporary_ytdlp_error_is_retried_once(self):
        service = service_module.Mp3DownloadService(service_module.Mp3Limits(retries=2))
        calls = []

        class TemporaryThenSuccess:
            def __init__(self, *_args, **_kwargs):
                pass

            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def extract_info(self, _url, download=True):
                calls.append(download)
                if len(calls) == 1:
                    raise RuntimeError("connection reset by peer")
                return {"id": "ok"}

        with patch.object(service_module, "YoutubeDL", TemporaryThenSuccess), patch.object(service_module.time, "sleep") as sleep:
            service._run_ytdlp({}, "https://www.youtube.com/watch?v=test", None)

        self.assertEqual(calls, [True, True])
        sleep.assert_called_once()

    def test_youtube_host_and_impersonation_option_are_normalized(self):
        options = service_module.Mp3DownloadService._platform_options("https://youtube.com/watch?v=test")
        self.assertIn("youtube.com", options["http_headers"]["Referer"])

        with patch.dict(os.environ, {"AMZ_MP3_YOUTUBE_IMPERSONATE": "chrome-136:macos-15"}, clear=False):
            configured = service_module.Mp3DownloadService._platform_options("https://youtube.com/watch?v=test")
        self.assertEqual(type(configured["impersonate"]).__name__, "ImpersonateTarget")


if __name__ == "__main__":
    unittest.main()
