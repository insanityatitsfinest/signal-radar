#!/usr/bin/env python3
"""Regenerates the vendor lookup tables bundled in the app (app/src/main/assets).

Sources (all public):
  * MAC OUI -> manufacturer: Wireshark's epan/manuf-data.c (built from the IEEE registry)
      https://raw.githubusercontent.com/wireshark/wireshark/master/epan/manuf-data.c
  * Bluetooth company IDs + 16-bit service UUIDs: Nordic's bluetooth-numbers-database
      https://raw.githubusercontent.com/NordicSemiconductor/bluetooth-numbers-database/master/v1/company_ids.json
      https://raw.githubusercontent.com/NordicSemiconductor/bluetooth-numbers-database/master/v1/service_uuids.json

Usage: python3 scripts/gen_vendor_data.py            (downloads)
       python3 scripts/gen_vendor_data.py <dir>      (uses manuf-data.c, company_ids.json, service_uuids.json in <dir>)
"""
import json
import os
import re
import sys
import urllib.request

URLS = {
    "manuf-data.c": "https://raw.githubusercontent.com/wireshark/wireshark/master/epan/manuf-data.c",
    "company_ids.json": "https://raw.githubusercontent.com/NordicSemiconductor/bluetooth-numbers-database/master/v1/company_ids.json",
    "service_uuids.json": "https://raw.githubusercontent.com/NordicSemiconductor/bluetooth-numbers-database/master/v1/service_uuids.json",
}

# 16-bit member UUIDs that matter for tracker / device identification but are not in the files above.
EXTRA_UUIDS = {
    "FEED": "Tile tracker",
    "FEEC": "Tile tracker",
    "FD5A": "Samsung SmartThings Find (SmartTag)",
    "FD6F": "Exposure Notification",
    "FE9F": "Google",
    "FE03": "Amazon",
    "FE59": "Nordic DFU",
    "FE95": "Xiaomi",
}

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")


def read(src_dir, name):
    if src_dir:
        for f in os.listdir(src_dir):
            if f.endswith(name):
                with open(os.path.join(src_dir, f), encoding="utf-8") as fh:
                    return fh.read()
        raise SystemExit(f"{name} not found in {src_dir}")
    req = urllib.request.Request(URLS[name], headers={"User-Agent": "signal-radar-build"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read().decode("utf-8")


def clean(s):
    return s.replace("\t", " ").replace("\n", " ").strip()


def main():
    src_dir = sys.argv[1] if len(sys.argv) > 1 else None
    os.makedirs(ASSETS, exist_ok=True)

    manuf = read(src_dir, "manuf-data.c")
    table = manuf.split("global_manuf_oui24_table[] = {", 1)[1].split("};", 1)[0]
    pat = re.compile(r'\{\s*\{\s*0x([0-9A-Fa-f]{2}),\s*0x([0-9A-Fa-f]{2}),\s*0x([0-9A-Fa-f]{2})\s*\},\s*"((?:[^"\\]|\\.)*)",\s*"((?:[^"\\]|\\.)*)"\s*\}')
    rows = []
    for m in pat.finditer(table):
        short, full = m.group(4), m.group(5)
        name = clean((full or short).replace('\\"', '"'))
        rows.append(f"{(m.group(1) + m.group(2) + m.group(3)).upper()}\t{name}")
    with open(os.path.join(ASSETS, "oui.txt"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(rows) + "\n")
    print(f"oui.txt: {len(rows)} entries")

    companies = json.loads(read(src_dir, "company_ids.json"))
    with open(os.path.join(ASSETS, "companies.txt"), "w", encoding="utf-8") as fh:
        for c in companies:
            fh.write(f"{c['code']:04X}\t{clean(c['name'])}\n")
    print(f"companies.txt: {len(companies)} entries")

    uuids = {}
    for s in json.loads(read(src_dir, "service_uuids.json")):
        u = s["uuid"].upper()
        if len(u) == 4:
            uuids[u] = clean(s["name"])
    for k, v in EXTRA_UUIDS.items():
        uuids.setdefault(k, v)
    with open(os.path.join(ASSETS, "uuids.txt"), "w", encoding="utf-8") as fh:
        for k in sorted(uuids):
            fh.write(f"{k}\t{uuids[k]}\n")
    print(f"uuids.txt: {len(uuids)} entries")


if __name__ == "__main__":
    main()
