"""Offline runtime checks: no extraction, conversion or external requests."""
import importlib.util
import sys
import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location("hf_runtime_app", ROOT / "app.py")
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        api.app.config["TESTING"] = True
        api.JOBS.clear()
        self.directory = tempfile.TemporaryDirectory(prefix="amz-api-test-")
        self.addCleanup(self.directory.cleanup)
        self.executor = Mock()
        self.executor_patch = patch.object(api, "VIDEO_EXECUTOR", self.executor)
        self.executor_patch.start()
        self.addCleanup(self.executor_patch.stop)
        self.client = api.app.test_client()

    def post_job(self, url="https://example.com/audio"):
        with api.app.test_client() as client:
            return client.post("/api/video/jobs", json={"url": url})

    def stored_job(self, job_id="ready", status="done", age=0):
        directory = Path(self.directory.name) / job_id
        directory.mkdir()
        output = directory / "audio.mp3"
        output.write_bytes(b"test mp3 bytes" * 8192)
        api.JOBS[job_id] = {
            "id": job_id, "status": status, "temp_dir": str(directory),
            "output_path": str(output), "criado_em_ts": 1,
            "atualizado_em_ts": time.time() - age,
        }
        return output

    def test_health_get_head_and_no_external_work(self):
        with patch.object(api.video_service, "analisar_url") as check, patch.object(
            api.video_service, "download_audio"
        ) as download:
            response = self.client.get("/api/health")
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json["status"], "ok")
            self.assertEqual(response.headers["Cache-Control"], "no-store")
            self.assertEqual(self.client.head("/api/health").data, b"")
            check.assert_not_called()
            download.assert_not_called()

    def test_check_does_not_block_health_and_concurrent_check_is_rejected(self):
        started, release = threading.Event(), threading.Event()

        def slow_check(_url):
            started.set()
            if not release.wait(5):
                raise RuntimeError("test did not release blocked check")
            return {"permitido": True}

        def request_check():
            with api.app.test_client() as client:
                return client.post("/api/video/check", json={"url": "https://example.com/a"})

        with patch.object(api.video_service, "analisar_url", side_effect=slow_check), ThreadPoolExecutor(1) as pool:
            future = pool.submit(request_check)
            try:
                self.assertTrue(started.wait(2))
                self.assertEqual(self.client.get("/api/health").status_code, 200)
                rejected = request_check()
                self.assertEqual(rejected.status_code, 503)
                self.assertEqual(rejected.headers["Retry-After"], "15")
            finally:
                release.set()
            self.assertEqual(future.result(timeout=2).status_code, 200)

    def test_check_slot_released_after_error(self):
        with patch.object(api.video_service, "analisar_url", side_effect=api.UrlVideoError("invalid")):
            self.assertEqual(self.client.post("/api/video/check", json={"url": "x"}).status_code, 400)
        self.assertTrue(api.CHECK_SLOT.acquire(blocking=False))
        api.CHECK_SLOT.release()

    def test_queue_is_bounded_atomically(self):
        with ThreadPoolExecutor(8) as pool:
            responses = list(pool.map(self.post_job, [f"https://example.com/{i}" for i in range(12)]))
        self.assertEqual(sum(response.status_code == 202 for response in responses), api.MAX_ACTIVE_JOBS)
        self.assertEqual(sum(response.status_code == 503 for response in responses), 12 - api.MAX_ACTIVE_JOBS)
        self.assertEqual(self.executor.submit.call_count, api.MAX_ACTIVE_JOBS)

    def test_simultaneous_identical_jobs_are_reused(self):
        with ThreadPoolExecutor(8) as pool:
            responses = list(pool.map(lambda _: self.post_job(), range(12)))
        self.assertEqual({response.status_code for response in responses}, {202})
        self.assertEqual(len({response.json["job"]["id"] for response in responses}), 1)
        self.executor.submit.assert_called_once()

    def test_retained_results_are_bounded_without_evicting_valid_files(self):
        with patch.object(api, "MAX_RETAINED_JOBS", 2):
            first = self.stored_job("first")
            second = self.stored_job("second")
            self.assertEqual(self.post_job().status_code, 503)
            self.assertTrue(first.exists())
            self.assertTrue(second.exists())

    def test_executor_failure_rolls_back_reserved_job(self):
        self.executor.submit.side_effect = RuntimeError("shutting down")
        self.assertEqual(self.post_job().status_code, 503)
        self.assertEqual(api.JOBS, {})

    def test_cleanup_keeps_active_jobs_and_recently_completed_files(self):
        old = api.JOB_TTL_SECONDS + 60
        paths = {state: self.stored_job(state, state, old) for state in ("queued", "running", "done", "error")}
        recent = self.stored_job("recent", "done")
        api.limpar_jobs_antigos()
        self.assertEqual(set(api.JOBS), {"queued", "running", "recent"})
        self.assertTrue(paths["queued"].exists())
        self.assertTrue(paths["running"].exists())
        self.assertTrue(recent.exists())
        self.assertFalse(paths["done"].exists())
        self.assertFalse(paths["error"].exists())

    def test_streamed_download_is_pinned_until_response_closes(self):
        output = self.stored_job()
        response = self.client.get("/api/video/jobs/ready/download", buffered=False)
        try:
            self.assertEqual(response.status_code, 200)
            self.assertEqual(api.JOBS["ready"]["downloads_ativos"], 1)
            api.JOBS["ready"]["atualizado_em_ts"] = 1
            api.limpar_jobs_antigos()
            self.assertTrue(output.exists())
            self.assertEqual(b"".join(response.response), b"test mp3 bytes" * 8192)
        finally:
            response.close()
        self.assertEqual(api.JOBS["ready"]["downloads_ativos"], 0)
        api.limpar_jobs_antigos()
        self.assertFalse(output.exists())

    def test_download_range_keeps_existing_contract(self):
        self.stored_job()
        response = self.client.get("/api/video/jobs/ready/download", headers={"Range": "bytes=0-3"})
        try:
            self.assertEqual(response.status_code, 206)
            self.assertEqual(response.data, b"test")
        finally:
            response.close()
        self.assertEqual(api.JOBS["ready"]["downloads_ativos"], 0)

    def test_legacy_streams_file_and_removes_it_after_close(self):
        generated = []

        def create_audio(_url, directory, **_kwargs):
            output = Path(directory) / "audio.mp3"
            output.write_bytes(b"mp3 data" * 8192)
            generated.append(output)
            return output

        with patch.object(api.video_service, "download_audio", side_effect=create_audio), patch.object(
            Path, "read_bytes", side_effect=AssertionError("must stream, not load full output")
        ):
            response = self.client.post("/api/video/download", json={"url": "https://example.com/a"}, buffered=False)
            try:
                self.assertEqual(response.status_code, 200)
                self.assertTrue(generated[0].exists())
                self.assertEqual(b"".join(response.response), b"mp3 data" * 8192)
            finally:
                response.close()
        self.assertFalse(generated[0].parent.exists())

    def test_legacy_uses_same_conversion_slot_and_releases_after_error(self):
        api.CONVERSION_SLOT.acquire()
        try:
            self.assertEqual(self.client.post("/api/video/download", json={"url": "x"}).status_code, 503)
            self.assertEqual(self.client.get("/api/health").status_code, 200)
        finally:
            api.CONVERSION_SLOT.release()
        with patch.object(api.video_service, "download_audio", side_effect=api.UrlVideoError("invalid")):
            self.assertEqual(self.client.post("/api/video/download", json={"url": "x"}).status_code, 400)
        self.assertTrue(api.CONVERSION_SLOT.acquire(blocking=False))
        api.CONVERSION_SLOT.release()

    def test_job_disk_error_becomes_terminal_and_releases_conversion(self):
        job_id = self.post_job().json["job"]["id"]
        with patch.object(api.tempfile, "mkdtemp", side_effect=OSError("disk full")):
            api.processar_job_video(job_id, "https://example.com/a", "mp3")
        self.assertEqual(api.JOBS[job_id]["status"], "error")
        self.assertTrue(api.CONVERSION_SLOT.acquire(blocking=False))
        api.CONVERSION_SLOT.release()

    def test_request_limits_and_invalid_json_do_not_start_jobs(self):
        for route in ("check", "jobs", "download"):
            with self.subTest(route=route):
                self.assertEqual(self.client.post(f"/api/video/{route}", json=["url"]).status_code, 400)
                self.assertEqual(self.client.post(f"/api/video/{route}", json={"url": "x" * 17000}).status_code, 413)
        self.executor.submit.assert_not_called()


if __name__ == "__main__":
    unittest.main()
