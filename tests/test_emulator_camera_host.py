"""Source proof and media normalization checks; never connect to an emulator."""

import importlib.util
import io
from pathlib import Path
import tempfile
import subprocess
import sys
import threading
import time
import unittest
from unittest.mock import Mock, patch
import uuid

from PIL import Image, ImageCms, ImageDraw, ImageOps


ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("emulator_camera_host", ROOT / "scripts/emulator-camera-host.py")
HOST = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HOST)
WATCH_SPEC = importlib.util.spec_from_file_location("emulator_camera_watchdog", ROOT / "scripts/emulator-camera-watchdog.py")
WATCH = importlib.util.module_from_spec(WATCH_SPEC)
WATCH_SPEC.loader.exec_module(WATCH)


def settings(**overrides):
    return {"cameraId": "10", "type": "image", "width": 1280, "height": 720,
            "rotation": 0, "scaleMode": "fit", "loop": True, **overrides}


def isolated_host():
    host = HOST.CameraHost.__new__(HOST.CameraHost)
    host.lock = threading.RLock()
    host.activation_lock = threading.Lock()
    host.avd, host.camera_id, host.info = "Test", "10", {"port.serial": "5556"}
    host.current, host.intent = None, 0
    host.pending_requests, host.cancelled_requests = {}, {}
    host.recovery_required, host.last_error = False, None
    host.ffmpeg = Path("unused.exe")
    host.configure, host.check_reachable, host.write_journal = Mock(), Mock(), Mock()
    return host


