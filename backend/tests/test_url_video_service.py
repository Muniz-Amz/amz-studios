"""Offline checks for yt-dlp options and retry behavior used by the bot."""

import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import services.url_video_service as service_module


class UrlVideoServiceTest(unittest.TestCase):
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
