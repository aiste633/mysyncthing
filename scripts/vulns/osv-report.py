#!/usr/bin/env python3
"""
Turns osv-scanner's JSON result for the SBOM into a Markdown report of the published
vulnerabilities that affect the app and are not accepted in accepted.toml.

Go is left out: govulncheck checks it (.github/workflows/govulncheck.yml) and reports only
the vulnerabilities whose code the app can reach, which a version match cannot tell.

Prints the report (nothing when there is nothing new) and, on stderr, the accepted entries
that no longer match anything. Any other exit status than 0 is an error.

Usage: osv-report.py <osv-scanner.json> <accepted.toml>
"""
import json
import sys
import tomllib

SKIPPED_ECOSYSTEMS = {"Go"}


def fixed_versions(vuln, package):
    fixed = set()
    for affected in vuln.get("affected", []):
        p = affected.get("package", {})
        if p.get("name") != package["name"] or p.get("ecosystem") != package["ecosystem"]:
            continue
        for r in affected.get("ranges", []):
            fixed.update(e["fixed"] for e in r.get("events", []) if "fixed" in e)
    return ", ".join(sorted(fixed)) or "none yet"


def cell(text):
    return " ".join(str(text).split()).replace("|", "\\|")


def main(result_path, accepted_path):
    with open(result_path) as f:
        result = json.load(f)
    with open(accepted_path, "rb") as f:
        accepted = {a["package"]: set(a["ids"]) for a in tomllib.load(f).get("accepted", [])}
    used = {purl: set() for purl in accepted}

    rows = []
    for source in result.get("results", []):
        for entry in source.get("packages", []):
            package = entry["package"]
            if package["ecosystem"] in SKIPPED_ECOSYSTEMS:
                continue
            purl = package.get("purl") or f'{package["ecosystem"]}/{package["name"]}@{package["version"]}'
            vulns = {v["id"]: v for v in entry.get("vulnerabilities", [])}
            for group in entry.get("groups", []):
                names = set(group["ids"]) | set(group.get("aliases", []))
                known = names & accepted.get(purl, set())
                if known:
                    used[purl] |= known
                    continue
                vuln = vulns.get(group["ids"][0], {})
                cves = sorted(a for a in names if a.startswith("CVE-"))
                rows.append((
                    package["ecosystem"], package["name"], package["version"],
                    group["ids"][0], ", ".join(cves), group.get("max_severity") or "?",
                    fixed_versions(vuln, package), vuln.get("summary", ""),
                ))

    for purl, ids in accepted.items():
        for stale in sorted(ids - used[purl]):
            print(f"accepted.toml: {purl} {stale} no longer matches; delete it", file=sys.stderr)

    if not rows:
        return
    print(f"### Libraries in the SBOM ({len(rows)} new)\n")
    print("| Advisory | CVE | Severity (CVSS) | Package | Version | Fixed in | Summary |")
    print("|---|---|---|---|---|---|---|")
    for eco, name, version, vid, cves, severity, fixed, summary in sorted(rows):
        print(f"| [{vid}](https://osv.dev/{vid}) | {cves} | {severity} | {cell(eco)} {cell(name)} "
              f"| {cell(version)} | {cell(fixed)} | {cell(summary)} |")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__.strip().splitlines()[-1])
    main(sys.argv[1], sys.argv[2])
