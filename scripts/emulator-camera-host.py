#!/usr/bin/env python3
"""Authenticated, local companion for an Android emulator environment camera.

Prepare the stopped AVD with emulator-environment.py --prepare, launch it with
-camera-back environment -grpc PORT -grpc-use-token, then run this server. Use
adb reverse tcp:8791 tcp:8791 for the Android broker's loopback connection.
Only Camera2 frames verified by the Android broker establish an active camera;
this process reports renderer configuration and image comparisons separately.
"""

from __future__ import annotations

import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
import math
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import uuid

from PIL import Image, ImageChops, ImageCms, ImageOps, ImageStat


ROOT = Path(__file__).resolve().parent.parent
DATA_ROOT = ROOT / "artifacts/emulator-host"
MAX_SOURCE = 256 * 1024 * 1024
MAX_JPEG = 16 * 1024 * 1024
MAX_IMAGE_PIXELS = 48_000_000
BACKEND = "android_emulator_environment"
HOST_PROTOCOL = 2
LEASE_SECONDS = 15.0
MATCH_THRESHOLD = 25.0
CANCELLATION_TTL_SECONDS = 600.0
MAX_CANCELLED_REQUESTS = 4096
MAX_PENDING_REQUESTS = 64

_spec = importlib.util.spec_from_file_location("vcam_environment", ROOT / "scripts/emulator-environment.py")
environment = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(environment)
_lock_spec = importlib.util.spec_from_file_location("vcam_owner_lock", ROOT / "scripts/emulator-camera-lock.py")
owner_lock = importlib.util.module_from_spec(_lock_spec)
_lock_spec.loader.exec_module(owner_lock)


class HostError(Exception):
    def __init__(self, code: str, message: str, status: int = 400):
        super().__init__(message)
        self.code = code
        self.status = status


def validate_request_id(value: str) -> str:
    try:
        canonical = str(uuid.UUID(value))
    except (ValueError, TypeError, AttributeError) as error:
        raise HostError("INVALID_REQUEST_ID", "X-Vcam-Request must contain a UUID") from error
    if canonical != value.lower():
        raise HostError("INVALID_REQUEST_ID", "X-Vcam-Request must contain a canonical UUID")
    return canonical


def validate_settings(raw: dict, camera_id: str) -> dict:
    if not isinstance(raw, dict):
        raise HostError("INVALID_SETTINGS", "Settings must be an object")
    if str(raw.get("cameraId", "")) != camera_id:
        raise HostError("CAMERA_UNSUPPORTED", "This companion controls only its configured environment camera")
    media_type = raw.get("type")
    if media_type not in ("image", "video"):
        raise HostError("INVALID_MEDIA_TYPE", "Media type must be image or video")
    width, height = raw.get("width"), raw.get("height")
    if type(width) is not int or type(height) is not int or (width, height) not in ((1280, 720), (1920, 1080), (720, 1280), (1080, 1920)):
        raise HostError("RESOLUTION_UNSUPPORTED", "Use 720p or 1080p dimensions")
    rotation = raw.get("rotation", 0)
    if type(rotation) is not int or rotation not in (0, 90, 180, 270):
        raise HostError("INVALID_ROTATION", "Rotation must be 0, 90, 180, or 270")
    scale = str(raw.get("scaleMode", "fit")).lower()
    if scale not in ("fit", "fill"):
        raise HostError("INVALID_SCALE_MODE", "Scale mode must be fit or fill")
    loop = raw.get("loop", True)
    if type(loop) is not bool:
        raise HostError("INVALID_LOOP", "Loop must be a boolean")
    zoom, pan_x, pan_y = raw.get("zoom", 1.0), raw.get("panX", 0.0), raw.get("panY", 0.0)
    if any(type(value) not in (int, float) or not math.isfinite(value) for value in (zoom, pan_x, pan_y)) or not 1 <= zoom <= 8 or not -1 <= pan_x <= 1 or not -1 <= pan_y <= 1:
        raise HostError("INVALID_TRANSFORM", "Zoom must be 1..8 and pan coordinates must be -1..1")
    return {"cameraId": camera_id, "type": media_type, "width": width, "height": height,
            "rotation": rotation, "scaleMode": scale, "loop": loop,
            "zoom": float(zoom), "panX": float(pan_x), "panY": float(pan_y)}


