import tempfile
import unittest
from pathlib import Path

from run_acceptance import verify_memory_write_census


class MemoryWriteCensusValidationTest(unittest.TestCase):
    def validate(self, content):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'census.csv'
            path.write_text(content, encoding='utf-8')
            return verify_memory_write_census(path)

    def test_valid_zero_and_writing_ticks(self):
        result = self.validate('brainIdentity,recordType,value,count,failedTicks\n'
                               '1,writesPerTick,0,3,0\n1,writesPerTick,2,4,0\n'
                               '1,consecutiveWriteTicks,2,2,0\n')
        self.assertEqual((result['ticks'], result['writeTicks']), (7, 4))

    def test_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, 'Invalid Brain memory write census'):
                verify_memory_write_census(Path(directory) / 'missing.csv')

    def test_streaks_require_separating_ticks(self):
        with self.assertRaisesRegex(RuntimeError, 'separating no-write ticks'):
            self.validate('brainIdentity,recordType,value,count,failedTicks\n'
                          '1,writesPerTick,1,2,0\n1,consecutiveWriteTicks,1,2,0\n')
        result = self.validate('brainIdentity,recordType,value,count,failedTicks\n'
                               '1,writesPerTick,1,2,0\n1,consecutiveWriteTicks,2,1,0\n')
        self.assertEqual(result['writeTicks'], 2)

    def test_empty_or_bad_header(self):
        for text in ('', 'brainIdentity,recordType,value,count,failedTicks\n', 'wrong\n'):
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                self.validate(text)

    def test_bad_counts_and_failed_ticks(self):
        for rows in ('1,writesPerTick,1,3,0\n1,consecutiveWriteTicks,2,1,0\n',
                     '1,writesPerTick,0,3,1\n',
                     '1,writesPerTick,0,-1,0\n',
                     '1,writesPerTick,0,1,0,extra\n',
                     '1,writesPerTick,0,1\n',
                     '1,writesPerTick,0,1,0\n1,writesPerTick,0,1,0\n'):
            with self.subTest(rows=rows), self.assertRaises(RuntimeError):
                self.validate('brainIdentity,recordType,value,count,failedTicks\n' + rows)


if __name__ == '__main__':
    unittest.main()
