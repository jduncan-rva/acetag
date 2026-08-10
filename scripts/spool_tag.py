#!/usr/bin/env python3
"""
Generate raw NFC page data for Anycubic ACE Pro compatible filament tags.

No reader hardware or web server required. This prints a hex blob you can
paste into a phone NFC app (e.g. NFC Tools Pro -> Other -> Write raw data)
along with the start page to write it at.

Format reverse-engineered by https://github.com/Molodos/anycubic-nfc-filament
(see format.md in that repo). Tag is an NTAG213: pages 0x00-0x03 and
0x28-0x2c are chip/management data and must never be touched. Pages
0x04-0x27 (36 pages / 144 bytes) hold the filament spec and are what this
tool produces.
"""

import argparse
import sys

START_PAGE = 0x04
END_PAGE = 0x27  # inclusive
PAGE_COUNT = END_PAGE - START_PAGE + 1

SKUS = {
    "PLA": "AHPLBK-101",
    "PLA+": "AHPLPBK-102",
    "PLA High Speed": "AHHSBK-102",
    "PLA Matte": "HYGBK-101",
    "PLA Silk": "HSCWH-101",
    "PETG": "HPEBK-103",
    "ASA": "HASBK-101",
    "ABS": "HABBK-102",
    "TPU": "HTPBK-101",
    "PLA Luminous": "HFGBL-101",
}


