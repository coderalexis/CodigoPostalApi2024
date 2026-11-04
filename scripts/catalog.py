"""Versioned SEPOMEX imports. Standard library only; see docs/catalog.md."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile

HEADER = "d_codigo|d_asenta|d_tipo_asenta|D_mnpio|d_estado|d_ciudad|d_CP|c_estado|c_oficina|c_CP|c_tipo_asenta|c_mnpio|id_asenta_cpcons|d_zona|c_cve_ciudad"
HASH = re.compile(r"[0-9a-f]{64}")
MAX_BYTES = 100 * 1024 * 1024


def read_catalog(path, encoding="auto"):
    path = Path(path)
    with path.open("rb") as source:
        raw = source.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise ValueError("El catálogo excede 100 MiB")
    if encoding == "auto":
        try:
            text = raw.decode("utf-8-sig")
            encoding = "utf-8"
        except UnicodeDecodeError:
            text = raw.decode("iso-8859-1")
            encoding = "iso-8859-1"
    else:
        text = raw.decode(encoding).lstrip("\ufeff")
    lines = text.splitlines()
    if len(lines) < 3 or lines[1] != HEADER:
        raise ValueError("Encabezado SEPOMEX inválido o catálogo vacío")
    records, locations, municipalities = {}, {}, set()
    for number, line in enumerate(lines[2:], 3):
        row = tuple(value.strip() for value in line.split("|"))
        if len(row) != 15 or any(not row[i] for i in [1, 2, 3, 4, 13]):
            raise ValueError(f"Línea {number}: columnas o campos requeridos inválidos")
        for column, width in [(0, 5), (7, 2), (11, 3), (12, 4)]:
            if not re.fullmatch(r"[0-9]{%d}" % width, row[column]):
                raise ValueError(f"Línea {number}: clave oficial inválida en columna {column}")
        if not 1 <= int(row[7]) <= 32:
            raise ValueError(f"Línea {number}: clave de estado fuera de rango")
        if row[13] not in {"Urbano", "Rural", "Semiurbano"}:
            raise ValueError(f"Línea {number}: zona desconocida")
        location = (row[7], row[11], row[4], row[3])
        if row[0] in locations and locations[row[0]] != location:
            raise ValueError(f"Línea {number}: CP con ubicaciones contradictorias")
        locations[row[0]] = location
        identity = ":".join(row[i] for i in [0, 7, 11, 12])
        if identity in records:
            raise ValueError(f"Línea {number}: asentamiento duplicado {identity}")
        records[identity] = row
        municipalities.add((row[7], row[11]))
    if not records:
        raise ValueError("El catálogo está vacío")
    return {"raw": raw, "records": records, "locations": locations,
            "summary": {"checksum": hashlib.sha256(raw).hexdigest(), "encoding": encoding,
                        "zip_codes": len(locations), "settlements": len(records),
                        "federal_entities": len({v[0] for v in locations.values()}),
                        "municipalities": len(municipalities)}}


def compare(old, new):
    before = old["records"] if old else {}
    after = new["records"]
    added, removed = sorted(after.keys() - before.keys()), sorted(before.keys() - after.keys())
    changed = sorted(k for k in before.keys() & after.keys() if before[k] != after[k])
    old_zips = set(old["locations"]) if old else set()
    return {"before": old["summary"] if old else None, "after": new["summary"],
            "zip_codes": {"added": sorted(set(new["locations"]) - old_zips),
                          "removed": sorted(old_zips - set(new["locations"]))},
            "settlements": {"added": added, "removed": removed, "changed": changed},
            "change_ratio": (len(added) + len(removed) + len(changed)) / max(len(before), 1) if old else 0}


def atomic_write(path, content):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(dir=path.parent, prefix=".pending-")
    try:
        with os.fdopen(fd, "wb") as target:
            target.write(content)
            target.flush()
            os.fsync(target.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def write_json(path, value):
    atomic_write(path, (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))


@contextmanager
def writer_lock(store):
    store.mkdir(parents=True, exist_ok=True)
    lock = store / ".import.lock"
    try:
        fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError as error:
        raise ValueError("Hay otra importación activa (.import.lock)") from error
    try:
        os.close(fd)
        yield
    finally:
        lock.unlink()


def version(store, checksum):
    if not isinstance(checksum, str) or not HASH.fullmatch(checksum):
        raise ValueError("Checksum inválido en active.json")
    parsed = read_catalog(store / "versions" / (checksum + ".txt"))
    if parsed["summary"]["checksum"] != checksum:
        raise ValueError("El archivo versionado fue modificado: checksum incorrecto")
    return parsed


def active(store):
    manifest = store / "active.json"
    if not manifest.exists():
        return None, None
    state = json.loads(manifest.read_text(encoding="utf-8"))
    return state, version(store, state["current"])


def import_catalog(path, store, max_change_ratio=0.05, allow_large_change=False, encoding="auto"):
    if not 0 <= max_change_ratio <= 1:
        raise ValueError("max-change-ratio debe estar entre 0 y 1")
    candidate = read_catalog(path, encoding)
    store = Path(store)
    with writer_lock(store):
        state, previous = active(store)
        report = compare(previous, candidate)
        if previous and report["change_ratio"] > max_change_ratio and not allow_large_change:
            raise ValueError(f"Cambio de {report['change_ratio']:.1%}; revisa compare y usa --allow-large-change si es intencional")
        checksum = candidate["summary"]["checksum"]
        if state and state["current"] == checksum:
            return report
        destination = store / "versions" / (checksum + ".txt")
        if destination.exists():
            version(store, checksum)
        else:
            atomic_write(destination, candidate["raw"])
        write_json(store / "reports" / (checksum + ".json"), report)
        # Único punto de activación. Los archivos de versiones nunca se sobrescriben.
        write_json(store / "active.json", {"current": checksum,
            "previous": state["current"] if state else None,
            "updated_at": datetime.now(timezone.utc).isoformat()})
        return report


def rollback(store):
    store = Path(store)
    with writer_lock(store):
        state, current = active(store)
        if not state or not state.get("previous"):
            raise ValueError("No hay versión anterior")
        previous = version(store, state["previous"])
        write_json(store / "active.json", {"current": state["previous"], "previous": state["current"],
            "updated_at": datetime.now(timezone.utc).isoformat()})
        return compare(current, previous)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate"); validate.add_argument("file")
    validate.add_argument("--encoding", default="auto", choices=["auto", "utf-8", "iso-8859-1"])
    diff = commands.add_parser("compare"); diff.add_argument("old"); diff.add_argument("new")
    diff.add_argument("--report", type=Path)
    imp = commands.add_parser("import"); imp.add_argument("file")
    imp.add_argument("--store", type=Path, required=True)
    imp.add_argument("--encoding", default="auto", choices=["auto", "utf-8", "iso-8859-1"])
    imp.add_argument("--max-change-ratio", type=float, default=0.05)
    imp.add_argument("--allow-large-change", action="store_true")
    back = commands.add_parser("rollback"); back.add_argument("--store", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "validate": result = read_catalog(args.file, args.encoding)["summary"]
        elif args.command == "compare":
            result = compare(read_catalog(args.old), read_catalog(args.new))
            if args.report: write_json(args.report, result)
        elif args.command == "import":
            result = import_catalog(args.file, args.store, args.max_change_ratio, args.allow_large_change, args.encoding)
        else: result = rollback(args.store)
        # La salida corta evita imprimir cientos de miles de claves; el reporte conserva el detalle.
        if "settlements" in result and isinstance(result["settlements"], dict):
            result = {**result, "settlements": {k: len(v) for k, v in result["settlements"].items()},
                      "zip_codes": {k: len(v) for k, v in result["zip_codes"].items()}}
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (ValueError, OSError, KeyError) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__": main()