def load_image(path_or_stream) -> Image.Image:
    with Image.open(path_or_stream) as opened:
        if opened.width * opened.height > MAX_IMAGE_PIXELS:
            raise HostError("IMAGE_TOO_LARGE", "Image exceeds the companion's 48 megapixel limit")
        oriented = ImageOps.exif_transpose(opened)
        alpha = None
        if oriented.mode in ("RGBA", "LA") or "transparency" in oriented.info:
            alpha = oriented.convert("RGBA").getchannel("A")
        embedded_profile = oriented.info.get("icc_profile")
        if embedded_profile:
            try:
                profile = ImageCms.ImageCmsProfile(io.BytesIO(embedded_profile))
                profile_input = oriented
                if profile_input.mode == "LA":
                    profile_input = oriented.getchannel("L")
                elif profile_input.mode not in ("RGB", "RGBA", "CMYK", "L", "LAB"):
                    profile_input = oriented.convert("RGB")
                colored = ImageCms.profileToProfile(profile_input, profile, ImageCms.createProfile("sRGB"), outputMode="RGB")
            except (OSError, ValueError, ImageCms.PyCMSError) as error:
                raise HostError("IMAGE_COLOR_PROFILE_UNSUPPORTED", "Could not convert the embedded image color profile to sRGB") from error
        else:
            colored = oriented.convert("RGB")
        if alpha is not None:
            image = Image.new("RGB", colored.size, "black")
            image.paste(colored, mask=alpha)
        else:
            image = colored
        return image.copy()


