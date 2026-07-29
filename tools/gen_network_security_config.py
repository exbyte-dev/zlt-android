#!/usr/bin/env python3
"""Generate app/src/main/res/xml/network_security_config.xml.

The app talks to the router over plain HTTP on the LAN, so cleartext has to be
permitted — but only to the router, never globally.

Android's network security config matches *hostnames*. It has no notion of a
CIDR range, so "permit cleartext to RFC1918 only" cannot be written directly:
the private ranges have to be enumerated as literal addresses. This script
generates that enumeration, covering the gateway addresses home routers
actually use, and the base config leaves cleartext denied everywhere else.

The enumeration is a backstop, not the only guard. HostValidation.kt rejects
any host outside RFC1918/CGNAT at the point the user types it, so the app
cannot be aimed at a public address even by hand.

Run from the project root after editing the ranges below:

    python3 tools/gen_network_security_config.py
"""

from pathlib import Path

OUT = Path("app/src/main/res/xml/network_security_config.xml")


def addresses() -> list[str]:
    seen: dict[str, None] = {}

    def add(a: str) -> None:
        seen.setdefault(a, None)

    # 192.168.0.0/16 — by far the most common home range. Both the .1 and .254
    # gateway conventions are covered for every third octet.
    for third in range(256):
        add(f"192.168.{third}.1")
        add(f"192.168.{third}.254")

    # 10.0.0.0/8 is 16.7M addresses, so only the plausible gateway shapes are
    # listed rather than any attempt at completeness.
    for i in range(10):
        add(f"10.0.{i}.1")
        add(f"10.{i}.0.1")
        add(f"10.{i}.1.1")

    # 172.16.0.0/12, same reasoning.
    for second in range(16, 32):
        add(f"172.{second}.0.1")
        add(f"172.{second}.1.1")

    return list(seen)


def render(hosts: list[str]) -> str:
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!--",
        "  GENERATED FILE — do not edit by hand.",
        "  Regenerate with: python3 tools/gen_network_security_config.py",
        "",
        "  Cleartext is denied by default and permitted only to the private",
        "  gateway addresses listed below. See the generator for why this is an",
        "  enumeration rather than a CIDR range.",
        "-->",
        "<network-security-config>",
        '    <base-config cleartextTrafficPermitted="false">',
        "        <trust-anchors>",
        '            <certificates src="system" />',
        "        </trust-anchors>",
        "    </base-config>",
        '    <domain-config cleartextTrafficPermitted="true">',
    ]
    lines += [f'        <domain includeSubdomains="false">{h}</domain>' for h in hosts]
    lines += [
        "    </domain-config>",
        "</network-security-config>",
        "",
    ]
    return "\n".join(lines)


def main() -> None:
    hosts = addresses()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(render(hosts), encoding="utf-8")
    print(f"wrote {OUT} with {len(hosts)} permitted addresses")


if __name__ == "__main__":
    main()
