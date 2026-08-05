"""Delete files to the Recycle Bin instead of destroying them.

Removing a mod deletes a file the user may have downloaded once and cannot get
back, so Blueprint asks the Windows shell to do it - the file lands in the
Recycle Bin and a mistake can be undone from Explorer. If the shell call is not
available (or fails) the caller decides whether a permanent delete is worth it.
"""

import ctypes
import os
import sys
from ctypes import wintypes

FO_DELETE = 0x0003
FOF_SILENT = 0x0004
FOF_NOCONFIRMATION = 0x0010
FOF_ALLOWUNDO = 0x0040
FOF_NOERRORUI = 0x0400


class _SHFILEOPSTRUCTW(ctypes.Structure):
    _fields_ = [
        ("hwnd", wintypes.HWND),
        ("wFunc", wintypes.UINT),
        ("pFrom", wintypes.LPCWSTR),
        ("pTo", wintypes.LPCWSTR),
        ("fFlags", ctypes.c_uint16),
        ("fAnyOperationsAborted", wintypes.BOOL),
        ("hNameMappings", ctypes.c_void_p),
        ("lpszProgressTitle", wintypes.LPCWSTR),
    ]


def available():
    return sys.platform.startswith("win")


def send_to_recycle_bin(paths):
    """Recycle every path in one shell operation. Returns True on success."""
    paths = [os.path.abspath(path) for path in paths if path]
    if not paths or not available():
        return False

    operation = _SHFILEOPSTRUCTW()
    operation.wFunc = FO_DELETE
    # The shell expects a list of null-terminated names, terminated by a
    # second null.
    operation.pFrom = "\0".join(paths) + "\0\0"
    operation.pTo = None
    operation.fFlags = FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT | FOF_NOERRORUI

    try:
        result = ctypes.windll.shell32.SHFileOperationW(ctypes.byref(operation))
    except (AttributeError, OSError):
        return False

    return result == 0 and not operation.fAnyOperationsAborted
