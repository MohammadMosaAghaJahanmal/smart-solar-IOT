"""Linux socket integration tests for the native protocol bridge.

The server here is a controlled protocol fixture, not Android or VMOS. Passing
these tests establishes wire safety; it does not establish device compatibility.
Run with Python 3 and the Linux-built proxy path as the sole argument.
"""
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest


def read_exact(connection, length):
    result = bytearray()
    while len(result) < length:
        block = connection.recv(length - len(result))
        if not block:
            raise EOFError("socket disconnected")
        result.extend(block)
    return bytes(result)


def read_nul(connection):
    data = bytearray()
    while True:
        value = read_exact(connection, 1)
        if value == b"\0":
            return bytes(data)
        data.extend(value)


class OriginalCamera:
    """Small deterministic wire fixture which keeps original bytes observable."""
    timestamp = struct.pack("<q", 782344519908)
    pixels = bytes(range(6))

    def __init__(self, path):
        self.listener = socket.socket(socket.AF_UNIX)
        self.listener.bind(str(path))
        self.listener.listen(8)
        self.stopped = False
        self.commands = []
        self.drop_stage = None
        self.vmos_padded = False
        self.nul_frame_only = False
        self.forced_header = None
        self.nonzero_trailer = False
        self.thread = threading.Thread(target=self.accept, daemon=True)
        self.thread.start()

    def accept(self):
        while not self.stopped:
            try:
                connection, _ = self.listener.accept()
            except OSError:
                return
            threading.Thread(target=self.serve, args=(connection,), daemon=True).start()

    def serve(self, connection):
        try:
            service = read_nul(connection)
            assert service.startswith(b"pipe:qemud:camera")
            if self.drop_stage == "handshake":
                return
            connection.sendall(b"OK")
            while True:
                command = read_nul(connection)
                self.commands.append(command)
                if self.drop_stage == "list-length":
                    return
                if command.startswith(b"frame "):
                    fields = dict(token.split(b"=", 1) for token in command.split()[1:])
                    video, preview = int(fields[b"video"]), int(fields[b"preview"])
                    data = bytes((i % 251 for i in range(video + preview)))
                    payload = b"ok:" + data
                    if self.vmos_padded or self.nul_frame_only:
                        payload += b"\x01" if self.nonzero_trailer else b"\0"
                    elif fields.get(b"time") == b"1":
                        payload += self.timestamp
                elif command == b"list":
                    payload = b"ok:name=camera_0 framedims=2x2 dir=back\n"
                    if self.vmos_padded:
                        payload += b"\0"
                elif command == b"unknown":
                    payload = b"ko:Unknown query\0"
                else:
                    payload = b"ok\0"
                header = f"{len(payload):08x}".encode()
                if self.vmos_padded:
                    header = (f"{len(payload):04x}".encode() + b"\0\0\xd9\xb9")[:8]
                connection.sendall(self.forced_header if self.forced_header is not None else header)
                if self.drop_stage == "list-payload":
                    return
                connection.sendall(payload)
        except (EOFError, OSError):
            pass
        finally:
            connection.close()

    def close(self):
        self.stopped = True
        self.listener.close()


class ProxyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.binary = str(Path(sys.argv[1]).resolve())

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="vcam-proxy-test-")
        self.directory = Path(self.temporary.name)
        self.state = self.directory / "state"
        self.state.mkdir(mode=0o700)
        self.proxy_socket = self.directory / "proxy.sock"
        self.original = OriginalCamera(self.directory / "original.sock")
        self.command = [self.binary, "--listen", str(self.proxy_socket),
                        "--original", str(self.directory / "original.sock"),
                        "--state-dir", str(self.state)]
        self.log = open(self.directory / "proxy.log", "w+")
        self.process = subprocess.Popen(self.command, stdout=self.log, stderr=self.log)
        deadline = time.monotonic() + 5
        while not self.proxy_socket.exists():
            if self.process.poll() is not None or time.monotonic() >= deadline:
                self.log.seek(0)
                self.fail("proxy did not start: " + self.log.read())
            time.sleep(.01)
        self.connection = socket.socket(socket.AF_UNIX)
        self.connection.settimeout(5)
        self.connection.connect(str(self.proxy_socket))
        self.connection.sendall(b"pipe:qemud:camera:name=camera_0\0")
        self.assertEqual(read_exact(self.connection, 2), b"OK")

    def tearDown(self):
        self.connection.close()
        self.process.terminate()
        try:
            self.process.wait(5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(5)
            self.fail("proxy did not stop promptly")
        self.original.close()
        self.log.close()
        self.temporary.cleanup()

    def query(self, command):
        return self.query_wire(command)[1]

    def query_wire(self, command):
        self.connection.sendall(command.encode() + b"\0")
        header = read_exact(self.connection, 8)
        return header, read_exact(self.connection, int(header.split(b"\0", 1)[0], 16))

    def configure(self, name="source-1", format="RGBA", duration=2):
        raw = self.state / f"{name}.raw"
        raw.write_bytes(bytes([255, 0, 0, 255]) * 4 if format == "RGBA" else bytes([82] * 4 + [90, 240]))
        expires = time.clock_gettime_ns(time.CLOCK_BOOTTIME) + int(duration * 1e9)
        text = (f"VCAM1\nid={name}\ncamera_name=camera_0\npath={raw}\nformat={format}\n"
                f"width=2\nheight=2\nfps=30\nframes=1\nloop=1\n"
                f"sha256={'a' * 64}\nexpires_ns={expires}\n")
        temporary = self.state / "source.next"
        temporary.write_text(text)
        temporary.replace(self.state / "source.cfg")
        return raw

    def start(self):
        self.assertEqual(self.query("start dim=2x2 pix=825382478"), b"ok\0")

    def crop(self, camera="camera_0", left=0, top=0, width=8, height=8,
             active_width=8, active_height=8, epoch=None, serial=1, extra="", expect=True):
        if epoch is None:
            epoch = time.clock_gettime_ns(time.CLOCK_BOOTTIME)
        command = (f"crop left={left} top={top} width={width} height={height} "
                   f"active_width={active_width} active_height={active_height} epoch={epoch} serial={serial}{extra}")
        with socket.socket(socket.AF_UNIX) as control:
            control.settimeout(2)
            control.connect(str(self.proxy_socket))
            control.sendall(f"vcam:crop:{camera}\0{command}\0".encode())
            try:
                result = control.recv(2)
            except ConnectionResetError:
                result = b""
            self.assertEqual(result, b"OK" if expect else b"")
        return epoch

    def configure_quadrants(self, format="RGBA", name="quadrants", live=False, sequence=0):
        raw = self.state / f"{name}-{sequence % 2}.raw"
        colors = [(255, 0, 0, 255), (0, 255, 0, 255), (0, 0, 255, 255), (255, 255, 0, 255)]
        if format == "RGBA":
            data = b"".join(bytes(colors[(y // 4) * 2 + x // 4]) for y in range(8) for x in range(8))
        else:
            data = (bytes([82 if x < 4 and y < 4 else 145 if y < 4 else 41 if x < 4 else 210 for y in range(8) for x in range(8)])
                    + bytes([90 if x < 2 and y < 2 else 54 if y < 2 else 240 if x < 2 else 16 for y in range(4) for x in range(4)])
                    + bytes([240 if x < 2 and y < 2 else 34 if y < 2 else 110 if x < 2 else 146 for y in range(4) for x in range(4)]))
        if live:
            data = b"VCAMFR2\n" + struct.pack(">Q", sequence) + data
        temporary = self.state / "quadrants.next"
        temporary.write_bytes(data)
        temporary.replace(raw)
        expires = time.clock_gettime_ns(time.CLOCK_BOOTTIME) + int(5e9)
        config = (f"{'VCAM2' if live else 'VCAM1'}\nid={name}\ncamera_name=camera_0\npath={raw}\nformat={format}\n"
                  f"width=8\nheight=8\nfps=30\nframes=1\nloop=1\nsha256={'a' * 64}\nexpires_ns={expires}\n")
        if live:
            config += f"sequence={sequence}\n"
        temporary = self.state / "config.next"
        temporary.write_text(config)
        temporary.replace(self.state / "source.cfg")

    def test_app_crop_changes_actual_rgba_pixels_and_full_reset_on_existing_stream(self):
        self.start()
        self.configure_quadrants()
        frame = self.query("frame video=6 preview=16 time=1")
        original = frame
        self.assertEqual(frame[9:25], bytes([255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255, 255, 0, 255]))
        epoch = self.crop(left=4, top=4, width=4, height=4)
        frame = self.query("frame video=6 preview=16 time=1")
        self.assertEqual(frame[9:25], bytes([255, 255, 0, 255]) * 4)
        self.assertEqual(frame[-8:], self.original.timestamp)
        self.crop(epoch=epoch, serial=2)
        self.assertEqual(self.query("frame video=6 preview=16 time=1"), original)
        self.assertFalse(any(command.startswith(b"crop ") for command in self.original.commands))

    def test_app_crop_centers_stream_aspect_inside_sensor_rectangle_rgba(self):
        self.query("start dim=8x4 pix=825382478")
        self.configure_quadrants()
        (self.state / "quadrants-0.raw").write_bytes(b"".join(bytes([x * 24, y * 24, 40, 255]) for y in range(8) for x in range(8)))
        epoch = self.crop()
        expected = b"".join(bytes([x * 24, (y + 2) * 24, 40, 255]) for y in range(4) for x in range(8))
        self.assertEqual(self.query("frame video=0 preview=128 time=1"), b"ok:" + expected + self.original.timestamp)
        # An off-center tall requested crop is further cropped vertically,
        # preserving its center and each stream's own aspect ratio.
        self.crop(left=2, top=0, width=4, height=8, epoch=epoch, serial=2)
        expected = b"".join(bytes([(2 + x // 2) * 24, (3 + y // 2) * 24, 40, 255]) for y in range(4) for x in range(8))
        self.assertEqual(self.query("frame video=0 preview=128 time=1"), b"ok:" + expected + self.original.timestamp)

    def test_app_crop_centers_stream_aspect_in_direct_i420_planes(self):
        self.query("start dim=4x8 pix=842093913")
        self.configure_quadrants(format="I420")
        y = bytes(16 + x + y * 8 for y in range(8) for x in range(8))
        u = bytes(64 + x + y * 4 for y in range(4) for x in range(4))
        v = bytes(144 + x + y * 4 for y in range(4) for x in range(4))
        (self.state / "quadrants-0.raw").write_bytes(y + u + v)
        self.crop()
        expected = (bytes(16 + (x + 2) + y * 8 for y in range(8) for x in range(4))
                    + bytes(64 + (x + 1) + y * 4 for y in range(4) for x in range(2))
                    + bytes(144 + (x + 1) + y * 4 for y in range(4) for x in range(2)))
        self.assertEqual(self.query("frame video=48 preview=0 time=1"), b"ok:" + expected + self.original.timestamp)

    def test_app_crop_resamples_i420_planes_without_rgb_round_trip(self):
        self.start()
        self.configure_quadrants(format="I420")
        self.crop(left=4, top=4, width=4, height=4)
        self.assertEqual(self.query("frame video=6 preview=0 time=1"), b"ok:" + bytes([210] * 4 + [146, 16]) + self.original.timestamp)
        self.query("start dim=2x2 pix=842093913")  # I420 / YU12
        self.assertEqual(self.query("frame video=6 preview=0 time=1"), b"ok:" + bytes([210] * 4 + [16, 146]) + self.original.timestamp)

    def test_app_crop_live_slot_updates_keep_crop_and_delivery_identity(self):
        self.start()
        self.configure_quadrants(live=True)
        self.crop(left=0, top=4, width=4, height=4)
        expected = b"ok:" + bytes([41] * 4 + [110, 240]) + self.original.timestamp
        self.assertEqual(self.query("frame video=6 preview=0 time=1"), expected)
        self.configure_quadrants(live=True, sequence=1)
        self.assertEqual(self.query("frame video=6 preview=0 time=1"), expected)
        time.sleep(.21)
        self.assertEqual(self.query("frame video=6 preview=0 time=1"), expected)
        deadline = time.monotonic() + 1
        while True:
            status = json.loads((self.state / "status.json").read_text())
            if status["sourceId"] or time.monotonic() >= deadline:
                break
            time.sleep(.01)
        self.assertEqual(status["sourceId"], "quadrants")

    def test_app_crop_other_camera_state_cannot_change_selected_camera(self):
        self.start()
        self.configure_quadrants()
        self.crop(left=0, top=0, width=4, height=4)
        expected = b"ok:" + bytes([82] * 4 + [240, 90]) + self.original.timestamp
        self.assertEqual(self.query("frame video=6 preview=0 time=1"), expected)
        self.crop(camera="camera_1", left=4, top=4, width=4, height=4)
        self.assertEqual(self.query("frame video=6 preview=0 time=1"), expected)

    def test_app_crop_rejects_stale_epoch_serial_and_invalid_fields_without_changing_pixels(self):
        self.start()
        self.configure_quadrants()
        epoch = self.crop(left=4, top=4, width=4, height=4, serial=2)
        expected = self.query("frame video=6 preview=16 time=1")
        self.crop(epoch=epoch, serial=2, expect=False)
        self.crop(epoch=epoch, serial=1, expect=False)
        self.crop(epoch=epoch - 1, serial=3, expect=False)
        self.crop(epoch=epoch, serial=3, width=0, expect=False)
        self.crop(epoch=epoch, serial=3, left=8, expect=False)
        self.crop(epoch=epoch, serial=3, width=65537, expect=False)
        self.crop(epoch=epoch, serial=3, top=-1, expect=False)
        self.crop(epoch=epoch, serial=3, extra=" left=0", expect=False)
        self.crop(epoch=epoch, serial=3, extra=" unknown=1", expect=False)
        self.crop(epoch=time.clock_gettime_ns(time.CLOCK_BOOTTIME) + int(3e9), serial=3, expect=False)
        for name in ("camera_32", "camera_00", "camera_-1", "camera_0/other", "unrelated"):
            self.crop(camera=name, expect=False)
        self.assertEqual(self.query("frame video=6 preview=16 time=1"), expected)
        self.crop(epoch=epoch + 1, serial=1)
        self.assertNotEqual(self.query("frame video=6 preview=16 time=1"), expected)

    def test_crop_reserved_services_never_reach_original_and_require_complete_protocol(self):
        for wire in (b"vcam:unknown:camera_0\0crop\0", b"vcam:crop:camera_0\0list\0", b"vcam:crop:camera_0\0crop serial=1\0"):
            with socket.socket(socket.AF_UNIX) as control:
                control.settimeout(2)
                control.connect(str(self.proxy_socket))
                control.sendall(wire)
                try:
                    self.assertEqual(control.recv(2), b"")
                except ConnectionResetError:
                    pass
        self.assertEqual(self.original.commands, [])

    @unittest.skipUnless(os.geteuid() == 0, "changing a test child UID requires Linux root")
    def test_crop_rejects_an_ordinary_unmapped_peer_uid(self):
        self.directory.chmod(0o711)
        code = ("import os,socket; os.setgid(65534); os.setuid(65534); "
                "s=socket.socket(socket.AF_UNIX); s.settimeout(2); "
                f"s.connect({str(self.proxy_socket)!r}); "
                "s.sendall(b'vcam:crop:camera_0\\0crop left=0 top=0 width=8 height=8 active_width=8 active_height=8 epoch=1 serial=1\\0'); "
                "\ntry:\n r=s.recv(2)\nexcept ConnectionResetError:\n r=b''\nassert r==b'',r")
        result = subprocess.run([sys.executable, "-c", code], capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.original.commands, [])

    def configure_live(self, sequence=0, color=(255, 0, 0, 255), raw_sequence=None, name="live-1"):
        raw = self.state / f"{name}-{sequence % 2}.raw"
        temporary = self.state / "frame.next"
        temporary.write_bytes(b"VCAMFR2\n" + struct.pack(">Q", sequence if raw_sequence is None else raw_sequence) + bytes(color) * 4)
        temporary.replace(raw)
        expires = time.clock_gettime_ns(time.CLOCK_BOOTTIME) + int(2 * 1e9)
        text = (f"VCAM2\nid={name}\ncamera_name=camera_0\npath={raw}\nformat=RGBA\n"
                f"width=2\nheight=2\nfps=30\nframes=1\nloop=1\nsequence={sequence}\n"
                f"sha256={'a' * 64}\nexpires_ns={expires}\n")
        temporary = self.state / "source.next"
        temporary.write_text(text)
        temporary.replace(self.state / "source.cfg")
        return raw

    def test_transparent_factory_unknown_and_frames(self):
        self.assertEqual(self.query("list"), b"ok:name=camera_0 framedims=2x2 dir=back\n")
        self.assertEqual(self.query("unknown"), b"ko:Unknown query\0")
        self.start()
        self.assertEqual(self.query("frame video=6 preview=0 time=1"), b"ok:" + bytes(range(6)) + self.original.timestamp)
        self.assertEqual(self.original.commands[-1], b"frame video=6 preview=0 time=1")

    def test_live_ring_remaps_same_generation_and_preserves_delivery_count(self):
        self.start()
        self.configure_live(0)
        red = bytes([82] * 4 + [240, 90])
        blue = bytes([41] * 4 + [110, 240])
        for sequence in range(6):
            self.configure_live(sequence, (255, 0, 0, 255) if sequence % 2 == 0 else (0, 0, 255, 255))
            self.assertEqual(self.query("frame video=6 preview=0 time=1"), b"ok:" + (red if sequence % 2 == 0 else blue) + self.original.timestamp)
        time.sleep(.21)
        self.query("frame video=6 preview=0 time=1")
        deadline = time.monotonic() + 1
        while True:
            status = json.loads((self.state / "status.json").read_text())
            if status["sourceFrames"] == 7 or time.monotonic() >= deadline:
                break
            time.sleep(.01)
        self.assertEqual(status["protocol"], 2)
        self.assertEqual(status["sourceId"], "live-1")
        self.assertEqual(status["sequence"], 5)
        self.assertEqual(status["sourceFrames"], 7)
        self.assertTrue(status["live"])
        self.assertEqual(len(list(self.state.glob("*.raw"))), 2)

    def test_live_ring_sequence_mismatch_and_backwards_frame_keep_original(self):
        self.start()
        self.configure_live(8)
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes([82] * 4 + [240, 90]))
        self.configure_live(7)
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes(range(6)))
        self.configure_live(9, raw_sequence=11)
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes(range(6)))

    def test_live_ring_same_sequence_does_not_map_replaced_inode(self):
        self.start()
        self.configure_live(0)
        red = bytes([82] * 4 + [240, 90])
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], red)
        self.configure_live(0, (0, 0, 255, 255))
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], red)
        self.configure_live(1, (0, 0, 255, 255))
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes([41] * 4 + [110, 240]))

    def test_live_ring_rejects_missing_header_and_wrong_generation_geometry(self):
        self.start()
        raw = self.configure_live(0)
        raw.write_bytes(bytes([255, 0, 0, 255]) * 4)
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes(range(6)))
        self.configure_live(0)
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes([82] * 4 + [240, 90]))
        self.configure_live(1)
        config = self.state / "source.cfg"
        config.write_text(config.read_text().replace("fps=30", "fps=24"))
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes(range(6)))

    def test_read_only_native_factory_list(self):
        result = subprocess.run([self.binary, "--list-cameras", "--original", str(self.directory / "original.sock")], capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), b"name=camera_0 framedims=2x2 dir=back")
        self.assertEqual(self.original.commands, [b"list"])

    def test_native_mapped_ownership_and_peer_identity_security(self):
        result = subprocess.run([self.binary, "--selftest"], capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(b"ownership and mapped camera peer identity: PASS", result.stdout)

    def test_source_config_symlink_writable_and_hardlink_are_rejected(self):
        self.start()
        self.configure()
        config = self.state / "source.cfg"
        config.chmod(0o666)
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes(range(6)))
        config.chmod(0o600)
        target = self.state / "config-copy"
        config.rename(target)
        config.symlink_to(target)
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes(range(6)))
        config.unlink()
        os.link(target, config)
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes(range(6)))

    def test_named_raw_path_must_still_match_pinned_directory_descriptor(self):
        self.start()
        self.configure()
        retained = self.directory / "retained-state"
        self.state.rename(retained)
        self.state.mkdir(mode=0o700)
        self.configure(name="other-object")
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes(range(6)))

    def test_measured_vmos_factory_header_and_payload_forward_unchanged(self):
        self.original.vmos_padded = True
        header, payload = self.query_wire("list")
        self.assertEqual(payload, b"ok:name=camera_0 framedims=2x2 dir=back\n\0")
        self.assertEqual(header, (f"{len(payload):04x}".encode() + b"\0\0\xd9\xb9")[:8])
        result = subprocess.run([self.binary, "--list-cameras", "--original", str(self.directory / "original.sock")], capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), b"name=camera_0 framedims=2x2 dir=back")
        self.assertNotIn(b"\0", result.stdout)

    def test_measured_vmos_nul_frame_has_no_transport_timestamp(self):
        self.original.vmos_padded = True
        self.start()
        self.configure()
        for time_flag in (0, 1):
            for slots in ("video=6 preview=0", "video=0 preview=6"):
                header, payload = self.query_wire(f"frame {slots} time={time_flag}")
                self.assertEqual(header, b"000a\0\0\xd9\xb9")
                self.assertEqual(payload, b"ok:" + bytes([82] * 4 + [240, 90]) + b"\0")

    def test_unmeasured_frame_layouts_are_never_reinterpreted(self):
        self.start()
        self.configure()
        self.original.nul_frame_only = True
        self.assertEqual(self.query("frame video=6 preview=0 time=1"), b"ok:" + bytes(range(6)) + b"\0")
        self.original.vmos_padded = True
        self.original.nonzero_trailer = True
        self.assertEqual(self.query("frame video=6 preview=0 time=1"), b"ok:" + bytes(range(6)) + b"\x01")
        self.original.nonzero_trailer = False
        self.assertEqual(self.query("frame video=6 preview=16 time=1"), b"ok:" + bytes(range(22)) + b"\0")

    def test_invalid_and_oversized_headers_fail_before_allocating_payload(self):
        for header in (b"00g3\0\0\xd9\xb9", b"003\0junk", b"0002\0xxx", b"02000001", b"fffffff\0", b" 003\0xxx", b"+003\0xxx"):
            self.original.forced_header = header
            result = subprocess.run([self.binary, "--list-cameras", "--original", str(self.directory / "original.sock")], capture_output=True, timeout=5)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(b"invalid camera list response length", result.stderr)
        self.original.forced_header = None

    def test_proxy_rejects_oversized_wire_header_without_forwarding_it(self):
        self.original.forced_header = b"02000001"
        self.connection.sendall(b"list\0")
        self.assertEqual(self.connection.recv(1), b"")

    def test_native_factory_disconnect_reports_exact_stage(self):
        for fixture_stage, native_stage in (("handshake", "read-handshake"), ("list-length", "read-list-length"), ("list-payload", "read-list-payload")):
            self.original.drop_stage = fixture_stage
            result = subprocess.run([self.binary, "--list-cameras", "--original", str(self.directory / "original.sock")], capture_output=True, timeout=5)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(f"factory probe stage={native_stage}: socket read disconnected".encode(), result.stderr)
        self.original.drop_stage = None

    def test_rgba_overrides_nv21_preserves_timestamp_and_removal(self):
        self.start()
        self.configure()
        time.sleep(.21)  # Make the delivery status observably newer than startup.
        response = self.query("frame video=6 preview=0 time=1")
        self.assertEqual(response, b"ok:" + bytes([82] * 4 + [240, 90]) + self.original.timestamp)
        deadline = time.monotonic() + 1
        while True:
            status = json.loads((self.state / "status.json").read_text())
            if status["sourceId"] == "source-1" or time.monotonic() >= deadline:
                break
            time.sleep(.01)
        self.assertEqual(status["sourceId"], "source-1")
        self.assertEqual(status["sourceFrames"], 1)
        self.assertTrue(status["configured"])
        (self.state / "source.cfg").unlink()
        self.assertEqual(self.query("frame video=6 preview=0 time=1"), b"ok:" + bytes(range(6)) + self.original.timestamp)

    def test_vmos_preview_wrapper_is_nv21(self):
        self.start()
        self.configure()
        self.assertEqual(self.query("frame video=0 preview=6 time=1"), b"ok:" + bytes([82] * 4 + [240, 90]) + self.original.timestamp)

    def test_mixed_video_rgb_preview_and_unsupported_sizes(self):
        self.start()
        self.configure()
        red_nv21 = bytes([82] * 4 + [240, 90])
        red_rgba = bytes([255, 0, 0, 255]) * 4
        self.assertEqual(self.query("frame video=6 preview=16 time=1"), b"ok:" + red_nv21 + red_rgba + self.original.timestamp)
        self.assertEqual(self.query("frame video=7 preview=0 time=1"), b"ok:" + bytes(range(7)) + self.original.timestamp)

    def test_unsupported_stream_keeps_original(self):
        self.assertEqual(self.query("start dim=2x2 pix=42"), b"ok\0")
        self.configure()
        self.assertEqual(self.query("frame video=6 preview=0 time=1"), b"ok:" + bytes(range(6)) + self.original.timestamp)

    def test_i420_source_converts_without_rgb_roundtrip(self):
        self.start()
        self.configure(format="I420")
        self.assertEqual(self.query("frame video=6 preview=0 time=1"), b"ok:" + bytes([82] * 4 + [240, 90]) + self.original.timestamp)

    def test_expired_lease_and_malformed_source_restore_original(self):
        self.start()
        self.configure(duration=.25)
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes([82] * 4 + [240, 90]))
        time.sleep(.3)
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes(range(6)))
        self.configure(name="source-2")
        (self.state / "source.cfg").write_text("VCAM1\ninvalid\n")
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes(range(6)))

    def test_duplicate_proxy_cannot_unlink_live_socket(self):
        result = subprocess.run(self.command, capture_output=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"another camera proxy", result.stderr)
        self.assertEqual(self.query("list"), b"ok:name=camera_0 framedims=2x2 dir=back\n")

    def test_killed_proxy_releases_lock_and_replaces_owned_stale_socket(self):
        previous_pid = self.process.pid
        self.process.kill()
        self.process.wait(5)
        self.connection.close()
        # SIGKILL deliberately bypasses cleanup: both the pathname and lock
        # file remain. The OS must release flock, and only the disconnected,
        # same-UID socket may be removed by the replacement daemon.
        self.assertTrue(self.proxy_socket.is_socket())
        self.assertTrue((self.state / "proxy.lock").is_file())
        self.process = subprocess.Popen(self.command, stdout=self.log, stderr=self.log)
        self.assertNotEqual(previous_pid, self.process.pid)
        deadline = time.monotonic() + 5
        while True:
            connection = socket.socket(socket.AF_UNIX)
            connection.settimeout(1)
            try:
                connection.connect(str(self.proxy_socket))
                connection.sendall(b"pipe:qemud:camera\0")
                self.assertEqual(read_exact(connection, 2), b"OK")
                self.connection = connection
                break
            except (OSError, EOFError):
                connection.close()
                if self.process.poll() is not None or time.monotonic() >= deadline:
                    self.log.seek(0)
                    self.fail("replacement proxy did not recover stale socket: " + self.log.read())
                time.sleep(.01)
        self.assertEqual(self.query("list"), b"ok:name=camera_0 framedims=2x2 dir=back\n")

    def test_other_camera_and_factory_frames_remain_original(self):
        self.configure()
        for service in (b"pipe:qemud:camera:name=camera_1", b"pipe:qemud:camera"):
            with socket.socket(socket.AF_UNIX) as connection:
                connection.settimeout(5)
                connection.connect(str(self.proxy_socket))
                connection.sendall(service + b"\0")
                self.assertEqual(read_exact(connection, 2), b"OK")
                connection.sendall(b"start dim=2x2 pix=825382478\0")
                self.assertEqual(read_exact(connection, int(read_exact(connection, 8), 16)), b"ok\0")
                connection.sendall(b"frame video=6 preview=0 time=1\0")
                self.assertEqual(read_exact(connection, int(read_exact(connection, 8), 16)), b"ok:" + bytes(range(6)) + self.original.timestamp)

    def test_other_state_directory_cannot_unlink_live_socket(self):
        other_state = self.directory / "state2"
        other_state.mkdir(mode=0o700)
        command = self.command[:-1] + [str(other_state)]
        result = subprocess.run(command, capture_output=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"another process is listening", result.stderr)
        self.assertEqual(self.query("list"), b"ok:name=camera_0 framedims=2x2 dir=back\n")

    def test_world_writable_raw_and_nested_directory_are_rejected(self):
        self.start()
        raw = self.configure()
        raw.chmod(0o666)
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes(range(6)))
        raw.chmod(0o600)
        nested = self.state / "nested"
        nested.mkdir()
        raw.rename(nested / raw.name)
        config = self.state / "source.cfg"
        config.write_text(config.read_text().replace(str(raw), str(nested / raw.name)))
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes(range(6)))

    def test_fifo_config_and_fifo_raw_cannot_block_camera(self):
        self.start()
        os.mkfifo(self.state / "source.cfg", 0o600)
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes(range(6)))
        (self.state / "source.cfg").unlink()
        raw = self.configure()
        raw.unlink()
        os.mkfifo(raw, 0o600)
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes(range(6)))

    def test_symlink_source_is_rejected(self):
        self.start()
        raw = self.configure()
        target = self.directory / "outside.raw"
        raw.rename(target)
        raw.symlink_to(target)
        self.assertEqual(self.query("frame video=6 preview=0 time=1")[3:9], bytes(range(6)))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: test-proxy.py /absolute/path/to/linux-built-proxy")
    unittest.main(argv=[sys.argv[0]], verbosity=2)
