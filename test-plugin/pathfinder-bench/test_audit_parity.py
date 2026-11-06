import unittest
from itertools import product

from verify_audit_parity import IMPLS, KEYS, LENGTHS, MODES, REVERSES, SIZES, VARIANTS, WORLDS, verify_text


class AuditParityTest(unittest.TestCase):
    @staticmethod
    def lines():
        lines = []
        for key in product(MODES, VARIANTS, SIZES, LENGTHS, WORLDS, REVERSES, IMPLS):
            lines.append("PATHFIND_SIDECAR " + " ".join(f"{name}={value}" for name, value in zip(KEYS, key))
                         + " nodes=4 checksum=-1234")
        lines.append("PATHFIND_SIDECAR_COMPLETE checks=960")
        return lines

    def test_complete(self):
        self.assertEqual(960, verify_text("\n".join(self.lines())))

    def test_missing_and_duplicate(self):
        lines = self.lines()
        with self.assertRaises(ValueError):
            verify_text("\n".join(lines[1:]))
        with self.assertRaises(ValueError):
            verify_text("\n".join(lines + [lines[0]]))

    def test_same_length_different_coordinates(self):
        lines = self.lines()
        lines[0] = lines[0].replace("checksum=-1234", "checksum=-1235")
        with self.assertRaisesRegex(ValueError, "checksum"):
            verify_text("\n".join(lines))

    def test_completion_and_variant(self):
        lines = self.lines()
        with self.assertRaises(ValueError):
            verify_text("\n".join(lines[:-1]))
        lines[0] = lines[0].replace("variant=baseline", "variant=unknown")
        with self.assertRaises(ValueError):
            verify_text("\n".join(lines))


if __name__ == "__main__":
    unittest.main()