class CameraHostMediaTest(unittest.TestCase):
    def test_duplicate_port_fails_before_camera_or_journal_recovery(self):
        first = HOST.CompanionServer(("127.0.0.1", 0), HOST.Handler)
        try:
            with patch.object(HOST, "CameraHost") as initialize:
                with self.assertRaises(OSError):
                    HOST.serve_companion("Test", "10", first.server_address[1], Path("unused"))
                initialize.assert_not_called()
        finally:
            first.server_close()

    def test_second_avd_owner_on_another_port_cannot_recover_journal(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(HOST, "DATA_ROOT", Path(temp)):
            journal = Path(temp) / "Test-session.json"
            connection = Path(temp) / "connection.json"
            journal.write_text('{"rendererConfigured":true,"generation":"active"}')
            connection.write_text("existing connection")
            original = journal.read_bytes(), connection.read_bytes()
            with HOST.owner_lock.AvdProcessLock("Test", Path(temp) / "locks"):
                with patch.object(HOST, "CameraHost") as initialize:
                    with self.assertRaises(HOST.owner_lock.OwnerBusyError):
                        HOST.serve_companion("Test", "10", 0, Path("unused"))
                    initialize.assert_not_called()
            self.assertEqual((journal.read_bytes(), connection.read_bytes()), original)

    def test_stale_parent_watcher_cannot_mutate_a_live_owner(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(WATCH, "DATA_ROOT", Path(temp)):
            journal = Path(temp) / "Test-session.json"
            journal.write_text('{"rendererConfigured":true}')
            original = journal.read_bytes()
            with HOST.owner_lock.AvdProcessLock("Test", Path(temp) / "locks"):
                result = WATCH.restore_after_parent("Test", 42, "old")
            self.assertEqual(result["reason"], "another_owner_running")
            self.assertEqual(journal.read_bytes(), original)

    def test_cancellation_before_arrival_prevents_preparation_and_renderer_calls(self):
        host = isolated_host()
        request_id = str(uuid.uuid4())
        host.cancel_request(request_id)
        with patch.object(HOST, "prepare_source") as prepare:
            with self.assertRaises(HOST.HostError) as caught:
                host.activate(io.BytesIO(b"image"), 5, settings(), request_id)
            self.assertEqual(caught.exception.code, "ACTIVATION_CANCELLED")
            prepare.assert_not_called()
        host.configure.assert_not_called()
        self.assertEqual(host.pending_requests, {})

    def test_cancellation_during_conversion_prevents_renderer_calls(self):
        host = isolated_host()
        request_id = str(uuid.uuid4())
        with tempfile.TemporaryDirectory() as temp, patch.object(HOST, "DATA_ROOT", Path(temp)):
            (Path(temp) / "sessions").mkdir()

            def conversion(_source, session, _settings, _ffmpeg):
                host.cancel_request(request_id)
                return session / "rendered.png", [], {"durationSeconds": None, "finalFrame": None}

            with patch.object(HOST, "prepare_source", side_effect=conversion) as prepare:
                with self.assertRaises(HOST.HostError) as caught:
                    host.activate(io.BytesIO(b"image"), 5, settings(), request_id)
                self.assertEqual(caught.exception.code, "ACTIVATION_CANCELLED")
                prepare.assert_called_once()
        host.configure.assert_not_called()
        host.check_reachable.assert_not_called()
        self.assertEqual(host.pending_requests, {})

    def test_queued_activation_is_registered_and_can_be_cancelled(self):
        host = isolated_host()
        request_id = str(uuid.uuid4())
        errors = []

        def activate():
            try:
                host.activate(io.BytesIO(b"image"), 5, settings(), request_id)
            except HOST.HostError as error:
                errors.append(error.code)

        host.activation_lock.acquire()
        worker = threading.Thread(target=activate)
        try:
            with patch.object(HOST, "prepare_source") as prepare:
                worker.start()
                deadline = time.monotonic() + 3
                while request_id not in host.pending_requests and time.monotonic() < deadline:
                    time.sleep(0.005)
                self.assertIn(request_id, host.pending_requests)
                host.cancel_request(request_id)
                host.activation_lock.release()
                worker.join(3)
                self.assertFalse(worker.is_alive())
                self.assertEqual(errors, ["ACTIVATION_CANCELLED"])
                prepare.assert_not_called()
        finally:
            if host.activation_lock.locked():
                host.activation_lock.release()
            worker.join(3)
        host.configure.assert_not_called()

    def test_cancelled_old_request_cannot_clear_newer_current_or_pending_source(self):
        host = isolated_host()
        old_id, new_id = str(uuid.uuid4()), str(uuid.uuid4())
        current = {"requestId": new_id, "generation": "new", "sha256": "hash", "settings": settings(),
                   "lease": time.monotonic() + 15, "verified": True, "renderedMode": "image",
                   "playback": {"durationSeconds": None}}
        host.current = current
        host.pending_requests = {old_id: {"cancelled": False, "intent": 0}, new_id: {"cancelled": False, "intent": 0}}
        result = host.cancel_request(old_id)
        self.assertIs(host.current, current)
        self.assertEqual(result["generation"], "new")
        self.assertTrue(host.pending_requests[old_id]["cancelled"])
        self.assertFalse(host.pending_requests[new_id]["cancelled"])
        self.assertEqual(host.intent, 0)
        host.configure.assert_not_called()

    def test_cancellation_tombstones_are_bounded_and_expire(self):
        host = isolated_host()
        with patch.object(HOST, "MAX_CANCELLED_REQUESTS", 2):
            for _ in range(3):
                host.cancel_request(str(uuid.uuid4()))
            self.assertEqual(len(host.cancelled_requests), 2)
        for request_id in host.cancelled_requests:
            host.cancelled_requests[request_id] = time.monotonic() - 1
        host.prune_cancellations()
        self.assertEqual(host.cancelled_requests, {})

    def test_parent_watcher_waits_for_only_its_test_process(self):
        process = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(0.1)"],
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        try:
            WATCH.wait_for_parent(process.pid)
            self.assertEqual(process.wait(timeout=5), 0)
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=5)

    def test_parent_watcher_ignores_replaced_or_clean_sessions(self):
        import json
        original_root = WATCH.DATA_ROOT
        try:
            with tempfile.TemporaryDirectory() as temp:
                WATCH.DATA_ROOT = Path(temp)
                journal = WATCH.DATA_ROOT / "Test-session.json"
                journal.write_text(json.dumps({"avd": "Test", "hostPid": 42, "instanceId": "first", "rendererConfigured": True}))
                self.assertEqual(WATCH.restore_after_parent("Test", 42, "second")["reason"], "session_replaced")
                journal.write_text(json.dumps({"avd": "Test", "hostPid": 42, "instanceId": "first", "rendererConfigured": False}))
                self.assertEqual(WATCH.restore_after_parent("Test", 42, "first")["reason"], "parent_shutdown_clean")
        finally:
            WATCH.DATA_ROOT = original_root

    def test_fit_preserves_image_and_fill_covers_frame(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            source, output = directory / "source.png", directory / "result.png"
            Image.new("RGB", (64, 64), "red").save(source)
            HOST.normalize_image(source, output, settings())
            fit = HOST.load_image(output)
            self.assertEqual(fit.size, (1280, 720))
            self.assertEqual(fit.getpixel((0, 360)), (0, 0, 0))
            self.assertEqual(fit.getpixel((640, 360)), (255, 0, 0))
            HOST.normalize_image(source, output, settings(scaleMode="fill"))
            fill = HOST.load_image(output)
            self.assertEqual(fill.getpixel((0, 360)), (255, 0, 0))

    def test_zoom_and_pan_reveal_actual_source_quadrants(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            source, output = directory / "quadrants.png", directory / "output.png"
            image = Image.new("RGB", (320, 180), "red")
            draw = ImageDraw.Draw(image)
            draw.rectangle((160, 0, 319, 89), fill=(0, 255, 0))
            draw.rectangle((0, 90, 159, 179), fill="blue")
            draw.rectangle((160, 90, 319, 179), fill="white")
            image.save(source)
            for x, y, expected in ((1, 1, (255, 0, 0)), (-1, 1, (0, 255, 0)),
                                   (1, -1, (0, 0, 255)), (-1, -1, (255, 255, 255))):
                HOST.normalize_image(source, output, settings(zoom=2, panX=x, panY=y))
                self.assertEqual(HOST.load_image(output).getpixel((640, 360)), expected)

    def test_zoom_transform_validation_rejects_nonfinite_and_out_of_range_values(self):
        for value in (float("nan"), float("inf"), 0.5, 9, True, "2"):
            with self.assertRaises(HOST.HostError):
                HOST.validate_settings(settings(zoom=value), "10")
        for value in (-2, 2, float("nan"), True):
            with self.assertRaises(HOST.HostError):
                HOST.validate_settings(settings(panX=value), "10")
        self.assertEqual(HOST.validate_settings(settings(), "10")["zoom"], 1.0)

    def test_video_zoom_and_pan_filters_encode_the_selected_quadrant(self):
        ffmpeg = ROOT / "tools/ffmpeg/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v7.1.exe"
        if not ffmpeg.is_file():
            self.skipTest("Bundled local FFmpeg is required")
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            source = directory / "quadrants.png"
            image = Image.new("RGB", (320, 180), "red")
            draw = ImageDraw.Draw(image)
            draw.rectangle((160, 0, 319, 89), fill="green")
            draw.rectangle((0, 90, 159, 179), fill="blue")
            draw.rectangle((160, 90, 319, 179), fill="white")
            image.save(source)
            for x, y, channel in ((1, 1, 0), (1, -1, 2)):
                output = directory / f"frame-{y}.png"
                HOST.run_ffmpeg(ffmpeg, ["-loop", "1", "-i", str(source), "-vf", HOST.video_filter(settings(type="video", zoom=2, panX=x, panY=y)),
                                        "-frames:v", "1", str(output)])
                pixel = HOST.load_image(output).getpixel((640, 360))
                self.assertGreater(pixel[channel], 230)
                self.assertLess(pixel[2 if channel == 0 else 0], 20)

    def test_exif_orientation_is_applied_before_user_rotation(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            source, output = directory / "source.jpg", directory / "result.png"
            original = Image.new("RGB", (80, 40), "red")
            ImageDraw.Draw(original).rectangle((40, 0, 79, 39), fill="blue")
            exif = Image.Exif()
            exif[274] = 6  # 90 degrees clockwise; red should appear at top.
            original.save(source, quality=100, exif=exif)
            HOST.normalize_image(source, output, settings())
            oriented = HOST.load_image(output)
            red, blue = oriented.getpixel((640, 120)), oriented.getpixel((640, 600))
            self.assertGreater(red[0], 240)
            self.assertGreater(blue[2], 240)
            HOST.normalize_image(source, output, settings(rotation=180))
            rotated = HOST.load_image(output)
            self.assertGreater(rotated.getpixel((640, 120))[2], 240)

    def test_real_jpeg_matches_source_across_camera_orientation(self):
        reference = Image.new("RGB", (96, 64), "red")
        draw = ImageDraw.Draw(reference)
        draw.rectangle((48, 0, 95, 31), fill="blue")
        draw.rectangle((0, 32, 47, 63), fill="yellow")
        draw.rectangle((48, 32, 95, 63), fill="white")
        proof = ImageOps.mirror(reference.rotate(90, expand=True))
        encoded = io.BytesIO()
        proof.save(encoded, "JPEG", quality=95)
        result = HOST.compare_jpeg(encoded.getvalue(), [reference.resize((64, 64))])
        self.assertTrue(result["sourceVerified"])
        self.assertLess(result["bestMeanAbsoluteError"], 8)
        unrelated = io.BytesIO()
        Image.new("RGB", (96, 64), "green").save(unrelated, "JPEG")
        self.assertFalse(HOST.compare_jpeg(unrelated.getvalue(), [reference.resize((64, 64))])["sourceVerified"])

    def test_black_borders_cannot_hide_wrong_camera_content(self):
        reference = Image.new("RGB", (64, 64), "black")
        ImageDraw.Draw(reference).rectangle((29, 0, 34, 63), fill="red")
        unrelated = io.BytesIO()
        Image.new("RGB", (64, 64), "black").save(unrelated, "JPEG")
        result = HOST.compare_jpeg(unrelated.getvalue(), [reference])
        self.assertLess(result["fullFrameMeanAbsoluteError"], HOST.MATCH_THRESHOLD)
        self.assertFalse(result["sourceVerified"])
        self.assertGreater(result["contentMeanAbsoluteError"], HOST.MATCH_THRESHOLD)

    def test_unsupported_camera_is_rejected_and_nonlooping_video_is_supported(self):
        with self.assertRaises(HOST.HostError) as caught:
            HOST.validate_settings(settings(cameraId="0"), "10")
        self.assertEqual(caught.exception.code, "CAMERA_UNSUPPORTED")
        self.assertFalse(HOST.validate_settings(settings(type="video", loop=False), "10")["loop"])

    def test_embedded_lab_profile_is_converted_to_srgb(self):
        image = Image.new("LAB", (3, 2), (128, 128, 128))
        encoded = io.BytesIO()
        profile = ImageCms.ImageCmsProfile(ImageCms.createProfile("LAB")).tobytes()
        image.save(encoded, "TIFF", icc_profile=profile)
        converted = HOST.load_image(io.BytesIO(encoded.getvalue()))
        red, green, blue = converted.getpixel((0, 0))
        self.assertLess(abs(red - green), 3)
        self.assertLess(abs(blue - green), 3)
        self.assertTrue(115 <= red <= 123)

    def test_icc_conversion_preserves_alpha_and_exif_orientation(self):
        image = Image.new("RGBA", (4, 2), (200, 0, 0, 128))
        ImageDraw.Draw(image).rectangle((2, 0, 3, 1), fill=(0, 0, 255, 255))
        profile = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()
        exif = Image.Exif()
        exif[274] = 6
        encoded = io.BytesIO()
        image.save(encoded, "PNG", icc_profile=profile, exif=exif)
        converted = HOST.load_image(io.BytesIO(encoded.getvalue()))
        self.assertEqual(converted.size, (2, 4))
        self.assertEqual(converted.getpixel((0, 0)), (100, 0, 0))
        self.assertEqual(converted.getpixel((0, 3)), (0, 0, 255))

    def test_end_frame_timer_cannot_overwrite_newer_source_or_restoration(self):
        host = HOST.CameraHost.__new__(HOST.CameraHost)
        host.lock = threading.RLock()
        host.current = {"generation": "new", "renderedMode": "video", "settings": settings(type="video", loop=False),
                        "lease": time.monotonic() + 15, "playback": {"finalFrame": Path("final.png")}}
        calls = []
        host.configure = calls.append
        host.write_journal = lambda _configured: None
        self.assertFalse(host.hold_final_frame("old"))
        self.assertEqual(calls, [])
        host.current = None
        self.assertFalse(host.hold_final_frame("new"))
        self.assertEqual(calls, [])

    def test_end_frame_hold_keeps_generation_and_source_identity(self):
        host = HOST.CameraHost.__new__(HOST.CameraHost)
        host.lock = threading.RLock()
        host.current = {"generation": "same", "sha256": "source-hash", "renderedMode": "video",
                        "settings": settings(type="video", loop=False), "lease": time.monotonic() + 15,
                        "playback": {"finalFrame": Path("last-decoded.png")}}
        calls = []
        host.configure = calls.append
        host.write_journal = lambda _configured: None
        self.assertTrue(host.hold_final_frame("same"))
        self.assertIn("scene.mode=imagefile:last-decoded.png", calls[0])
        self.assertEqual(host.current["generation"], "same")
        self.assertEqual(host.current["sha256"], "source-hash")
        self.assertEqual(host.current["renderedMode"], "final_frame")
        self.assertFalse(host.hold_final_frame("same"))
        self.assertEqual(len(calls), 1)

    def test_video_conversion_produces_multiple_matching_reference_colors(self):
        ffmpeg = next(iter((ROOT / "tools/ffmpeg/imageio_ffmpeg/binaries").glob("ffmpeg*.exe")), None)
        fixture = ROOT / "android/camera-client/src/androidTest/assets/red-blue-2s.mp4"
        if ffmpeg is None or not fixture.is_file():
            self.skipTest("Bundled local FFmpeg and red/blue test fixture are required")
        with tempfile.TemporaryDirectory() as temp:
            output, references, playback = HOST.prepare_source(fixture, Path(temp), settings(type="video"), ffmpeg)
            self.assertTrue(output.is_file())
            self.assertGreaterEqual(len(references), 20)
            colors = [image.getpixel((32, 32)) for image in references]
            self.assertTrue(any(red > blue + 100 for red, _, blue in colors))
            self.assertTrue(any(blue > red + 100 for red, _, blue in colors))
            self.assertAlmostEqual(playback["durationSeconds"], 2.0, delta=1 / 30 + 0.00001)

    def test_nonlooping_short_clip_extracts_its_true_final_decoded_frame(self):
        ffmpeg = next(iter((ROOT / "tools/ffmpeg/imageio_ffmpeg/binaries").glob("ffmpeg*.exe")), None)
        if ffmpeg is None:
            self.skipTest("Bundled local FFmpeg is required")
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            for index, color in enumerate(("red", "red", "green", "green", "blue", "blue")):
                Image.new("RGB", (192, 108), color).save(directory / f"input-{index:03d}.png")
            source = directory / "short.mp4"
            HOST.run_ffmpeg(ffmpeg, ["-framerate", "30", "-i", str(directory / "input-%03d.png"),
                                    "-c:v", "libx264", "-pix_fmt", "yuv420p", str(source)])
            _, references, playback = HOST.prepare_source(source, directory, settings(type="video", loop=False), ffmpeg)
            self.assertAlmostEqual(playback["durationSeconds"], 0.2, delta=1 / 30 + 0.00001)
            final = HOST.load_image(playback["finalFrame"])
            red, _, blue = final.getpixel((final.width // 2, final.height // 2))
            self.assertGreater(blue, red + 150)
            self.assertGreater(references[-1].getpixel((32, 32))[2], 200)


if __name__ == "__main__":
    unittest.main()
