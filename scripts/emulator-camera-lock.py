"""An OS released, exclusive owner lock for one emulator camera companion."""

import os
from pathlib import Path
import re


class OwnerBusyError(OSError):
    pass


class AvdProcessLock:
    def __init__(self, avd: str, directory: Path):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", avd):
            raise ValueError("Invalid AVD identity")
        self.avd = avd
        self.directory = directory
        self.handle = None

    @property
    def held(self) -> bool:
        return self.handle is not None

    def acquire(self):
        if self.held:
            raise ValueError("Owner lock is already held")
        self.directory.mkdir(parents=True, exist_ok=True)
        handle = (self.directory / f"{self.avd}.lock").open("a+b")
        try:
            if handle.seek(0, os.SEEK_END) == 0:
                handle.write(b"\0")
                handle.flush()
            handle.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            handle.close()
            raise OwnerBusyError(f"An emulator camera owner is already running for {self.avd}") from error
        self.handle = handle
        return self

    def close(self):
        handle, self.handle = self.handle, None
        if handle is None:
            return
        try:
            handle.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        finally:
            handle.close()

    def __enter__(self):
        return self.acquire()

    def __exit__(self, *_args):
        self.close()
