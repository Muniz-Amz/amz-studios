#!/usr/bin/env python3
"""Check the final APK for the MusicAmz 1.2.1 reflective ZIP-handler regression.

Python 3.9+; standard library only. This reads APK/mapping files and changes nothing.
The explicit handler list is the register(Class) list in Commons Compress 1.12's
ExtraFieldUtils static initializer, as shipped by youtubedl-android 0.18.1.
"""

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import struct
import sys
import zipfile


ZIP_PACKAGE = "org.apache.commons.compress.archivers.zip."
REGISTERED_HANDLERS = (
    "AsiExtraField",
    "X5455_ExtendedTimestamp",
    "X7875_NewUnix",
    "JarMarker",
    "UnicodePathExtraField",
    "UnicodeCommentExtraField",
    "Zip64ExtendedInformationExtraField",
    "X000A_NTFS",
    "X0014_X509Certificates",
    "X0015_CertificateIdForFile",
    "X0016_CertificateIdForCentralDirectory",
    "X0017_StrongEncryptionHeader",
    "X0019_EncryptionRecipientCertificateList",
)
PUBLIC = 0x1
STATIC = 0x8
NATIVE = 0x100
INTERFACE = 0x200
ABSTRACT = 0x400


@dataclass(frozen=True)
class Method:
    name: str
    descriptor: str
    flags: int
    code_offset: int


@dataclass(frozen=True)
class ClassDefinition:
    flags: int
    methods: tuple
    dex_name: str


