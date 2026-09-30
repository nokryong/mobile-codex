import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('cleanup', Path(__file__).parents[1] / 'tools/cleanup_actions.py')
cleanup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleanup)

class CleanupActionsTests(unittest.TestCase):
    def run_record(self, number, conclusion, status='completed', name='Android APK', branch='main'):
        return dict(id=number, conclusion=conclusion, status=status, name=name, head_branch=branch)

    def test_only_the_newest_run_per_workflow_and_branch_survives(self):
        runs = [self.run_record(9, None, 'in_progress'), self.run_record(8, 'success'), self.run_record(7, 'failure'),
                self.run_record(6, 'success'), self.run_record(5, 'failure', branch='feature'), self.run_record(4, 'success', branch='feature'),
                self.run_record(3, 'success', name='Codex runtime update'), self.run_record(2, 'success', name='Codex runtime update')]
        self.assertEqual([7, 6, 4, 2], [r['id'] for r in cleanup.candidates(runs, set())])

    def test_latest_failure_is_kept_for_diagnosis(self):
        runs = [self.run_record(3, 'failure'), self.run_record(2, 'failure'), self.run_record(1, 'success')]
        self.assertEqual([2, 1], [r['id'] for r in cleanup.candidates(runs, set())])

    def test_protected_browser_evidence_and_live_runs_are_kept(self):
        runs = [self.run_record(4, None, 'queued'), self.run_record(3, 'failure'), self.run_record(2, 'success'), self.run_record(1, 'success')]
        self.assertEqual([1], [r['id'] for r in cleanup.candidates(runs, {2})])

    def test_cleanup_does_not_accumulate_its_own_history(self):
        runs = [self.run_record(3, None, 'in_progress', 'Actions history cleanup'),
                self.run_record(2, 'success', name='Actions history cleanup'), self.run_record(1, 'success', name='Actions history cleanup')]
        self.assertEqual([2, 1], [r['id'] for r in cleanup.candidates(runs, {3})])
