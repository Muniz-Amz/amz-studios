"""Offline checks for yt-dlp options and retry behavior used by the bot."""

from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import services.url_video_service as service_module


class UrlVideoServiceTest(unittest.TestCase):
    def setUp(self):
        pot_environment = patch.dict(os.environ, {"AMZ_YOUTUBE_POT_SERVER_HOME": ""})
        pot_environment.start()
        self.addCleanup(pot_environment.stop)

    def make_service(self, retries=2):
        service = service_module.UrlVideoService.__new__(service_module.UrlVideoService)
        service.limits = type(
            "Limits",
            (),
            {"retries": retries, "timeout_seconds": 10},
        )()
        return service

    def test_youtube_and_tiktok_options_include_platform_headers(self):
        service = self.make_service()
        with patch.dict(
            os.environ,
            {
                "AMZ_YTDLP_IMPERSONATE": "",
                "AMZ_URLVIDEO_YOUTUBE_IMPERSONATE": "",
                "AMZ_URLVIDEO_TIKTOK_IMPERSONATE": "",
                "AMZ_YTDLP_YOUTUBE_IMPERSONATE": "",
                "AMZ_YTDLP_TIKTOK_IMPERSONATE": "",
            },
            clear=False,
        ):
            youtube = service._opcoes_plataforma("https://youtube.com/watch?v=test")
            tiktok = service._opcoes_plataforma("https://www.tiktok.com/@amz/video/1")

        self.assertIn("youtube.com", youtube["http_headers"]["Referer"])
        self.assertIn("tiktok.com", tiktok["http_headers"]["Referer"])
        self.assertNotIn("impersonate", youtube)
        self.assertNotIn("impersonate", tiktok)

    def test_impersonation_is_converted_to_yt_dlp_target(self):
        service = self.make_service()
        with patch.dict(
            os.environ,
            {
                "AMZ_YTDLP_IMPERSONATE": "",
                "AMZ_URLVIDEO_YOUTUBE_IMPERSONATE": "chrome-136:macos-15",
            },
            clear=False,
        ):
            options = service._opcoes_plataforma("https://youtu.be/test")

        self.assertEqual(type(options["impersonate"]).__name__, "ImpersonateTarget")

        with patch.dict(
            os.environ,
            {
                "AMZ_YTDLP_IMPERSONATE": "",
                "AMZ_URLVIDEO_YOUTUBE_IMPERSONATE": "",
                "AMZ_YTDLP_YOUTUBE_IMPERSONATE": "chrome-136:macos-15",
            },
            clear=False,
        ):
            alias_options = service._opcoes_plataforma("https://youtube.com/watch?v=test")
        self.assertEqual(type(alias_options["impersonate"]).__name__, "ImpersonateTarget")

    def test_network_options_enable_ejs_only_when_node_exists(self):
        service = self.make_service()
        with patch.object(service_module.shutil, "which", return_value="node"):
            self.assertEqual(service._opcoes_rede_ytdlp()["js_runtimes"], {"node": {}})
        with patch.object(service_module.shutil, "which", return_value=None):
            self.assertNotIn("js_runtimes", service._opcoes_rede_ytdlp())

    def test_unconfigured_pot_preserves_default_youtube_clients(self):
        service = self.make_service()
        for value in ("", "   "):
            with self.subTest(value=value), patch.dict(os.environ, {"AMZ_YOUTUBE_POT_SERVER_HOME": value}), \
                    patch.object(service_module.shutil, "which", return_value=None):
                options = service._opcoes_plataforma("https://youtu.be/test")
            self.assertNotIn("extractor_args", options)

    def test_configured_pot_enables_mweb_with_resolved_script_directory(self):
        service = self.make_service()
        with tempfile.TemporaryDirectory(prefix="amz-pot-test-") as directory:
            script = Path(directory, "build", "generate_once.js")
            script.parent.mkdir()
            script.write_text("// Test fixture; never executed.\n", encoding="utf-8")
            configured_path = str(Path(directory) / "build" / "..")
            with patch.dict(os.environ, {"AMZ_YOUTUBE_POT_SERVER_HOME": configured_path}), \
                    patch.object(service_module.shutil, "which", return_value="node"):
                for url in ("https://youtu.be/test", "https://youtube.com/watch?v=test", "https://m.youtube.com/watch?v=test"):
                    with self.subTest(url=url):
                        options = service._opcoes_plataforma(url)
                        self.assertEqual(options["extractor_args"], {
                            "youtube": {"player_client": ["mweb"]},
                            "youtubepot-bgutilscript": {"server_home": [str(Path(directory).resolve())]},
                        })
                        self.assertIn("youtube.com", options["http_headers"]["Referer"])

    def test_pot_setup_does_not_affect_other_platforms(self):
        service = self.make_service()
        urls = (
            "https://www.tiktok.com/@amz/video/1",
            "https://www.instagram.com/reel/test/",
            "https://youtube.com.example.org/watch?v=test",
        )
        defaults = {url: service._opcoes_plataforma(url) for url in urls}
        # Even a broken YouTube-only configuration must not affect other sites.
        with patch.dict(os.environ, {"AMZ_YOUTUBE_POT_SERVER_HOME": "https://invalid.example/provider"}), \
                patch.object(service_module.shutil, "which", return_value=None):
            for url in urls:
                with self.subTest(url=url):
                    self.assertEqual(service._opcoes_plataforma(url), defaults[url])

    def test_pot_rejects_missing_or_malformed_script_setup(self):
        service = self.make_service()
        with tempfile.TemporaryDirectory(prefix="amz-pot-test-") as directory:
            file_path = Path(directory, "file")
            file_path.write_text("not a directory", encoding="utf-8")
            script_directory = Path(directory, "build", "generate_once.js")
            script_directory.mkdir(parents=True)
            for value in (str(Path(directory, "missing")), str(file_path), directory, f'"{directory}"'):
                with self.subTest(value=value), patch.dict(os.environ, {"AMZ_YOUTUBE_POT_SERVER_HOME": value}), \
                        patch.object(service_module.shutil, "which", return_value="node"):
                    with self.assertRaisesRegex(service_module.UrlVideoError, "Configuracao.*AMZ_YOUTUBE_POT_SERVER_HOME") as caught:
                        service._opcoes_plataforma("https://youtu.be/test")
                    self.assertNotIn("cookies", str(caught.exception).lower())

    def test_pot_rejects_missing_node_before_starting_download(self):
        service = self.make_service()
        service.limits = service_module.UrlVideoLimits()
        service.ffmpeg = "ffmpeg"
        with tempfile.TemporaryDirectory(prefix="amz-pot-test-") as directory:
            script = Path(directory, "build", "generate_once.js")
            script.parent.mkdir()
            script.write_text("// Test fixture; never executed.\n", encoding="utf-8")
            with patch.dict(os.environ, {"AMZ_YOUTUBE_POT_SERVER_HOME": directory}), \
                    patch.object(service_module.shutil, "which", return_value=None), \
                    patch.object(service, "_cookies_file", return_value=None), \
                    patch.object(service, "_executar_ytdlp") as download:
                for operation in (service.download_audio, service.download_video):
                    with self.subTest(operation=operation.__name__):
                        with self.assertRaisesRegex(service_module.UrlVideoError, "Configuracao.*Node.js") as caught:
                            operation("https://youtu.be/test", directory)
                        self.assertNotIn("cookies", str(caught.exception).lower())
                download.assert_not_called()

    def test_permanent_ytdlp_error_is_not_retried(self):
        service = self.make_service(retries=3)
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
            with self.assertRaises(service_module.UrlVideoError):
                service._executar_ytdlp(
                    {"outtmpl": "video.%(ext)s"},
                    "https://www.youtube.com/watch?v=test",
                    download=True,
                    tipo="video",
                )

        self.assertEqual(calls, [True])
        sleep.assert_not_called()

    def test_pot_gate_is_shared_rejects_concurrency_and_releases_after_success(self):
        first_service = self.make_service()
        second_service = self.make_service()
        provider_options = {"extractor_args": {"youtubepot-bgutilscript": {"server_home": ["test-provider"]}}}
        entered = threading.Event()
        finish = threading.Event()
        calls = []

        class BlockingDownload:
            def __init__(self, *_args, **_kwargs):
                pass

            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def extract_info(self, url, download=True):
                calls.append(url)
                if url == "https://youtu.be/hold":
                    entered.set()
                    if not finish.wait(timeout=5):
                        raise RuntimeError("test did not release the download")
                return {"id": "ok"}

        with patch.object(service_module, "YoutubeDL", BlockingDownload), ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(
                first_service._executar_ytdlp, provider_options, "https://youtu.be/hold", download=True, tipo="audio",
            )
            try:
                self.assertTrue(entered.wait(timeout=5), "first provider download did not start")
                with self.assertRaisesRegex(service_module.UrlVideoError, "ocupado"):
                    second_service._executar_ytdlp(provider_options, "https://youtu.be/blocked", download=True, tipo="video")
                # Downloads without the opt-in provider keep their original behavior.
                self.assertEqual(
                    second_service._executar_ytdlp({}, "https://youtu.be/default", download=True, tipo="audio"),
                    {"id": "ok"},
                )
            finally:
                finish.set()
            self.assertEqual(future.result(timeout=5), {"id": "ok"})
            self.assertEqual(
                second_service._executar_ytdlp(provider_options, "https://youtu.be/after", download=True, tipo="video"),
                {"id": "ok"},
            )
        self.assertEqual(calls, ["https://youtu.be/hold", "https://youtu.be/default", "https://youtu.be/after"])

    def test_pot_gate_releases_after_download_failure(self):
        service = self.make_service()
        provider_options = {"extractor_args": {"youtubepot-bgutilscript": {"server_home": ["test-provider"]}}}

        class FailedThenSuccessfulDownload:
            def __init__(self, *_args, **_kwargs):
                pass

            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def extract_info(self, url, download=True):
                if url == "https://youtu.be/fail":
                    raise RuntimeError("Sign in to confirm you're not a bot")
                return {"id": "ok"}

        with patch.object(service_module, "YoutubeDL", FailedThenSuccessfulDownload):
            with self.assertRaisesRegex(service_module.UrlVideoError, "YouTube bloqueou"):
                service._executar_ytdlp(provider_options, "https://youtu.be/fail", download=True, tipo="audio")
            self.assertEqual(
                self.make_service()._executar_ytdlp(provider_options, "https://youtu.be/after", download=True, tipo="audio"),
                {"id": "ok"},
            )

    def test_temporary_ytdlp_error_is_retried_and_partial_files_are_removed(self):
        service = self.make_service(retries=2)
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

        with tempfile.TemporaryDirectory(prefix="amz-urlvideo-test-") as temp_dir:
            partial = Path(temp_dir, "video.f140.part")
            partial.write_bytes(b"partial")
            options = {"outtmpl": str(Path(temp_dir, "video.%(ext)s"))}
            with patch.object(service_module, "YoutubeDL", TemporaryThenSuccess), patch.object(service_module.time, "sleep") as sleep:
                service._executar_ytdlp(
                    options,
                    "https://www.youtube.com/watch?v=test",
                    download=True,
                    tipo="video",
                )
            self.assertFalse(partial.exists())

        self.assertEqual(calls, [True, True])
        sleep.assert_called_once()


if __name__ == "__main__":
    unittest.main()
