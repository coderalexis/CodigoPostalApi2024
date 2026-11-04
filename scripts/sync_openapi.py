"""Canonicalize the contract exported by mvn verify; --check detects drift in CI."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--check", action="store_true")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
source = root / "target/openapi.json"
destination = root / "integrations/openapi.json"
if not source.exists():
    parser.exit(1, "Falta target/openapi.json; ejecuta mvn verify primero\n")
contract = json.loads(source.read_text(encoding="utf-8"))
content = json.dumps(contract, indent=2, ensure_ascii=False, sort_keys=True) + "\n"
if args.check:
    if not destination.exists() or destination.read_text(encoding="utf-8") != content:
        parser.exit(1, "OpenAPI desactualizado; ejecuta python scripts/sync_openapi.py y npm run generate\n")
else:
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(content, encoding="utf-8", newline="\n")
print("OpenAPI sincronizado")