class TagPages:
    def __init__(self):
        self.pages = [bytearray(4) for _ in range(PAGE_COUNT)]

    def _rel(self, page):
        idx = page - START_PAGE
        if not (0 <= idx < PAGE_COUNT):
            raise ValueError(f"page {page:#x} outside writable range 0x04-0x27")
        return idx

    def write_byte(self, page, index, value):
        self.pages[self._rel(page)][index] = value & 0xFF

    def write_u16(self, page, index, value):
        low, high = value % 256, value // 256
        self.write_byte(page, index, low)
        if index == 3:
            self.write_byte(page + 1, 0, high)
        else:
            self.write_byte(page, index + 1, high)

    def write_string(self, page, text, max_len=20):
        for i in range(min(len(text), max_len)):
            self.write_byte(page + i // 4, i % 4, ord(text[i]))

    def write_color(self, page, hex_color):
        hex_color = hex_color.lstrip("#")
        r, g, b = (int(hex_color[i:i + 2], 16) for i in (0, 2, 4))
        self.write_byte(page, 0, 0xFF)  # alpha/opacity
        self.write_byte(page, 1, b)
        self.write_byte(page, 2, g)
        self.write_byte(page, 3, r)

    def hex_blob(self):
        return "".join(page.hex() for page in self.pages)

    def page_dump(self):
        lines = []
        for i, page in enumerate(self.pages):
            lines.append(f"[Page {START_PAGE + i:02x}] {page.hex(':')}")
        return "\n".join(lines)


def build_tag(spec):
    t = TagPages()

    # Static markers
    t.write_byte(0x04, 0, 0x7B)
    t.write_byte(0x04, 2, 0x65)  # format version 2
    t.write_byte(0x27, 3, 0x4D)  # custom spool marker

    t.write_string(0x05, SKUS.get(spec["type"], "AHPLBK-101"))
    t.write_string(0x0A, spec.get("manufacturer", "AC"))
    t.write_string(0x0F, spec["type"])

    t.write_color(0x14, spec["color"])

    t.write_u16(0x17, 0, spec.get("speed_min", 0))
    t.write_u16(0x17, 2, spec.get("speed_max", 0))
    t.write_u16(0x18, 0, spec["nozzle_min"])
    t.write_u16(0x18, 2, spec["nozzle_max"])

    t.write_u16(0x1D, 0, spec["bed_min"])
    t.write_u16(0x1D, 2, spec["bed_max"])

    t.write_u16(0x1E, 0, round(spec["diameter"] * 100))
    t.write_u16(0x1E, 2, spec["length"])
    t.write_u16(0x1F, 0, spec["weight"])

    return t


def prompt(text, default=None, cast=str):
    suffix = f" [{default}]" if default is not None else ""
    while True:
        raw = input(f"{text}{suffix}: ").strip()
        if not raw and default is not None:
            return default
        if not raw:
            print("  A value is required.")
            continue
        try:
            return cast(raw)
        except ValueError:
            print("  Invalid value, try again.")


def interactive_spec():
    print("Anycubic NFC spool tag generator\n")

    types = list(SKUS.keys())
    print("Filament types:")
    for i, tname in enumerate(types, 1):
        print(f"  {i}. {tname}")
    choice = prompt("Type number", default="1", cast=int)
    ftype = types[choice - 1] if 1 <= choice <= len(types) else types[0]

    manufacturer = prompt("Manufacturer code (2-4 letters)", default="AC")
    color = prompt("Color hex (e.g. #89a84f)", default="#ffffff")

    nozzle_min = prompt("Nozzle temp min (C)", default=200, cast=int)
    nozzle_max = prompt("Nozzle temp max (C)", default=210, cast=int)
    bed_min = prompt("Bed temp min (C)", default=50, cast=int)
    bed_max = prompt("Bed temp max (C)", default=60, cast=int)
    speed_min = prompt("Print speed min mm/s (0 = skip)", default=0, cast=int)
    speed_max = prompt("Print speed max mm/s (0 = skip)", default=0, cast=int)

    diameter = prompt("Filament diameter mm", default=1.75, cast=float)
    length = prompt("Spool length (m)", default=330, cast=int)
    weight = prompt("Spool weight (g)", default=1000, cast=int)

    return {
        "type": ftype,
        "manufacturer": manufacturer,
        "color": color,
        "nozzle_min": nozzle_min,
        "nozzle_max": nozzle_max,
        "bed_min": bed_min,
        "bed_max": bed_max,
        "speed_min": speed_min,
        "speed_max": speed_max,
        "diameter": diameter,
        "length": length,
        "weight": weight,
    }


def cli_spec(args):
    return {
        "type": args.type,
        "manufacturer": args.manufacturer,
        "color": args.color,
        "nozzle_min": args.nozzle_min,
        "nozzle_max": args.nozzle_max,
        "bed_min": args.bed_min,
        "bed_max": args.bed_max,
        "speed_min": args.speed_min,
        "speed_max": args.speed_max,
        "diameter": args.diameter,
        "length": args.length,
        "weight": args.weight,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--type", choices=list(SKUS.keys()))
    parser.add_argument("--manufacturer", default="AC")
    parser.add_argument("--color", help="hex color, e.g. #89a84f")
    parser.add_argument("--nozzle-min", type=int, dest="nozzle_min")
    parser.add_argument("--nozzle-max", type=int, dest="nozzle_max")
    parser.add_argument("--bed-min", type=int, dest="bed_min")
    parser.add_argument("--bed-max", type=int, dest="bed_max")
    parser.add_argument("--speed-min", type=int, dest="speed_min", default=0)
    parser.add_argument("--speed-max", type=int, dest="speed_max", default=0)
    parser.add_argument("--diameter", type=float, default=1.75)
    parser.add_argument("--length", type=int, help="spool length in meters")
    parser.add_argument("--weight", type=int, help="spool weight in grams")
    parser.add_argument("--dump", action="store_true",
                         help="also print a human-readable per-page dump")
    args = parser.parse_args()

    if args.type and args.color and args.nozzle_min is not None:
        spec = cli_spec(args)
    else:
        spec = interactive_spec()

    tag = build_tag(spec)
    blob = tag.hex_blob()

    print("\n--- Copy into NFC Tools Pro: Other -> Write raw data ---")
    print(f"Start page: {START_PAGE} (0x{START_PAGE:02x})")
    print(f"Byte count: {len(blob) // 2} ({PAGE_COUNT} pages)")
    print("Hex data:")
    print(blob)

    if args.dump:
        print("\n--- Page dump ---")
        print(tag.page_dump())

    print(
        "\nReminder: each spool needs two tags written identically. "
        "Never write to pages 0x00-0x03 or 0x28-0x2c."
    )


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(1)
