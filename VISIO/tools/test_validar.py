"""Fixtures sintéticas: prueban cálculo, nunca se presentan como capturas del reloj."""
import csv
import json
import tempfile
import unittest
from pathlib import Path
from validar import analyze, scan_stream, state_coverage

class ValidationTests(unittest.TestCase):
    def test_unknown_onbody_not_counted_as_worn(self):
        result = state_coverage([{"t_elapsed_ns": 30, "worn": "1"}, {"t_elapsed_ns": 80, "worn": "0"}], "worn", 0, 100)
        self.assertEqual(result, {"yes_pct": 50, "no_pct": 20, "unknown_pct": 30})

    def test_gaps_at_hour_boundary_and_tail(self):
        with tempfile.TemporaryDirectory() as d:
            folder = Path(d)
            for part, times in enumerate(([0, 20_000_000, 40_000_000], [100_000_000, 120_000_000])):
                with (folder/f"acelerometro_{part:03}.csv").open("w", newline="") as f:
                    w=csv.writer(f); w.writerow(["t_elapsed_ns", "t_wall_ms"])
                    w.writerows((t, 0) for t in times)
            report, _ = scan_stream(folder, "acelerometro", 0, 200_000_000, [])
            self.assertEqual(report["estimated_internal_missing"], 2)
            self.assertEqual(report["estimated_leading_missing"], 0)
            self.assertEqual(report["estimated_trailing_missing"], 4)
            self.assertGreater(report["estimated_loss_pct"], 1)

    def test_empty_or_incomplete_session_cannot_pass(self):
        with tempfile.TemporaryDirectory() as d:
            folder = Path(d)
            (folder/"meta.json").write_text(json.dumps({"start_elapsed_ns": 1, "last_checkpoint_elapsed_ns": 3_600_000_000_001}))
            report, *_ = analyze(folder)
            self.assertFalse(report["acceptance"]["passed"])
            self.assertEqual(report["worn"]["unknown_pct"], 100)

    def test_batched_ibi_keeps_all_values_and_quality(self):
        with tempfile.TemporaryDirectory() as d:
            folder = Path(d)
            with (folder/"heart_rate_000.csv").open("w", newline="") as f:
                w=csv.writer(f);w.writerow(["t_elapsed_ns", "t_wall_ms", "ibi_ms_json", "ibi_status_json", "batch_size"])
                w.writerow([1_000_000_000, 1, "[800,900,1000]", "[0,-1,0]", 2])
                w.writerow([2_000_000_000, 2, "", "", 2])
            r,_=scan_stream(folder,"heart_rate",0,3_000_000_000,[("REPOSO",0,3_000_000_000,True)])
            self.assertEqual(r["ibi_values"],3)
            self.assertEqual(r["ibi_quality_zero_in_rest_batches"],2)

    def test_truncated_csv_fails_acceptance_instead_of_hiding_loss(self):
        with tempfile.TemporaryDirectory() as d:
            folder = Path(d)
            (folder/"meta.json").write_text(json.dumps({"start_elapsed_ns": 1, "end_elapsed_ns": 100, "finalized": True, "status": "completed"}))
            (folder/"offbody.csv").write_text("t_elapsed_ns,t_wall_ms,worn\n2,3\n")
            r, *_ = analyze(folder)
            self.assertFalse(r["acceptance"]["checks"]["csv_readable"])
            self.assertTrue(any("incompleta" in e for e in r["errors"]))

    def test_unknown_clock_has_no_sensor_frequency(self):
        with tempfile.TemporaryDirectory() as d:
            folder=Path(d)
            (folder/"ppg_000.csv").write_text("t_elapsed_ns,t_wall_ms,clock_basis\n1,0,receipt_only_unknown\n2,0,receipt_only_unknown\n")
            r,_=scan_stream(folder,"ppg",0,10,[])
            self.assertIsNone(r["measured_hz"])

    def test_stop_boundary_violation_keeps_statistics_but_cannot_pass(self):
        with tempfile.TemporaryDirectory() as d:
            folder = Path(d)
            (folder/"meta.json").write_text(json.dumps({"start_elapsed_ns": 0, "end_elapsed_ns": 19_000_000, "finalized": True, "status": "completed"}))
            (folder/"acelerometro_000.csv").write_text("t_elapsed_ns,t_wall_ms\n0,0\n20000000,20\n")
            r, *_ = analyze(folder)
            self.assertEqual(r["streams"]["acelerometro"]["samples"], 2)
            self.assertEqual(r["streams"]["acelerometro"]["out_of_session_samples"], 1)
            self.assertTrue(r["acceptance"]["checks"]["csv_readable"])
            self.assertFalse(r["acceptance"]["checks"]["timestamps_within_session"])
            self.assertFalse(r["acceptance"]["passed"])

    def test_started_temperature_tracker_without_samples_is_reported(self):
        with tempfile.TemporaryDirectory() as d:
            folder = Path(d)
            (folder/"meta.json").write_text(json.dumps({"start_elapsed_ns": 0, "end_elapsed_ns": 100,
                "samsung_started_trackers": ["SKIN_TEMPERATURE_CONTINUOUS"]}))
            r, *_ = analyze(folder)
            self.assertEqual(r["missing_started_samsung_streams"], ["temperatura"])
            self.assertFalse(r["acceptance"]["checks"]["started_samsung_streams_have_data"])

if __name__ == "__main__": unittest.main()
