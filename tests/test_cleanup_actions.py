import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('cleanup', Path(__file__).parents[1] / 'tools/cleanup_actions.py')
cleanup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleanup)

class CleanupActionsTests(unittest.TestCase):
    def run_record(self, number, conclusion, status='completed', name='Android APK', branch='main'):
        return dict(id=number, conclusion=conclusion, status=status, name=name, head_branch=branch)

    def test_success_preserves_evidence_and_live_runs_but_replaces_old_failures(self):
        runs = [self.run_record(6, None, 'in_progress'), self.run_record(5, 'success'),
                self.run_record(4, 'failure'), self.run_record(3, 'cancelled'), self.run_record(2, 'failure'),
                self.run_record(1, 'failure', branch='feature')]
        self.assertEqual([3, 2], [r['id'] for r in cleanup.candidates(runs, {4})])

    def test_latest_failed_build_is_kept_for_diagnosis(self):
        runs = [self.run_record(3, 'failure'), self.run_record(2, 'failure'), self.run_record(1, 'success')]
        self.assertEqual([2], [r['id'] for r in cleanup.candidates(runs, set())])

    def test_cleanup_does_not_accumulate_its_own_history(self):
        runs = [self.run_record(3, None, 'in_progress', 'Actions history cleanup'),
                self.run_record(2, 'success', name='Actions history cleanup')]
        self.assertEqual([2], [r['id'] for r in cleanup.candidates(runs, {3})])
