"""Strict static archive/Mach-O inventory; no external tools or architecture guesses.

Command layouts: https://github.com/apple-oss-distributions/cctools/blob/main/include/mach-o/loader.h
This checks structural ranges and target metadata; it does not implement a linker.
"""
from __future__ import annotations

import hashlib
from pathlib import Path, PurePosixPath
import re
import struct


ARM64 = 0x0100000C
BUILD_VERSION = 0x32
LEGACY_IOS = 0x25
BSD_INDEXES = {"__.SYMDEF", "__.SYMDEF SORTED", "__.SYMDEF_64", "__.SYMDEF_64 SORTED"}


def _hash(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _range(data: bytes, offset: int, size: int, label: str) -> None:
    if offset < 0 or size < 0 or offset > len(data) or size > len(data) - offset:
        raise ValueError(f"Truncated/out-of-range {label}")


def _version(value: int) -> tuple[int, int, int]:
    return value >> 16, value >> 8 & 255, value & 255


def _version_text(value) -> str:
    return ".".join(map(str, value))


def _name(value: bytes) -> str:
    name = value.decode("utf-8")
    path = PurePosixPath(name)
    if (not path.parts or path.is_absolute() or path.as_posix() != name
            or ".." in path.parts or "\\" in name or ":" in name
            or any(ord(character) < 32 or ord(character) == 127 for character in name)):
        raise ValueError("Unsafe/noncanonical archive member name")
    return name


def inspect_object(data: bytes, *, expected_platform: int, max_min_os=(16, 0, 0),
                   allow_legacy_device_version_min: bool = False) -> dict:
    if expected_platform not in (2, 7):
        raise ValueError("Expected iOS device platform 2 or iOS simulator platform 7")
    maximum = tuple(max_min_os)
    if len(maximum) == 2: maximum += (0,)
    if len(maximum) != 3 or any(type(part) is not int or part < 0 for part in maximum):
        raise ValueError("Maximum minimum OS must be a two/three-component version tuple")
    if data[:4] != b"\xcf\xfa\xed\xfe":
        raise ValueError("Expected thin little-endian Mach-O64 object; fat, bitcode and other formats are unsupported")
    _range(data, 0, 32, "Mach-O header")
    _, cpu, subtype, file_type, count, size, flags, reserved = struct.unpack_from("<8I", data)
    if cpu != ARM64 or subtype != 0:
        raise ValueError("Expected generic arm64 object (arm64e/other architectures are unsupported)")
    if file_type != 1:
        raise ValueError("Archive member is not MH_OBJECT (shared libraries/executables are forbidden)")
    if reserved != 0 or count > size // 8:
        raise ValueError("Invalid Mach-O header/load-command count")
    _range(data, 32, size, "Mach-O load commands")
    position, end, versions = 32, 32 + size, []
    for _ in range(count):
        if position + 8 > end: raise ValueError("Truncated load-command header")
        command, command_size = struct.unpack_from("<2I", data, position)
        if command_size < 8 or command_size % 8 or position + command_size > end:
            raise ValueError("Invalid/truncated Mach-O load command")
        if command == BUILD_VERSION:
            if command_size < 24: raise ValueError("Truncated LC_BUILD_VERSION")
            platform, minimum, sdk, tools = struct.unpack_from("<4I", data, position + 8)
            if command_size != 24 + tools * 8: raise ValueError("Invalid build-version tool count")
            versions.append((platform, _version(minimum), "LC_BUILD_VERSION"))
        elif command in (0x24, LEGACY_IOS, 0x2F, 0x30):
            if command_size != 16: raise ValueError("Invalid legacy version-min command")
            if command != LEGACY_IOS or expected_platform != 2 or not allow_legacy_device_version_min:
                raise ValueError("Legacy platform metadata cannot establish this target; explicit device-only opt-in required")
            minimum, sdk = struct.unpack_from("<2I", data, position + 8)
            versions.append((2, _version(minimum), "LC_VERSION_MIN_IPHONEOS (explicit device-only allowance)"))
        elif command == 0x19:  # LC_SEGMENT_64 and its section/relocation payloads
            if command_size < 72: raise ValueError("Truncated segment command")
            file_offset, file_size = struct.unpack_from("<2Q", data, position + 40)
            sections = struct.unpack_from("<I", data, position + 64)[0]
            if command_size != 72 + sections * 80: raise ValueError("Invalid segment section count")
            _range(data, file_offset, file_size, "segment payload")
            for index in range(sections):
                section = position + 72 + index * 80
                section_size = struct.unpack_from("<Q", data, section + 40)[0]
                offset, alignment, relocations, relocation_count, section_flags = struct.unpack_from("<5I", data, section + 48)
                if section_flags & 255 not in (1, 0xC, 0x12):  # zero-fill sections have no file bytes
                    _range(data, offset, section_size, "section payload")
                _range(data, relocations, relocation_count * 8, "section relocations")
        elif command == 0x2:  # LC_SYMTAB; full defined-symbol inspection belongs to nm
            if command_size != 24: raise ValueError("Invalid symbol-table command")
            offset, symbols, string_offset, string_size = struct.unpack_from("<4I", data, position + 8)
            _range(data, offset, symbols * 16, "symbol table")
            _range(data, string_offset, string_size, "symbol strings")
            strings = data[string_offset:string_offset + string_size]
            for index in range(symbols):
                name_offset = struct.unpack_from("<I", data, offset + index * 16)[0]
                if name_offset >= len(strings) or b"\0" not in strings[name_offset:]:
                    raise ValueError("Invalid symbol string offset/termination")
        elif command == 0xB:  # LC_DYSYMTAB
            if command_size != 80: raise ValueError("Invalid dynamic-symbol-table command")
            fields = struct.unpack_from("<18I", data, position + 8)
            for index, entry_size in ((6, 8), (8, 56), (10, 4), (12, 4), (14, 8), (16, 8)):
                _range(data, fields[index], fields[index + 1] * entry_size, "dynamic-symbol-table payload")
        elif command == 0x2D:  # LC_LINKER_OPTION, a count of NUL-terminated UTF-8 strings
            if command_size < 16: raise ValueError("Truncated linker-option command")
            option_count = struct.unpack_from("<I", data, position + 8)[0]
            option_offset = position + 12
            for _ in range(option_count):
                option_end = data.find(b"\0", option_offset, position + command_size)
                if option_end < 0: raise ValueError("Unterminated linker option")
                data[option_offset:option_end].decode("utf-8")
                option_offset = option_end + 1
            if any(data[option_offset:position + command_size]): raise ValueError("Invalid linker-option padding")
        elif command in (0x1B, 0x2A):  # LC_UUID and LC_SOURCE_VERSION
            if command_size != (24 if command == 0x1B else 16): raise ValueError("Invalid fixed-size metadata command")
        elif command == 0x31:  # LC_NOTE
            if command_size != 40: raise ValueError("Invalid note command")
            _range(data, *struct.unpack_from("<2Q", data, position + 24), "note payload")
        elif command in (0x1D, 0x1E, 0x26, 0x29, 0x2B, 0x2E, 0x80000033, 0x80000034):
            if command_size != 16: raise ValueError("Invalid linkedit-data command")
            _range(data, *struct.unpack_from("<2I", data, position + 8), "linkedit payload")
        else:
            raise ValueError(f"Unsupported object load command 0x{command:x}; extend validation explicitly")
        position += command_size
    if position != end: raise ValueError("Load-command count does not consume declared command bytes")
    if len(versions) != 1: raise ValueError("Expected exactly one unambiguous platform/minimum-OS command")
    platform, minimum, origin = versions[0]
    if platform != expected_platform: raise ValueError(f"Wrong Mach-O platform {platform}; expected {expected_platform}")
    if minimum[0] == 0 or minimum > maximum:
        raise ValueError(f"Unsupported minimum OS {_version_text(minimum)}; maximum {_version_text(maximum)}")
    return {"architecture": "arm64", "cpuSubtype": subtype, "fileType": "MH_OBJECT", "platform": platform,
            "minimumOS": _version_text(minimum), "platformEvidence": origin, "loadCommands": count,
            "flags": flags, "bytes": len(data), "sha256": _hash(data)}


def _index_symbols(name: str, data: bytes, object_offsets: set[int]) -> list[str]:
    """Validate archive symbol-index structure, names and referenced member headers."""
    symbols = []
    if name in BSD_INDEXES:
        width = 8 if "_64" in name else 4
        integer = "<Q" if width == 8 else "<I"
        _range(data, 0, width, "BSD index length")
        table_size = struct.unpack_from(integer, data)[0]
        if table_size % (2 * width): raise ValueError("Invalid BSD symbol index size")
        _range(data, width, table_size + width, "BSD symbol index")
        string_size = struct.unpack_from(integer, data, width + table_size)[0]
        start = width + table_size + width
        _range(data, start, string_size, "BSD symbol index strings")
        strings = data[start:start + string_size]
        tail = data[start + string_size:]
        if len(tail) > 7 or any(tail): raise ValueError("Unexpected BSD index trailing data")
        for offset in range(width, width + table_size, width * 2):
            string_offset, member_offset = struct.unpack_from("<2Q" if width == 8 else "<2I", data, offset)
            if member_offset not in object_offsets: raise ValueError("BSD index references a non-object member")
            if string_offset >= len(strings): raise ValueError("BSD index string offset outside table")
            end = strings.find(b"\0", string_offset)
            if end < 0: raise ValueError("Unterminated BSD index symbol")
            symbols.append(strings[string_offset:end].decode("utf-8"))
    else:
        width = 8 if name == "/SYM64/" else 4
        integer = ">Q" if width == 8 else ">I"
        _range(data, 0, width, "GNU index length")
        count = struct.unpack_from(integer, data)[0]
        _range(data, width, count * width, "GNU symbol offsets")
        string_offset = width + count * width
        for index in range(count):
            member_offset = struct.unpack_from(integer, data, width + index * width)[0]
            if member_offset not in object_offsets: raise ValueError("GNU index references a non-object member")
            end = data.find(b"\0", string_offset)
            if end < 0: raise ValueError("Unterminated GNU index symbol")
            symbols.append(data[string_offset:end].decode("utf-8"))
            string_offset = end + 1
        tail = data[string_offset:]
        if len(tail) > 7 or any(tail): raise ValueError("Unexpected GNU index trailing data")
    if any(not name for name in symbols): raise ValueError("Empty archive-index symbol")
    return symbols


def inspect_archive(path: Path | str, *, expected_platform: int, max_min_os=(16, 0, 0),
                    allow_empty: bool = False, allow_legacy_device_version_min: bool = False) -> dict:
    path = Path(path)
    if expected_platform not in (2, 7): raise ValueError("Expected platform 2 or 7")
    if allow_empty and path.name != "libwebp_sse41.a":
        raise ValueError("Empty-archive allowance is restricted to libwebp_sse41.a")
    data = path.read_bytes()
    if data[:8] != b"!<arch>\n": raise ValueError("Expected regular ar archive; thin/external archives are forbidden")
    position, raw_members, long_names = 8, [], None
    while position < len(data):
        header_offset = position
        _range(data, position, 60, "ar member header")
        header = data[position:position + 60]
        if header[58:60] != b"`\n": raise ValueError("Invalid ar member header terminator")
        raw_name = header[:16].rstrip(b" ")
        for field, base in ((header[16:28], 10), (header[28:34], 10), (header[34:40], 10), (header[40:48], 8)):
            token = field.strip()
            if token and not re.fullmatch(b"[0-7]+" if base == 8 else b"[0-9]+", token):
                raise ValueError("Invalid ar numeric header")
        size_field = header[48:58].strip()
        if not re.fullmatch(b"[0-9]+", size_field): raise ValueError("Invalid ar member size")
        size = int(size_field)
        position += 60
        _range(data, position, size, "ar member payload")
        payload = data[position:position + size]
        position += size
        if position % 2:
            _range(data, position, 1, "ar alignment byte")
            if data[position:position + 1] != b"\n": raise ValueError("Invalid ar alignment byte")
            position += 1
        if raw_name.startswith(b"#1/"):
            if not re.fullmatch(b"[0-9]+", raw_name[3:]): raise ValueError("Invalid BSD long-name size")
            name_size = int(raw_name[3:])
            if not 0 < name_size <= len(payload): raise ValueError("Truncated BSD long name")
            name = _name(payload[:name_size].rstrip(b"\0"))
            payload = payload[name_size:]
        elif raw_name == b"//":
            if long_names is not None: raise ValueError("Duplicate GNU long-name table")
            long_names, name = payload, "//"
        elif raw_name in (b"/", b"/SYM64/"):
            name = raw_name.decode("ascii")
        elif raw_name.startswith(b"/"):
            if not re.fullmatch(b"/[0-9]+", raw_name): raise ValueError("Invalid GNU name reference")
            name = int(raw_name[1:])
        else:
            name = _name(raw_name[:-1] if raw_name.endswith(b"/") else raw_name)
        raw_members.append((name, header_offset, payload))
    gnu_names = {}
    if long_names is not None:
        offset = 0
        while offset < len(long_names):
            if long_names[offset:] == b"\n": break  # GNU string-table alignment
            end = long_names.find(b"/\n", offset)
            if end < 0: raise ValueError("Unterminated GNU long-name table entry")
            gnu_names[offset] = _name(long_names[offset:end])
            offset = end + 2
    members, indexes = [], []
    for name, offset, payload in raw_members:
        if isinstance(name, int):
            if name not in gnu_names: raise ValueError("GNU long-name offset is not an entry boundary")
            name = gnu_names[name]
        if name == "//": continue
        if name in BSD_INDEXES or name in ("/", "/SYM64/"):
            indexes.append((name, payload))
            continue
        info = inspect_object(payload, expected_platform=expected_platform, max_min_os=max_min_os,
                              allow_legacy_device_version_min=allow_legacy_device_version_min)
        members.append({"name": name, "headerOffset": offset, **info})
    if len(indexes) > 1: raise ValueError("Multiple archive symbol indexes are unsupported")
    symbols = []
    for name, payload in indexes:
        symbols.extend(_index_symbols(name, payload, {member["headerOffset"] for member in members}))
    if not members and not allow_empty: raise ValueError("Archive contains no Mach-O objects")
    return {"archive": path.name, "bytes": len(data), "sha256": _hash(data), "objectCount": len(members),
            "members": members, "platforms": sorted({member["platform"] for member in members}),
            "minimumOSVersions": sorted({member["minimumOS"] for member in members}),
            "symbolIndexCount": len(indexes), "archiveIndexSymbols": sorted(set(symbols)),
            "definedSymbolInspection": "Archive index only; use xcrun nm for full defined-symbol verification",
            "emptyAllowance": "libwebp_sse41.a" if not members else None}
