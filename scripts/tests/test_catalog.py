import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import catalog
import tempfile
import unittest

ROW = "01000|San Ángel|Colonia|Álvaro Obregón|Ciudad de México|Ciudad de México|01001|09|01001||09|010|0001|Urbano|01"

class CatalogTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.store = self.root / "store"
    def sample(self, name, row=ROW, encoding="utf-8", metadata="SEPOMEX"):
        path = self.root / name
        path.write_bytes((metadata + "\n" + catalog.HEADER + "\n" + row + "\n").encode(encoding))
        return path
    def test_encoding_checks_entire_file(self):
        for encoding in ["utf-8", "iso-8859-1"]:
            parsed = catalog.read_catalog(self.sample(encoding, encoding=encoding, metadata="A" * 5000))
            self.assertEqual(parsed["summary"]["encoding"], encoding)
            self.assertEqual(next(iter(parsed["records"].values()))[1], "San Ángel")
    def test_rejects_bad_duplicate_conflicting_and_empty_catalogs(self):
        for row in ["bad", ROW + "\n" + ROW, ROW + "\n" + ROW.replace("|09|01001|", "|32|01001|").replace("|0001|", "|0002|"), ""]:
            with self.assertRaises(ValueError): catalog.read_catalog(self.sample("bad.txt", row))
    def test_import_diff_rollback_and_idempotence(self):
        first = self.sample("one.txt")
        second = self.sample("two.txt", ROW.replace("San Ángel", "Nombre nuevo"))
        catalog.import_catalog(first, self.store)
        report = catalog.import_catalog(second, self.store, allow_large_change=True)
        self.assertEqual(len(report["settlements"]["changed"]), 1)
        state, _ = catalog.active(self.store)
        catalog.import_catalog(second, self.store)
        self.assertEqual(catalog.active(self.store)[0], state)
        catalog.rollback(self.store)
        self.assertEqual(catalog.active(self.store)[1]["raw"], first.read_bytes())
    def test_failed_import_never_changes_active_version(self):
        first = self.sample("one.txt")
        catalog.import_catalog(first, self.store)
        manifest = (self.store / "active.json").read_bytes()
        with self.assertRaises(ValueError):
            catalog.import_catalog(self.sample("two.txt", ROW.replace("San Ángel", "Cambio")), self.store)
        self.assertEqual((self.store / "active.json").read_bytes(), manifest)
        (self.store / ".import.lock").touch()
        with self.assertRaises(ValueError): catalog.import_catalog(first, self.store)
    def test_tampered_version_and_path_traversal_rejected(self):
        first = self.sample("one.txt")
        catalog.import_catalog(first, self.store)
        state, _ = catalog.active(self.store)
        (self.store / "versions" / (state["current"] + ".txt")).write_bytes(first.read_bytes().replace("San Ángel".encode(), b"San Juan"))
        with self.assertRaisesRegex(ValueError, "checksum incorrecto"): catalog.active(self.store)
        with self.assertRaises(ValueError): catalog.version(self.store, "../../outside")

if __name__ == "__main__": unittest.main()
