#!/usr/bin/env python3
"""
lock-file.py — hold an exclusive file lock until Enter is pressed.

Usage:
    python lock-file.py <path>

Windows:  CreateFile with FILE_SHARE_NONE — blocks all other opens (reads and writes).
POSIX:    fcntl.flock LOCK_EX | LOCK_NB — advisory exclusive lock.
"""
import os
import sys


def lock_windows(path):
    import ctypes
    import ctypes.wintypes

    GENERIC_READ    = 0x80000000
    GENERIC_WRITE   = 0x40000000
    FILE_SHARE_NONE = 0x00000000
    OPEN_ALWAYS     = 4
    INVALID_HANDLE  = ctypes.c_void_p(-1).value

    CreateFileW = ctypes.windll.kernel32.CreateFileW
    CreateFileW.restype = ctypes.wintypes.HANDLE

    handle = CreateFileW(
        path,
        GENERIC_READ | GENERIC_WRITE,
        FILE_SHARE_NONE,
        None,
        OPEN_ALWAYS,
        0,
        None,
    )
    if handle == INVALID_HANDLE:
        err = ctypes.windll.kernel32.GetLastError()
        print(f"error: could not lock '{path}' (Windows error {err})", file=sys.stderr)
        sys.exit(1)

    print(f"locked  {path}")
    print("press Enter to release...")
    try:
        input()
    finally:
        ctypes.windll.kernel32.CloseHandle(handle)
        print("released")


def lock_posix(path):
    import fcntl

    fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        os.close(fd)
        print(f"error: could not lock '{path}' (already locked)", file=sys.stderr)
        sys.exit(1)

    print(f"locked  {path}")
    print("press Enter to release...")
    try:
        input()
    finally:
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)
        print("released")


def main():
    if len(sys.argv) != 2:
        print(f"usage: python {os.path.basename(__file__)} <file>", file=sys.stderr)
        sys.exit(1)

    path = sys.argv[1]

    if sys.platform == "win32":
        lock_windows(path)
    else:
        lock_posix(path)


if __name__ == "__main__":
    main()
