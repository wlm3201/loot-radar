"""Bounce 覆盖率计分的回归测试：按「位置存在」计分，而不是唱片数量。"""
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('coverage_compare', Path(__file__).with_name('compare-bounce-coverage.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CoverageTest(unittest.TestCase):
    def test_presence_not_disc_quantity_is_scoring_unit(self):
        actual = {(1, 2, 3): {'bounceCount': 2, 'lootSeed': '-8159788421983247496'},
                  (4, 5, 6): {'bounceCount': 1, 'lootSeed': '2'}}
        predicted = {(1, 2, 3): {'bounceCount': 1, 'lootSeed': '-8159788421983247496'},
                     (7, 8, 9): {'bounceCount': 4, 'lootSeed': '3'},
                     (4, 5, 6): {'bounceCount': 0, 'lootSeed': '2'}}
        result = module.metrics(actual, predicted)
        self.assertEqual((result['actual'], result['predicted'], result['truePositive']), (2, 2, 1))
        self.assertEqual((result['recallPercent'], result['precisionPercent']), (50, 50))
        self.assertEqual((result['falseNegative'], result['falsePositive']), (1, 1))
        self.assertEqual((result['actualDiscs'], result['predictedDiscs']), (3, 5))
        self.assertEqual((result['matchingLootSeed'], result['matchingDiscCount']), (1, 0))

    def test_zero_denominator_is_undefined(self):
        result = module.metrics({}, {})
        self.assertIsNone(result['recallPercent'])
        self.assertIsNone(result['precisionPercent'])

    def test_duplicate_positions_fail_instead_of_inflating_score(self):
        with self.assertRaises(ValueError):
            module.index_carts([{'pos': [1, 2, 3]}, {'pos': [1, 2, 3]}])


if __name__ == '__main__':
    unittest.main()
