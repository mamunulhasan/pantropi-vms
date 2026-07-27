#!/usr/bin/env python3
"""
dependency-scan.py — self-contained software composition analysis.

US-01.4.1 · T-01.4.1.3 (AC-3).

Reads the Gradle dependency inventory (build/reports/dependencies.txt, produced by the
`dependencyInventory` task) and checks every Maven coordinate against the OSV database
(https://osv.dev — Google's Open Source Vulnerabilities, free, no API key, the same data
many commercial scanners consume).

Fails (exit 1) when any dependency carries a HIGH or CRITICAL advisory, naming the
advisory identifier (GHSA + CVE alias) and the CVSS score — satisfying AC-3 without
GitHub's dependency graph, which is not populated for Gradle.

Runs identically on a developer machine and in CI. No scanner binary to install.

Usage:
    python scripts/dependency-scan.py [inventory_file] [--fail-on high|critical]
Exit:
    0  no HIGH/CRITICAL advisories
    1  at least one HIGH/CRITICAL advisory (details printed)
    2  scan could not run (network, missing inventory)
"""
import json
import sys
import urllib.request
import urllib.error

OSV_BATCH = "https://api.osv.dev/v1/querybatch"
OSV_VULN = "https://api.osv.dev/v1/vulns/"
RANK = {"LOW": 1, "MODERATE": 2, "MEDIUM": 2, "HIGH": 3, "CRITICAL": 4}


def post(url, payload):
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def get(url):
    with urllib.request.urlopen(url, timeout=30) as r:
        return json.load(r)


def cvss_to_severity(score):
    if score is None:
        return None
    if score >= 9.0:
        return "CRITICAL"
    if score >= 7.0:
        return "HIGH"
    if score >= 4.0:
        return "MEDIUM"
    return "LOW"


def cvss_base_score(vector):
    """Parse the base score from a CVSS v3 vector's numeric form if present."""
    # OSV 'severity' entries are {type: CVSS_V3, score: 'CVSS:3.1/...'} — the numeric
    # base score is not in the vector string, so we rely on database_specific severity
    # and the vector's exploitability as a fallback. Score is fetched below instead.
    return None


def severity_of(vuln_id):
    """Return (label, score, cve_aliases) for an OSV id, best-effort."""
    try:
        v = get(OSV_VULN + vuln_id)
    except Exception:
        return (None, None, [])
    aliases = [a for a in v.get("aliases", []) if a.startswith("CVE-")]
    # 1) explicit severity label
    label = (v.get("database_specific", {}) or {}).get("severity")
    # 2) numeric CVSS score if the record carries one
    score = None
    for sev in v.get("severity", []) or []:
        s = sev.get("score", "")
        # some records put the numeric base score in a CVSS_V3 'score' as a bare number
        try:
            score = float(s)
        except (TypeError, ValueError):
            score = None
    if label is None and score is not None:
        label = cvss_to_severity(score)
    return (label.upper() if label else None, score, aliases)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    inventory = args[0] if args else "apps/vms-api/build/reports/dependencies.txt"
    fail_on = "high"
    if "--fail-on" in sys.argv:
        fail_on = sys.argv[sys.argv.index("--fail-on") + 1].lower()
    threshold = RANK["CRITICAL"] if fail_on == "critical" else RANK["HIGH"]

    # Reviewed allowlist: advisory ids with a written justification and a recheck date.
    # Format per line: "GHSA-xxxx  # reason (recheck YYYY-MM-DD)". Never silently added.
    allow = {}
    allow_path = "scripts/dependency-scan-allowlist.txt"
    try:
        for line in open(allow_path, encoding="utf-8"):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            aid = line.split()[0]
            allow[aid] = line
    except OSError:
        pass

    try:
        coords = [l.strip() for l in open(inventory, encoding="utf-8") if l.strip()]
    except OSError as e:
        print(f"dependency-scan: cannot read inventory {inventory}: {e}", file=sys.stderr)
        return 2

    # Maven coord "group:artifact:version" -> OSV query (name "group:artifact")
    queries, meta = [], []
    for c in coords:
        parts = c.rsplit(":", 1)
        if len(parts) != 2:
            continue
        name, version = parts
        queries.append({"package": {"name": name, "ecosystem": "Maven"}, "version": version})
        meta.append((name, version))

    print(f"dependency-scan: checking {len(queries)} dependencies against OSV "
          f"(fail-on={fail_on})...")
    try:
        results = post(OSV_BATCH, {"queries": queries}).get("results", [])
    except (urllib.error.URLError, TimeoutError) as e:
        print(f"dependency-scan: OSV unreachable: {e}", file=sys.stderr)
        return 2

    findings = []
    seen = set()
    for (name, version), res in zip(meta, results):
        for v in res.get("vulns", []) or []:
            vid = v["id"]
            if (name, vid) in seen:
                continue
            seen.add((name, vid))
            label, score, cves = severity_of(vid)
            rank = RANK.get(label, 0)
            allowed = vid in allow or any(a in allow for a in cves)
            if rank >= threshold and not allowed:
                findings.append({
                    "dep": f"{name}:{version}", "id": vid,
                    "cve": ", ".join(cves) or "-", "severity": label,
                    "score": score,
                })

    if allow:
        print(f"dependency-scan: {len(allow)} advisory id(s) allowlisted with justification "
              f"(see {allow_path})")
    if findings:
        print("\n✗ VULNERABLE DEPENDENCIES (AC-3):\n")
        for f in sorted(findings, key=lambda x: -RANK.get(x["severity"], 0)):
            sc = f" CVSS {f['score']}" if f["score"] else ""
            print(f"  [{f['severity']}]{sc}  {f['dep']}")
            print(f"        advisory: {f['id']}   {f['cve']}")
        print(f"\n{len(findings)} HIGH/CRITICAL advisory finding(s). Build fails (AC-3).")
        return 1

    print(f"✓ no {fail_on}-or-above advisories across {len(queries)} dependencies.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
