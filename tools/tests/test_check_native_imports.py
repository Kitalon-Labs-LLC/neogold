"""
tools/tests/test_check_native_imports.py

Gate S10 (BUG-2026-058, TC-PRIV-01, TC-SEC-01): a native library that imports a socket or name-resolution function fails the
release, unless it is the one allow-listed library. The ELF files here are built in the test, so nothing depends on a build.
"""

import struct
import zipfile
from pathlib import Path

from tools.check_native_imports import ALLOWED, NETWORK_SYMBOLS, check, main, undefined_symbols


def make_elf64(imports: list[str], defined: list[str] | None = None) -> bytes:
    """A minimal 64-bit little-endian ELF shared object with a .dynsym and .dynstr that name these symbols."""
    defined = defined or []
    strtab = b"\0"
    offsets = {}
    for name in imports + defined:
        offsets[name] = len(strtab)
        strtab += name.encode() + b"\0"
    syms = b"\0" * 24  # the null symbol
    for name in imports:
        syms += struct.pack("<IBBHQQ", offsets[name], 0x10, 0, 0, 0, 0)  # global, shndx UNDEF
    for name in defined:
        syms += struct.pack("<IBBHQQ", offsets[name], 0x12, 0, 1, 0x1000, 8)  # global function, defined in section 1
    header_size = 64
    dynstr_off = header_size
    dynsym_off = dynstr_off + len(strtab)
    dynsym_off += (-dynsym_off) % 8
    shoff = dynsym_off + len(syms)
    shoff += (-shoff) % 8

    def shdr(sh_type, offset, size, link, entsize):
        return struct.pack("<IIQQQQIIQQ", 0, sh_type, 0, 0, offset, size, link, 0, 1, entsize)

    sections = shdr(0, 0, 0, 0, 0)  # null
    sections += shdr(11, dynsym_off, len(syms), 2, 24)  # .dynsym, linked to section 2
    sections += shdr(3, dynstr_off, len(strtab), 0, 0)  # .dynstr
    ident = b"\x7fELF" + bytes([2, 1, 1]) + b"\0" * 9
    header = ident + struct.pack("<HHIQQQIHHHHHH", 3, 183, 1, 0, 0, shoff, 0, 64, 0, 0, 64, 3, 0)
    body = header + strtab
    body += b"\0" * (dynsym_off - len(body)) + syms
    body += b"\0" * (shoff - len(body)) + sections
    return body


def test_the_reader_lists_imported_symbols_and_ignores_defined_ones():
    data = make_elf64(imports=["malloc", "socket"], defined=["Java_org_example_Run"])
    assert undefined_symbols(data) == {"malloc", "socket"}


def test_a_library_with_no_network_imports_passes(tmp_path: Path):
    (tmp_path / "libgood.so").write_bytes(make_elf64(["malloc", "memcpy", "fopen"]))
    problems, count = check(tmp_path)
    assert (problems, count) == ([], 1)
    assert main(["check", str(tmp_path)]) == 0


def test_a_library_that_imports_a_socket_function_fails(tmp_path: Path, capsys):
    (tmp_path / "libbad.so").write_bytes(make_elf64(["malloc", "getaddrinfo", "connect"]))
    assert main(["check", str(tmp_path)]) == 1
    out = capsys.readouterr().out
    assert "libbad.so imports network functions: connect, getaddrinfo" in out


def test_the_allow_listed_library_may_import_them_but_a_renamed_copy_may_not(tmp_path: Path):
    data = make_elf64(sorted(NETWORK_SYMBOLS)[:3])
    (tmp_path / "libsqlcipher.so").write_bytes(data)
    assert check(tmp_path)[0] == []
    (tmp_path / "libsqlcipher.so").unlink()
    (tmp_path / "libother.so").write_bytes(data)
    assert len(check(tmp_path)[0]) == 1


def test_only_sqlcipher_is_allow_listed():
    assert set(ALLOWED) == {"libsqlcipher.so"}


def test_an_apk_is_read_from_its_lib_folder(tmp_path: Path):
    apk = tmp_path / "app.apk"
    with zipfile.ZipFile(apk, "w") as z:
        z.writestr("lib/arm64-v8a/libgood.so", make_elf64(["malloc"]))
        z.writestr("lib/arm64-v8a/libbad.so", make_elf64(["socket"]))
        z.writestr("classes.dex", b"not a library")
    problems, count = check(apk)
    assert count == 2
    assert problems == ["lib/arm64-v8a/libbad.so imports network functions: socket"]


def test_a_file_that_is_not_elf_is_reported(tmp_path: Path):
    (tmp_path / "libfake.so").write_bytes(b"MZ not an elf")
    problems, _ = check(tmp_path)
    assert problems and "libfake.so" in problems[0]


def test_no_libraries_at_all_fails_so_a_wrong_path_cannot_pass(tmp_path: Path, capsys):
    assert main(["check", str(tmp_path)]) == 1
    assert "no native libraries found" in capsys.readouterr().out
