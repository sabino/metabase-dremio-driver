#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import urllib.request
from pathlib import Path


GITHUB_RELEASES_URL = "https://api.github.com/repos/metabase/metabase/releases?per_page=30"


def http_get_json(url: str) -> object:
    request = urllib.request.Request(url, headers={"User-Agent": "metabase-dremio-driver-sync"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def resolve_metabase_version(channel: str) -> str:
    releases = http_get_json(GITHUB_RELEASES_URL)
    if not isinstance(releases, list):
        raise RuntimeError("Unexpected GitHub releases payload")

    for release in releases:
        if release.get("draft"):
            continue
        prerelease = bool(release.get("prerelease"))
        if channel == "prerelease" and prerelease:
            return str(release["tag_name"])
        if channel == "stable" and not prerelease:
            return str(release["tag_name"])

    raise RuntimeError(f"Could not resolve a Metabase release for channel {channel!r}")


def parse_versions_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, value = line.split("=", 1)
        values[key] = value
    return values


def write_versions_file(path: Path, values: dict[str, str]) -> None:
    content = "\n".join(
        [
            f"METABASE_VERSION={values['METABASE_VERSION']}",
            f"METABASE_RELEASE_CHANNEL={values['METABASE_RELEASE_CHANNEL']}",
            f"DREMIO_JDBC_VERSION={values['DREMIO_JDBC_VERSION']}",
            f"PLUGIN_VERSION={values['PLUGIN_VERSION']}",
        ]
    )
    path.write_text(content + "\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--versions-file", default=".github/driver-versions.env")
    parser.add_argument("--metabase-version", default="")
    parser.add_argument("--dremio-jdbc-version", default="")
    parser.add_argument("--metabase-channel", default="prerelease", choices=["prerelease", "stable"])
    args = parser.parse_args()

    path = Path(args.versions_file)
    values = parse_versions_file(path)
    values.setdefault("PLUGIN_VERSION", "2.0.0-SNAPSHOT")
    values.setdefault("DREMIO_JDBC_VERSION", "26.0.5-202509091642240013-f5051a07")

    values["METABASE_RELEASE_CHANNEL"] = args.metabase_channel
    values["METABASE_VERSION"] = args.metabase_version or resolve_metabase_version(args.metabase_channel)
    if args.dremio_jdbc_version:
        values["DREMIO_JDBC_VERSION"] = args.dremio_jdbc_version

    write_versions_file(path, values)

    print(f"METABASE_VERSION={values['METABASE_VERSION']}")
    print(f"DREMIO_JDBC_VERSION={values['DREMIO_JDBC_VERSION']}")


if __name__ == "__main__":
    main()
