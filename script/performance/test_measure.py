"""Host-side validation for the report parser; no device required."""
import copy
import json
import tempfile
import unittest
from pathlib import Path
from measure import compare, statistics_ms

class ReportTests(unittest.TestCase):
    def test_median_and_nearest_rank_p95(self):
        result = statistics_ms([4_000_000, 1_000_000, 3_000_000, 2_000_000])
        self.assertEqual(result, {'median_ms': 2.5, 'p95_ms': 4.0, 'n': 4})

    def test_no_empty_report(self):
        with self.assertRaises(ValueError): statistics_ms([])

    def test_comparison_rejects_different_device(self):
        with tempfile.TemporaryDirectory() as folder:
            baseline = Path(folder) / 'before.json'; candidate = Path(folder) / 'after.json'
            report = {'status': 'PASS', 'device': 'phone A', 'sdk': 37, 'debuggable': True,
                      'active_key_count': 40, 'custom_mode': False, 'width_px': 1000}
            baseline.write_text(json.dumps(report)); report['device'] = 'phone B'; candidate.write_text(json.dumps(report))
            with self.assertRaisesRegex(ValueError, 'device'): compare([baseline], [candidate])

    def test_equal_runs_have_zero_change(self):
        with tempfile.TemporaryDirectory() as folder:
            baseline = Path(folder) / 'before.json'; candidate = Path(folder) / 'after.json'
            report = {'status': 'PASS', 'device': 'phone A', 'sdk': 37, 'debuggable': True,
                      'active_key_count': 40, 'custom_mode': False, 'width_px': 1000}
            for metric in ('model_docked', 'model_floating', 'full_rebuild', 'refresh_to_ondraw'):
                report[metric] = {'raw_ns': [1_000_000, 3_000_000]}
            baseline.write_text(json.dumps(report)); candidate.write_text(json.dumps(report))
            result = compare([baseline], [candidate])
            self.assertEqual(result['full_rebuild']['change_percent'], 0)
            self.assertEqual(result['full_rebuild']['baseline']['median_of_run_medians_ms'], 2)

if __name__ == '__main__': unittest.main()