class Dex:
    """Read only the DEX tables needed to inspect class/constructor definitions."""

    def __init__(self, data, name):
        self.data = data
        self.name = name
        if len(data) < 112 or not re.fullmatch(rb"dex\n0(?:3[5-9]|40)\x00", data[:8]):
            raise ValueError(f"{name}: unsupported DEX format (expected 035-040)")
        if self.u32(32) != len(data) or self.u32(36) != 112:
            raise ValueError(f"{name}: invalid DEX file/header size")
        if self.u32(40) != 0x12345678:
            raise ValueError(f"{name}: unsupported DEX byte order")
        self.strings = [self.string(self.u32(o)) for o in self.table(56, 4)]
        self.types = [self.strings[self.u32(o)] for o in self.table(64, 4)]
        self.prototypes = [self.prototype(o) for o in self.table(72, 12)]
        self.method_ids = [
            (self.types[self.u16(o)], self.strings[self.u32(o + 4)],
             self.prototypes[self.u16(o + 2)])
            for o in self.table(88, 8)
        ]

    def u16(self, offset):
        return struct.unpack_from("<H", self.data, offset)[0]

    def u32(self, offset):
        return struct.unpack_from("<I", self.data, offset)[0]

    def table(self, header_offset, item_size):
        count, offset = self.u32(header_offset), self.u32(header_offset + 4)
        if offset + count * item_size > len(self.data):
            raise ValueError(f"{self.name}: DEX table exceeds file bounds")
        return range(offset, offset + count * item_size, item_size)

    def uleb(self, offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = self.data[offset]
            offset += 1
            value |= (byte & 0x7F) << shift
            if byte < 0x80:
                return value, offset
        raise ValueError(f"{self.name}: invalid ULEB128 value")

    def string(self, offset):
        _, offset = self.uleb(offset)
        end = self.data.index(0, offset)
        # Relevant descriptors/names are ASCII. Other DEX strings may use MUTF-8.
        return self.data[offset:end].decode("utf-8", errors="replace")

    def prototype(self, offset):
        parameters_offset = self.u32(offset + 8)
        parameters = ""
        if parameters_offset:
            count = self.u32(parameters_offset)
            if parameters_offset + 4 + count * 2 > len(self.data):
                raise ValueError(f"{self.name}: invalid parameter list")
            parameters = "".join(
                self.types[self.u16(parameters_offset + 4 + i * 2)]
                for i in range(count)
            )
        return "(" + parameters + ")" + self.types[self.u32(offset + 4)]

    def class_methods(self, offset, owner):
        if not offset:
            return ()
        counts = []
        for _ in range(4):
            count, offset = self.uleb(offset)
            counts.append(count)
        # Skip encoded static/instance fields: each has an index delta and flags.
        for _ in range(counts[0] + counts[1]):
            _, offset = self.uleb(offset)
            _, offset = self.uleb(offset)
        methods = []
        for count in counts[2:]:
            index = 0
            for _ in range(count):
                delta, offset = self.uleb(offset)
                index += delta
                flags, offset = self.uleb(offset)
                code_offset, offset = self.uleb(offset)
                method_owner, name, descriptor = self.method_ids[index]
                if method_owner != owner:
                    raise ValueError(f"{self.name}: method belongs to another class")
                methods.append(Method(name, descriptor, flags, code_offset))
        return tuple(methods)

    def classes(self):
        for offset in self.table(96, 32):
            name = self.types[self.u32(offset)]
            yield name, ClassDefinition(
                self.u32(offset + 4),
                self.class_methods(self.u32(offset + 24), name),
                self.name,
            )


def read_mapping(path):
    mappings = {}
    with path.open(encoding="utf-8") as source:
        for line in source:
            match = re.fullmatch(r"(\S+) -> (\S+):\s*", line)
            if match:
                original, renamed = match.groups()
                if original in mappings and mappings[original] != renamed:
                    raise ValueError(f"Conflicting class mapping: {original}")
                mappings[original] = renamed
    return mappings


def check(apk, mapping, handlers=REGISTERED_HANDLERS):
    mappings = read_mapping(mapping)
    classes = {}
    dex_count = 0
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes(?:[1-9][0-9]*)?\.dex", name):
                continue
            dex_count += 1
            for descriptor, definition in Dex(archive.read(name), name).classes():
                if descriptor in classes:
                    raise ValueError(f"Duplicate DEX class: {descriptor}")
                classes[descriptor] = definition
    if not dex_count:
        raise ValueError("APK contains no root classes*.dex files")

    failures = []
    for handler in handlers:
        original = ZIP_PACKAGE + handler
        renamed = mappings.get(original)
        if not renamed:
            failures.append(f"{handler}: absent from mapping (wrong build or removed class)")
            continue
        definition = classes.get("L" + renamed.replace(".", "/") + ";")
        if not definition:
            failures.append(f"{handler} -> {renamed}: absent from APK")
            continue
        problems = []
        if not definition.flags & PUBLIC:
            problems.append("class is not public")
        if definition.flags & (ABSTRACT | INTERFACE):
            problems.append("class is abstract or an interface")
        constructors = [m for m in definition.methods
                        if m.name == "<init>" and m.descriptor == "()V"]
        if not any(m.flags & PUBLIC and not m.flags & (STATIC | NATIVE | ABSTRACT)
                   and m.code_offset for m in constructors):
            problems.append("no public concrete zero-argument constructor")
        if problems:
            failures.append(f"{handler} -> {renamed} ({definition.dex_name}): "
                            + "; ".join(problems))
    return failures, dex_count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="final signed release APK")
    parser.add_argument("mapping", type=Path, help="mapping.txt from that exact build")
    parser.add_argument("--handler", action="append", choices=REGISTERED_HANDLERS,
                        help="check only this handler; repeatable, diagnostic use only")
    args = parser.parse_args()
    handlers = tuple(dict.fromkeys(args.handler)) if args.handler else REGISTERED_HANDLERS
    try:
        failures, dex_count = check(args.apk, args.mapping, handlers)
    except (OSError, ValueError, IndexError, struct.error, zipfile.BadZipFile) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2
    if failures:
        print(f"FAIL: {len(failures)} reflective ZIP handlers are not instantiable.")
        for failure in failures:
            print("  " + failure)
        return 1
    scope = "selected" if args.handler else "all"
    print(f"PASS: {scope} {len(handlers)} reflective ZIP handlers retain public "
          f"zero-argument constructors in {args.apk.name} ({dex_count} DEX file(s)).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
