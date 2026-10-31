#!/usr/bin/env python3
"""Release gate S10 (doc 8 Section 6.1, BUG-2026-058): which network functions do the bundled native libraries import?

Usage: check_native_imports.py app.apk | directory-with-.so-files [...]

The screening path makes no network call, and only :sync:research may use the network (CLAUDE.md). Kotlin and Java code is
checked by the dependency rule and detekt; a native library bypasses both. This reads the undefined dynamic symbols of every
`lib/**/*.so` (a plain ELF parser, no tools needed) and fails when a library outside the allow-list imports a socket or
name-resolution function. `libsqlcipher.so` imports them because it is built from OpenSSL-era sources whose network code is
never called by the app; it is the only entry, and a new entry needs a reason here.
"""
import struct
import sys
import zipfile
from pathlib import Path

# Functions that open a connection or resolve a name. Importing one means the library can reach a network.
NETWORK_SYMBOLS = frozenset(
    {
        "socket",
        "socketpair",
        "connect",
        "bind",
        "listen",
        "accept",
        "accept4",
        "sendto",
        "sendmsg",
        "recvfrom",
        "recvmsg",
        "getaddrinfo",
        "getnameinfo",
        "gethostbyname",
        "gethostbyname2",
        "gethostbyaddr",
        "res_query",
        "android_getaddrinfofornet",
        "android_getaddrinfofornetcontext",
    }
)

# Library file name -> the network symbols it is allowed to import, and why.
ALLOWED = {
    # SQLCipher carries OpenSSL-derived code with socket helpers that no code path of the app reaches (the database is a file).
    "libsqlcipher.so": NETWORK_SYMBOLS,
}

ELF_MAGIC = b"\x7fELF"
SHT_DYNSYM = 11
SHN_UNDEF = 0


class NotElf(Exception):
    pass


def undefined_symbols(data: bytes) -> set[str]:
    """The names of the undefined (imported) dynamic symbols of an ELF shared object, 32- or 64-bit, little-endian."""
    if data[:4] != ELF_MAGIC:
        raise NotElf("not an ELF file")
    is64 = data[4] == 2
    if data[5] != 1:
        raise NotElf("only little-endian ELF is supported")
    if is64:
        (shoff,) = struct.unpack_from("<Q", data, 0x28)
        shentsize, shnum = struct.unpack_from("<HH", data, 0x3A)
    else:
        (shoff,) = struct.unpack_from("<I", data, 0x20)
        shentsize, shnum = struct.unpack_from("<HH", data, 0x2E)
    sections = []
    for i in range(shnum):
        base = shoff + i * shentsize
        if is64:
            _name, sh_type = struct.unpack_from("<II", data, base)
            _flags, _addr, offset, size = struct.unpack_from("<QQQQ", data, base + 8)
            link, _info = struct.unpack_from("<II", data, base + 40)
            _align, entsize = struct.unpack_from("<QQ", data, base + 48)
        else:
            _name, sh_type = struct.unpack_from("<II", data, base)
            _flags, _addr, offset, size = struct.unpack_from("<IIII", data, base + 8)
            link, _info = struct.unpack_from("<II", data, base + 24)
            _align, entsize = struct.unpack_from("<II", data, base + 32)
        sections.append((sh_type, offset, size, link, entsize))
    names: set[str] = set()
    for sh_type, offset, size, link, entsize in sections:
        if sh_type != SHT_DYNSYM or entsize == 0:
            continue
        _t, str_off, str_size, _l, _e = sections[link]
        strtab = data[str_off : str_off + str_size]
        for k in range(size // entsize):
            sym = offset + k * entsize
            if is64:
                name_off, _info, _other, shndx = struct.unpack_from("<IBBH", data, sym)
            else:
                name_off, _value, _size, _info, _other, shndx = struct.unpack_from("<IIIBBH", data, sym)
            if shndx != SHN_UNDEF or name_off == 0:
                continue
            end = strtab.index(b"\0", name_off)
            names.add(strtab[name_off:end].decode("ascii", "replace"))
    return names


def libraries(path: Path):
    """(display name, bytes) for every native library under an APK/AAB or a directory."""
    if path.is_dir():
        for so in sorted(path.rglob("*.so")):
            yield str(so.relative_to(path)).replace("\\", "/"), so.read_bytes()
    elif zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            for info in sorted(z.infolist(), key=lambda i: i.filename):
                if info.filename.endswith(".so"):
                    yield info.filename, z.read(info)
    else:
        raise FileNotFoundError(f"{path} is neither a directory nor an APK")


def check(path: Path) -> tuple[list[str], int]:
    """(problems, number of libraries read) for one APK or directory."""
    problems: list[str] = []
    count = 0
    for name, data in libraries(path):
        count += 1
        try:
            imported = undefined_symbols(data) & NETWORK_SYMBOLS
        except NotElf as e:
            problems.append(f"{name}: {e}")
            continue
        allowed = ALLOWED.get(Path(name).name, frozenset())
        extra = sorted(imported - allowed)
        if extra:
            problems.append(f"{name} imports network functions: {', '.join(extra)}")
    return problems, count


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: check_native_imports.py app.apk|dir [...]")
        return 2
    problems: list[str] = []
    total = 0
    for arg in argv[1:]:
        found, count = check(Path(arg))
        problems += found
        total += count
    if total == 0:
        print("S10 FAIL: no native libraries found to check (wrong path?)")
        return 1
    for p in problems:
        print("S10 FAIL:", p)
    if not problems:
        print(f"S10 PASS: {total} native libraries import no network functions beyond the allow-list")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