def normalize_image(source: Path, destination: Path, settings: dict) -> None:
    image = load_image(source)
    if settings["rotation"]:
        image = image.rotate(-settings["rotation"], expand=True)
    size = (settings["width"], settings["height"])
    zoom, pan_x, pan_y = settings.get("zoom", 1.0), settings.get("panX", 0.0), settings.get("panY", 0.0)
    if (zoom, pan_x, pan_y) != (1.0, 0.0, 0.0):
        ratios = (size[0] / image.width, size[1] / image.height)
        scale = (max(ratios) if settings["scaleMode"] == "fill" else min(ratios)) * zoom
        content_width, content_height = image.width * scale, image.height * scale
        left = (size[0] - content_width) / 2 + pan_x * max(0.0, content_width - size[0]) / 2
        top = (size[1] - content_height) / 2 + pan_y * max(0.0, content_height - size[1]) / 2
        image = image.transform(size, Image.Transform.AFFINE, (1 / scale, 0, -left / scale, 0, 1 / scale, -top / scale),
                                resample=Image.Resampling.BICUBIC, fillcolor="black")
        image.save(destination, "PNG")
        return
    if settings["scaleMode"] == "fill":
        image = ImageOps.fit(image, size, method=Image.Resampling.LANCZOS)
    else:
        resized = ImageOps.contain(image, size, method=Image.Resampling.LANCZOS)
        image = Image.new("RGB", size, "black")
        image.paste(resized, ((size[0] - resized.width) // 2, (size[1] - resized.height) // 2))
    image.save(destination, "PNG")


def video_filter(settings: dict) -> str:
    filters = []
    rotation = settings["rotation"]
    if rotation == 90:
        filters.append("transpose=1")
    elif rotation == 180:
        filters.extend(("hflip", "vflip"))
    elif rotation == 270:
        filters.append("transpose=2")
    width, height = settings["width"], settings["height"]
    zoom, pan_x, pan_y = settings.get("zoom", 1.0), settings.get("panX", 0.0), settings.get("panY", 0.0)
    if (zoom, pan_x, pan_y) != (1.0, 0.0, 0.0):
        fit = "min" if settings["scaleMode"] == "fit" else "max"
        factor = f"{fit}({width}/iw\\,{height}/ih)*{zoom:.10g}"
        filters.append(f"scale=w='max(2\\,trunc(iw*({factor})/2)*2)':h='max(2\\,trunc(ih*({factor})/2)*2)'")
        # Crop the scaled media before padding so pan can reveal pixels outside
        # the original output viewport. Right/down pan follows the native EGL path.
        filters.append(f"crop=w='min(iw\\,{width})':h='min(ih\\,{height})':x='max(0\\,(iw-{width})*(1-({pan_x:.10g}))/2)':y='max(0\\,(ih-{height})*(1-({pan_y:.10g}))/2)'")
        filters.append(f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color=black")
        filters.extend(("setsar=1", "fps=30"))
        return ",".join(filters)
    if settings["scaleMode"] == "fit":
        filters.extend((f"scale={width}:{height}:force_original_aspect_ratio=decrease",
                        f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color=black"))
    else:
        filters.extend((f"scale={width}:{height}:force_original_aspect_ratio=increase",
                        f"crop={width}:{height}"))
    filters.extend(("setsar=1", "fps=30"))
    return ",".join(filters)


def run_ffmpeg(ffmpeg: Path, args: list[str], timeout: int = 180) -> str:
    try:
        result = subprocess.run([str(ffmpeg), "-hide_banner", "-loglevel", "error", "-nostdin", "-y", *args],
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=timeout, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except subprocess.TimeoutExpired as error:
        raise HostError("MEDIA_PROCESSING_TIMEOUT", "Media conversion exceeded the local processing limit") from error
    if result.returncode:
        detail = result.stderr.decode("utf-8", errors="replace")[-800:]
        raise HostError("MEDIA_DECODE_FAILED", "Could not decode the selected video: " + detail)
    return result.stdout.decode("utf-8", errors="replace")


def prepare_source(source: Path, session: Path, settings: dict, ffmpeg: Path) -> tuple[Path, list[Image.Image], dict]:
    playback = {"durationSeconds": None, "finalFrame": None}
    if settings["type"] == "image":
        output = session / "rendered.png"
        normalize_image(source, output, settings)
        references = [load_image(output)]
    else:
        output = session / "rendered.mp4"
        progress = run_ffmpeg(ffmpeg, ["-progress", "pipe:1", "-nostats", "-i", str(source), "-map", "0:v:0", "-an",
                                      "-vf", video_filter(settings), "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
                                      "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(output)])
        frame_counts = re.findall(r"(?m)^frame=(\d+)\s*$", progress)
        if not frame_counts or int(frame_counts[-1]) <= 0:
            raise HostError("VIDEO_TIMING_UNAVAILABLE", "Could not determine the normalized video's exact frame count")
        # video_filter produces CFR 30 fps. The encoder's actual frame count
        # gives its duration without rounded container metadata or ffprobe.
        playback["durationSeconds"] = int(frame_counts[-1]) / 30.0
        refs_dir = session / "reference-frames"
        refs_dir.mkdir()
        # These are decoded from the actual normalized output before loading it
        # into the renderer. 60 reference frames cover the first four seconds.
        run_ffmpeg(ffmpeg, ["-i", str(output), "-t", "4", "-vf", "fps=15,scale=160:90", "-frames:v", "60",
                            str(refs_dir / "%03d.png")], timeout=45)
        references = [load_image(path) for path in sorted(refs_dir.glob("*.png"))]
        if not settings["loop"]:
            final_frame = session / "final-frame.png"
            seek = max(0.0, playback["durationSeconds"] - 1.0)
            # Decode through EOF and overwrite one PNG for each frame. The
            # resulting file is the true last decoded output frame, with
            # bounded memory even for a long clip.
            run_ffmpeg(ffmpeg, ["-ss", f"{seek:.9f}", "-i", str(output), "-an", "-fps_mode", "passthrough",
                                "-update", "1", str(final_frame)], timeout=45)
            references.append(load_image(final_frame))
            playback["finalFrame"] = final_frame
    if not references:
        raise HostError("NO_REFERENCE_FRAMES", "The selected source has no decodable video frames")
    # Keep a bounded in-memory representation for repeated JPEG checks.
    references = [image.resize((64, 64), Image.Resampling.LANCZOS) for image in references]
    return output, references, playback


def compare_jpeg(jpeg: bytes, references: list[Image.Image]) -> dict:
    try:
        image = load_image(io.BytesIO(jpeg))
    except (OSError, ValueError, Image.DecompressionBombError) as error:
        raise HostError("INVALID_JPEG", "The camera proof is not a decodable image") from error
    best = math.inf
    best_global = math.inf
    best_detail = math.inf
    best_reference = -1
    best_orientation = ""
    for rotation in (0, 90, 180, 270):
        rotated = image.rotate(rotation, expand=True)
        for mirrored in (False, True):
            transformed = ImageOps.mirror(rotated) if mirrored else rotated
            thumbnail = transformed.resize((64, 64), Image.Resampling.LANCZOS)
            for index, reference in enumerate(references):
                difference = ImageChops.difference(thumbnail, reference)
                global_error = sum(ImageStat.Stat(difference).mean) / 3.0
                # Compare visible source content as well, so wide black fit
                # borders cannot make an unrelated black camera frame pass.
                mask = reference.convert("L").point(lambda value: 255 if value > 12 else 0)
                bounds = mask.getbbox()
                detail_error = sum(ImageStat.Stat(difference.crop(bounds)).mean) / 3.0 if bounds else global_error
                score = max(global_error, detail_error)
                if score < best:
                    best, best_global, best_detail = score, global_error, detail_error
                    best_reference, best_orientation = index, f"{rotation}:{'mirror' if mirrored else 'normal'}"
    return {"sourceVerified": best <= MATCH_THRESHOLD, "bestMeanAbsoluteError": round(best, 3),
            "fullFrameMeanAbsoluteError": round(best_global, 3), "contentMeanAbsoluteError": round(best_detail, 3),
            "referenceIndex": best_reference, "orientation": best_orientation, "threshold": MATCH_THRESHOLD}


def remove_session(path: Path) -> None:
    target = path.resolve()
    sessions = (DATA_ROOT / "sessions").resolve()
    if target == sessions or not target.is_relative_to(sessions):
        raise ValueError("Refusing to remove a path outside companion sessions")
    if target.exists():
        shutil.rmtree(target)


class CameraHost:
    def __init__(self, avd: str, camera_id: str, ffmpeg: Path, owner):
        if not owner.held or owner.avd != avd:
            raise HostError("OWNER_LOCK_REQUIRED", "Acquire the matching exclusive AVD owner lock before initializing the companion")
        self.owner = owner
        self.avd, self.camera_id, self.ffmpeg = avd, camera_id, ffmpeg
        self.lock = threading.RLock()
        self.activation_lock = threading.Lock()
        self.stop_event = threading.Event()
        self.current = None
        self.intent = 0
        self.pending_requests = {}
        self.cancelled_requests = {}
        self.recovery_required = False
        self.instance_id = uuid.uuid4().hex
        self.last_error = None
        self.restore_retry_at = 0.0
        self.discovery, self.info = environment.discover(avd, None)
        self.discovery_mtime = self.discovery.stat().st_mtime_ns
        environment_file = Path(self.info["avd.dir"]) / "environment.ini"
        if not environment_file.is_file():
            raise HostError("EMULATOR_NOT_PREPARED", "Stop the AVD and run emulator-environment.py --prepare before relaunching")
        state_file = ROOT / "artifacts/emulator-environment" / f"{avd}.json"
        if state_file.exists():
            saved = json.loads(state_file.read_text(encoding="utf-8"))
            if saved.get("avd") != avd or saved.get("avdDir") != self.info["avd.dir"]:
                raise HostError("BASELINE_MISMATCH", "Saved environment belongs to another AVD")
            self.baseline = saved["environmentConfig"]
        else:
            self.baseline = environment_file.read_text(encoding="utf-8-sig")
            state_file.parent.mkdir(parents=True, exist_ok=True)
            state_file.write_text(json.dumps({"avd": avd, "avdDir": self.info["avd.dir"],
                                              "environmentConfig": self.baseline}, indent=2), encoding="utf-8")
        DATA_ROOT.mkdir(parents=True, exist_ok=True)
        (DATA_ROOT / "sessions").mkdir(exist_ok=True)
        self.journal = DATA_ROOT / f"{avd}-session.json"
        self.check_reachable()
        # A previous process crash must not leave its source configured when
        # this companion resumes. The saved baseline is independent of it.
        if self.journal.exists():
            previous = json.loads(self.journal.read_text(encoding="utf-8"))
            if previous.get("rendererConfigured"):
                self.configure(self.baseline)
                self.recovery_required = False
                self.write_journal(False)
        self.write_journal(False)

    def check_reachable(self) -> None:
        import grpc
        _, live = environment.discover(self.avd, None)
        if live["avd.dir"] != self.info["avd.dir"] or live["grpc.token"] != self.info["grpc.token"]:
            raise HostError("EMULATOR_RESTARTED", "Restart the companion after the emulator restarts", 503)
        with grpc.insecure_channel(f"127.0.0.1:{int(live['grpc.port'])}") as channel:
            channel.unary_unary("/android.emulation.control.incubating.VirtualSceneService/getAnimationState")(
                b"", metadata=(("authorization", "Bearer " + live["grpc.token"]),), timeout=3)

    def configure(self, config: str) -> None:
        # Write recovery intent before the RPC: an interrupted/uncertain reply
        # must still trigger baseline restoration on this process or its next run.
        self.recovery_required = True
        self.write_journal(True)
        try:
            environment.reload_environment(self.info, config)
            # reloadEnvironment persists its live config. Keep the on-disk AVD
            # baseline instead, so a cold emulator boot restores its normal
            # source even if this host process was forcibly stopped.
            environment_file = (Path(self.info["avd.dir"]) / "environment.ini").resolve()
            if environment_file.parent != Path(self.info["avd.dir"]).resolve():
                raise ValueError("Invalid AVD environment path")
            environment_file.write_text(self.baseline, encoding="utf-8")
        except Exception as error:
            raise HostError("RENDERER_CONFIGURATION_FAILED", str(error), 502) from error

    def write_journal(self, configured: bool) -> None:
        temporary = self.journal.with_suffix(".host-tmp")
        temporary.write_text(json.dumps({"avd": self.avd, "rendererConfigured": configured,
                                        "generation": self.current["generation"] if self.current else None,
                                        "hostPid": os.getpid(), "instanceId": self.instance_id,
                                        "avdDir": self.info["avd.dir"], "discoveryFile": str(self.discovery),
                                        "discoveryMtimeNs": self.discovery_mtime}, indent=2), encoding="utf-8")
        temporary.replace(self.journal)

    def status(self, health: bool = False) -> dict:
        reachable = True
        support_error = None
        if health:
            try:
                self.check_reachable()
            except Exception as error:
                reachable = False
                support_error = {"code": error.code if isinstance(error, HostError) else "EMULATOR_UNREACHABLE",
                                 "message": str(error) if isinstance(error, HostError) else "The selected emulator's authenticated camera service is unavailable"}
        with self.lock:
            current = self.current
            return {"supported": reachable, "backend": BACKEND, "cameraId": self.camera_id, "hostProtocol": HOST_PROTOCOL,
                    "avd": self.avd, "serial": f"emulator-{self.info['port.serial']}",
                    "rendererConfigured": current is not None,
                    "sourceVerified": bool(current and current["verified"]),
                    "generation": current["generation"] if current else "",
                    "sourceSha256": current["sha256"] if current else "",
                    "leaseRemainingMs": max(0, int((current["lease"] - time.monotonic()) * 1000)) if current else 0,
                    "width": current["settings"]["width"] if current else 0,
                    "height": current["settings"]["height"] if current else 0,
                    "renderedMode": current["renderedMode"] if current else "baseline",
                    "durationMs": round(current["playback"]["durationSeconds"] * 1000) if current and current["playback"]["durationSeconds"] is not None else None,
                    "restorePending": self.recovery_required,
                    "lastError": self.last_error,
                    "code": support_error["code"] if support_error else "READY",
                    "message": support_error["message"] if support_error else "Emulator environment camera companion is ready",
                    "videoLoopModes": [True, False], "maxSourceBytes": MAX_SOURCE}

    def prune_cancellations(self) -> None:
        now = time.monotonic()
        for request_id, expires in list(self.cancelled_requests.items()):
            if expires <= now:
                del self.cancelled_requests[request_id]

    def check_pending(self, request_id: str, pending: dict) -> None:
        self.prune_cancellations()
        if (self.pending_requests.get(request_id) is not pending or pending["cancelled"]
                or request_id in self.cancelled_requests or pending["intent"] != self.intent):
            raise HostError("ACTIVATION_CANCELLED", "This camera activation request was cancelled", 409)

    def activate(self, stream, length: int, raw_settings: dict, request_id: str) -> dict:
        request_id = validate_request_id(request_id)
        settings = validate_settings(raw_settings, self.camera_id)
        # Register before waiting for the single conversion slot. A cancel can
        # therefore identify both queued and currently preparing requests.
        with self.lock:
            self.prune_cancellations()
            if request_id in self.cancelled_requests:
                raise HostError("ACTIVATION_CANCELLED", "This camera activation request was already cancelled", 409)
            if request_id in self.pending_requests or self.current and self.current.get("requestId") == request_id:
                raise HostError("REQUEST_ALREADY_REGISTERED", "This activation request was already registered", 409)
            if len(self.pending_requests) >= MAX_PENDING_REQUESTS:
                raise HostError("COMPANION_BUSY", "Too many camera activation requests are pending", 503)
            pending = {"cancelled": False, "intent": self.intent}
            self.pending_requests[request_id] = pending
        try:
            return self.activate_registered(stream, length, settings, request_id, pending)
        finally:
            with self.lock:
                if self.pending_requests.get(request_id) is pending:
                    del self.pending_requests[request_id]

    def activate_registered(self, stream, length: int, settings: dict, request_id: str, pending: dict) -> dict:
        with self.activation_lock:
            with self.lock:
                self.check_pending(request_id, pending)
            session = DATA_ROOT / "sessions" / uuid.uuid4().hex
            session.mkdir()
            source = session / "source.media"
            digest = hashlib.sha256()
            try:
                remaining = length
                with source.open("xb") as output:
                    while remaining:
                        chunk = stream.read(min(64 * 1024, remaining))
                        if not chunk:
                            raise HostError("INCOMPLETE_SOURCE", "Media upload ended before Content-Length")
                        output.write(chunk)
                        digest.update(chunk)
                        remaining -= len(chunk)
                        with self.lock:
                            self.check_pending(request_id, pending)
                rendered, references, playback = prepare_source(source, session, settings, self.ffmpeg)
                source.unlink()
                config = f"version=1\nscene.mode={'imagefile' if settings['type'] == 'image' else 'videofile'}:{rendered}\nbackground.enabled=false\n"
                with self.lock:
                    self.check_pending(request_id, pending)
                    self.check_reachable()
                    previous = self.current
                    render_requested_at = time.monotonic()
                    self.configure(config)
                    self.current = {"generation": uuid.uuid4().hex, "sha256": digest.hexdigest(), "settings": settings,
                                    "session": session, "references": references, "lease": time.monotonic() + LEASE_SECONDS,
                                    "verified": False, "renderedMode": settings["type"], "playback": playback, "finalTimer": None,
                                    "requestId": request_id}
                    self.recovery_required = False
                    self.last_error = None
                    self.write_journal(True)
                    if previous and previous.get("finalTimer"):
                        previous["finalTimer"].cancel()
                    if playback["finalFrame"] is not None:
                        # Start before the first possible wrap, measured from
                        # the renderer request rather than its later reply.
                        deadline = render_requested_at + max(0.0, playback["durationSeconds"] - 1.0 / 30.0)
                        timer = threading.Timer(max(0.0, deadline - time.monotonic()), self.hold_final_frame,
                                                args=(self.current["generation"],))
                        timer.daemon = True
                        self.current["finalTimer"] = timer
                        timer.start()
                    result = self.status()
                if previous:
                    try:
                        remove_session(previous["session"])
                    except OSError:
                        pass  # Closed Windows renderer handles can release asynchronously.
                return result
            except Exception:
                with self.lock:
                    owns_current = self.current and self.current["session"] == session
                if not owns_current:
                    try:
                        remove_session(session)
                    except OSError:
                        pass
                raise

    def cancel_request(self, request_id: str) -> dict:
        request_id = validate_request_id(request_id)
        with self.lock:
            self.prune_cancellations()
            self.cancelled_requests.pop(request_id, None)
            self.cancelled_requests[request_id] = time.monotonic() + CANCELLATION_TTL_SECONDS
            while len(self.cancelled_requests) > MAX_CANCELLED_REQUESTS:
                del self.cancelled_requests[next(iter(self.cancelled_requests))]
            pending = self.pending_requests.get(request_id)
            if pending is not None:
                pending["cancelled"] = True
            # A reply can be lost after configuration. Cancel that same request
            # if it is still current, without disturbing a newer request.
            if self.current and self.current.get("requestId") == request_id:
                result = self.deactivate(self.current["generation"], cancel_pending=False)
            else:
                result = self.status()
            return {**result, "requestCancelled": True, "cancelledRequest": request_id}

    def hold_final_frame(self, generation: str) -> bool:
        with self.lock:
            current = self.current
            if (not current or current["generation"] != generation or current["renderedMode"] != "video"
                    or current["settings"]["loop"] or current["lease"] <= time.monotonic()):
                return False
            final_frame = current["playback"]["finalFrame"]
            if final_frame is None:
                return False
            try:
                self.configure(f"version=1\nscene.mode=imagefile:{final_frame}\nbackground.enabled=false\n")
                current["renderedMode"] = "final_frame"
                current["finalTimer"] = None
                self.recovery_required = False
                self.write_journal(True)
                return True
            except Exception as error:
                # An unsuccessful/uncertain end-frame switch restores the
                # baseline through the existing recovery watchdog.
                self.last_error = {"code": "FINAL_FRAME_HOLD_FAILED", "message": str(error)}
                return False

    def require_generation(self, generation: str) -> dict:
        if not self.current:
            raise HostError("NOT_CONFIGURED", "There is no configured camera source", 409)
        if not generation or generation != self.current["generation"]:
            raise HostError("STALE_GENERATION", "The request belongs to an older camera source", 409)
        return self.current

    def heartbeat(self, generation: str) -> dict:
        with self.lock:
            current = self.require_generation(generation)
            current["lease"] = time.monotonic() + LEASE_SECONDS
            return self.status()

    def verify(self, generation: str, jpeg: bytes) -> dict:
        with self.lock:
            current = self.require_generation(generation)
            comparison = compare_jpeg(jpeg, current["references"])
            current["verified"] = comparison["sourceVerified"]
            (current["session"] / "verification-latest.jpg").write_bytes(jpeg)
            (current["session"] / "verification.json").write_text(json.dumps(comparison, indent=2), encoding="utf-8")
            return {**comparison, "generation": generation, "backend": BACKEND, "cameraId": self.camera_id, "hostProtocol": HOST_PROTOCOL}

    def deactivate(self, generation: str = "", cancel_pending: bool = True) -> dict:
        with self.lock:
            if generation:
                self.require_generation(generation)
            if cancel_pending:
                self.intent += 1
            previous = self.current
            if previous and previous.get("finalTimer"):
                previous["finalTimer"].cancel()
            if previous or self.recovery_required:
                self.configure(self.baseline)
            self.current = None
            self.recovery_required = False
            self.last_error = None
            self.write_journal(False)
            result = self.status()
        if previous:
            try:
                remove_session(previous["session"])
            except OSError:
                pass
        return result

    def watchdog(self) -> None:
        while not self.stop_event.wait(0.5):
            with self.lock:
                expired = self.recovery_required or self.current and self.current["lease"] <= time.monotonic()
                if not expired or time.monotonic() < self.restore_retry_at:
                    continue
                try:
                    self.deactivate(cancel_pending=False)
                except Exception as error:
                    self.last_error = {"code": "RESTORE_FAILED", "message": str(error)}
                    self.restore_retry_at = time.monotonic() + 2


class CompanionServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = False

    def server_bind(self):
        # Windows SO_REUSEADDR permits overlapping live listeners. Exclusive
        # ownership makes a duplicate bind fail before any emulator work.
        if os.name == "nt":
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, _format, *_args):
        pass

    def setup(self):
        super().setup()
        self.connection.settimeout(30)

    def send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, allow_nan=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def authorize(self) -> None:
        supplied = self.headers.get("Authorization", "")
        if not secrets.compare_digest(supplied, "Bearer " + self.server.token):
            raise HostError("UNAUTHORIZED", "Companion authentication required", 401)

    def dispatch(self, post: bool) -> None:
        try:
            self.authorize()
            camera = self.server.camera
            if not post:
                if self.path not in ("/support", "/status"):
                    raise HostError("NOT_FOUND", "Unknown companion endpoint", 404)
                result = camera.status(health=True)
            else:
                if self.headers.get("Transfer-Encoding"):
                    raise HostError("LENGTH_REQUIRED", "Use Content-Length rather than chunked transfer", 411)
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                except ValueError as error:
                    raise HostError("INVALID_LENGTH", "Invalid Content-Length") from error
                limit = MAX_SOURCE if self.path == "/activate" else MAX_JPEG if self.path == "/verify" else 1024
                if length < 0 or length > limit:
                    raise HostError("BODY_TOO_LARGE", "Request exceeds the endpoint size limit", 413)
                generation = self.headers.get("X-Vcam-Generation", "")
                if self.path == "/activate":
                    if length == 0:
                        raise HostError("EMPTY_SOURCE", "Selected media is empty")
                    settings_json = self.headers.get("X-Vcam-Settings", "")
                    if len(settings_json) > 8192:
                        raise HostError("INVALID_SETTINGS", "Settings header exceeds its limit")
                    try:
                        settings = json.loads(settings_json)
                    except ValueError as error:
                        raise HostError("INVALID_SETTINGS", "Settings header must contain valid JSON") from error
                    result = camera.activate(self.rfile, length, settings, self.headers.get("X-Vcam-Request", ""))
                elif self.path == "/verify":
                    if length == 0:
                        raise HostError("EMPTY_JPEG", "Camera verification JPEG is empty")
                    jpeg = self.rfile.read(length)
                    if len(jpeg) != length:
                        raise HostError("INCOMPLETE_JPEG", "Camera verification upload ended early")
                    result = camera.verify(generation, jpeg)
                elif self.path in ("/heartbeat", "/deactivate", "/cancel"):
                    if length:
                        self.rfile.read(length)
                    if self.path == "/cancel":
                        result = camera.cancel_request(self.headers.get("X-Vcam-Request", ""))
                    else:
                        result = camera.heartbeat(generation) if self.path == "/heartbeat" else camera.deactivate(generation)
                else:
                    raise HostError("NOT_FOUND", "Unknown companion endpoint", 404)
            self.send_json(200, result)
        except HostError as error:
            self.send_json(error.status, {"code": error.code, "message": str(error), "backend": BACKEND, "hostProtocol": HOST_PROTOCOL})
        except Exception:
            self.send_json(500, {"code": "COMPANION_ERROR", "message": "The local companion could not complete the request", "backend": BACKEND, "hostProtocol": HOST_PROTOCOL})

    def do_GET(self):
        self.dispatch(False)

    def do_POST(self):
        self.dispatch(True)


def serve_companion(avd: str, camera_id: str, port: int, ffmpeg: Path) -> int:
    # Binding and exclusive AVD ownership precede journal recovery, renderer
    # calls, and connection-file writes. A failed duplicate has no such effects.
    server = CompanionServer(("127.0.0.1", port), Handler)
    try:
        with owner_lock.AvdProcessLock(avd, DATA_ROOT / "locks") as owner:
            camera = CameraHost(avd, camera_id, ffmpeg, owner)
            try:
                server.camera = camera
                server.token = secrets.token_urlsafe(32)
                (DATA_ROOT / "connection.json").write_text(json.dumps({
                    "url": f"http://127.0.0.1:{port}", "token": server.token,
                    "cameraId": camera_id, "backend": BACKEND,
                    "hostProtocol": HOST_PROTOCOL,
                    "avd": avd, "serial": f"emulator-{camera.info['port.serial']}",
                }, indent=2), encoding="utf-8")
                subprocess.Popen([sys.executable, str(ROOT / "scripts/emulator-camera-watchdog.py"),
                                  "--avd", avd, "--watch-parent", str(os.getpid()), "--instance", camera.instance_id],
                                 stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                 close_fds=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
                threading.Thread(target=camera.watchdog, name="camera-lease", daemon=True).start()
                print(json.dumps({"listening": f"127.0.0.1:{port}", "avd": avd, "cameraId": camera_id,
                                  "connectionFile": str(DATA_ROOT / "connection.json")}), flush=True)
                try:
                    server.serve_forever(poll_interval=0.5)
                except KeyboardInterrupt:
                    pass
            finally:
                camera.stop_event.set()
                camera.deactivate()
    finally:
        server.server_close()
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--avd", required=True)
    parser.add_argument("--camera-id", default="10")
    parser.add_argument("--port", default=8791, type=int)
    parser.add_argument("--ffmpeg", type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", args.avd):
        parser.error("AVD name contains invalid characters")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", args.camera_id):
        parser.error("Camera ID contains invalid characters")
    if not 1024 <= args.port <= 65535:
        parser.error("Port must be between 1024 and 65535")
    ffmpeg = args.ffmpeg or next(iter((ROOT / "tools/ffmpeg/imageio_ffmpeg/binaries").glob("ffmpeg*.exe")), None)
    if ffmpeg is None or not ffmpeg.is_file():
        parser.error("Provide an existing FFmpeg executable using --ffmpeg")
    return serve_companion(args.avd, args.camera_id, args.port, ffmpeg.resolve())


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (HostError, ValueError, OSError) as error:
        print(f"Companion failed: {error}", file=sys.stderr)
        raise SystemExit(1)
