import xml.etree.ElementTree as ET
from collections import defaultdict

tree = ET.parse("/workspace/app/build/reports/pitest/mutations.xml")
root = tree.getroot()
stats = defaultdict(lambda: {"total": 0, "killed": 0, "survived": 0, "no_coverage": 0})

for m in root.findall("mutation"):
    cls = m.find("mutatedClass").text
    short = cls.split(".")[-1]
    # Remove inner class markers for grouping
    if "$" in short:
        short = short.split("$")[0]
    status = m.get("status")
    stats[short]["total"] += 1
    if status == "KILLED":
        stats[short]["killed"] += 1
    elif status == "SURVIVED":
        stats[short]["survived"] += 1
    elif status == "NO_COVERAGE":
        stats[short]["no_coverage"] += 1

print(f"{'Class':<45} {'Total':>5} {'Kill':>5} {'Surv':>5} {'NoCov':>6} {'Kill%':>6}")
print("-" * 78)
for cls, s in sorted(stats.items(), key=lambda x: -x[1]["total"]):
    covered = s["killed"] + s["survived"]
    kill_pct = str(s["killed"] * 100 // covered) + "%" if covered > 0 else "N/A"
    print(f"{cls:<45} {s['total']:>5} {s['killed']:>5} {s['survived']:>5} {s['no_coverage']:>6} {kill_pct:>6}")
