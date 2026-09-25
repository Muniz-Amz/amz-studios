"""Focused checks for the restored GIF conversions."""

from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from PIL import Image, ImageSequence

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from services.media_service import MediaError, MediaLimits, MediaService, tipo_anexo


class Attachment:
    def __init__(self, filename, content_type, size=0):
        self.filename = filename
        self.content_type = content_type
        self.size = size


class MediaServiceTest(unittest.TestCase):
    def setUp(self):
        self.limits = MediaLimits(max_output_mb=1, max_video_seconds=2, max_width=32, gif_fps=5)
        self.service = MediaService(self.limits)

    def test_attachment_types_include_images_and_videos(self):
        self.assertEqual(tipo_anexo(Attachment("foto.png", "image/png")), "image")
        self.assertEqual(tipo_anexo(Attachment("clipe.mp4", "video/mp4")), "video")
        self.assertEqual(tipo_anexo(Attachment("arquivo.bin", "application/octet-stream")), "unknown")

    def test_image_to_gif_preserves_a_small_valid_output(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "foto.png"
            output = Path(directory) / "foto.gif"
            Image.new("RGBA", (80, 40), (20, 80, 140, 255)).save(source)

            self.service.imagem_para_gif(source, output)

            with Image.open(output) as converted:
                self.assertEqual(converted.format, "GIF")
                self.assertLessEqual(max(converted.size), self.limits.max_width)
            self.assertLess(output.stat().st_size, self.limits.max_output_bytes)

    def test_video_to_gif_applies_duration_fps_and_scale_limits(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "video.mp4"
            output = Path(directory) / "video.gif"
            self.service._run_ffmpeg([
                "-f", "lavfi", "-i", "testsrc2=size=80x160:rate=10",
                "-t", "3", "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p", str(source),
            ])

            self.service.video_para_gif(source, output)

            with Image.open(output) as converted:
                self.assertEqual(converted.format, "GIF")
                self.assertEqual(converted.info.get("loop"), 0)
                self.assertEqual(converted.size, (16, 32))
                frames = []
                duration_ms = 0
                for frame in ImageSequence.Iterator(converted):
                    frames.append(frame.convert("RGB").tobytes())
                    duration_ms += frame.info.get("duration", 0)
                self.assertEqual(len(frames), 10)
                self.assertGreater(len(set(frames)), 1)
                self.assertEqual(duration_ms, 2000)
            self.assertLess(output.stat().st_size, self.limits.max_output_bytes)

    def test_image_pixel_limit_rejects_before_decoding(self):
        limits = MediaLimits(max_image_pixels=100)
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "foto.png"
            output = Path(directory) / "foto.gif"
            Image.new("RGB", (11, 10)).save(source)
            with patch.object(Image.Image, "thumbnail", side_effect=AssertionError("must reject before resizing")):
                with self.assertRaises(MediaError):
                    MediaService(limits).imagem_para_gif(source, output)
            self.assertFalse(output.exists())

    def test_invalid_video_returns_conversion_error(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "invalid.mp4"
            source.write_bytes(b"not a video")
            with self.assertRaises(MediaError):
                self.service.video_para_gif(source, Path(directory) / "result.gif")


if __name__ == "__main__":
    unittest.main()
