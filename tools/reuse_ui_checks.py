#!/usr/bin/env python3
"""Reuse a successful main-branch UI job only when its checked inputs match."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request

ROUTING_FILES = {'.github/workflows/android.yml', 'tools/reuse_ui_checks.py',
                 'tests/test_reuse_ui_checks.py', 'docs/ci-release.md'}

def git(*args):
    return subprocess.check_output(['git', *args])

def fingerprint(tree):
    records = []
    for entry in tree.split(b'\0'):
        if not entry:
            continue
        _, path = entry.split(b'\t', 1)
        name = path.decode('utf-8')
        # Android test changes are checked by Gradle, not by the UI job.
        if name.startswith('app/src/test/java/') or name in ROUTING_FILES:
            continue
        records.append(entry)
    return hashlib.sha256(b'\0'.join(sorted(records))).hexdigest()

def check_contract(workflow):
    job = re.search(r'^  checks:\n(.*?)(?=^  build:\n)', workflow, re.M | re.S)
    if not job:
        raise ValueError('Missing checks job')
    body = re.sub(r'^      # UI_REUSE_START\n.*?^      # UI_REUSE_END\n', '', job[1], flags=re.M | re.S)
    body = body.replace("        if: steps.reuse.outputs.reused != 'true'\n", '')
    body = body.replace("if: always() && steps.reuse.outputs.reused != 'true'", 'if: always()')
    return body

def passed(jobs):
    required = {'Test UI logic', 'Test packaging and release logic', 'Check responsive browser layouts'}
    return any(j.get('name') == 'Fast UI and source checks' and j.get('conclusion') == 'success'
               and required <= {s.get('name') for s in j.get('steps', []) if s.get('conclusion') == 'success'}
               for j in jobs)

def api(path):
    request = urllib.request.Request('https://api.github.com/repos/' + os.environ['GITHUB_REPOSITORY'] + path,
        headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json'})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)

def find_reusable():
    if os.environ.get('GITHUB_REF') != 'refs/heads/main' or os.environ.get('GITHUB_EVENT_NAME') not in {'push', 'workflow_dispatch'}:
        return None
    current = fingerprint(git('ls-tree', '-r', '-z', 'HEAD'))
    contract = check_contract(Path('.github/workflows/android.yml').read_text())
    runs = api('/actions/workflows/android.yml/runs?branch=main&per_page=20')['workflow_runs']
    for run in runs:
        if str(run['id']) == os.environ.get('GITHUB_RUN_ID') or run.get('status') != 'completed':
            continue
        if run.get('event') not in {'push', 'workflow_dispatch'} or run.get('head_branch') != 'main' or run.get('path') != '.github/workflows/android.yml':
            continue
        sha = run.get('head_sha', '')
        if not re.fullmatch('[a-f0-9]{40}', sha):
            continue
        subprocess.run(['git', 'fetch', '--no-tags', '--depth=1', 'origin', sha], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if fingerprint(git('ls-tree', '-r', '-z', sha)) != current:
            continue
        if check_contract(git('show', sha + ':.github/workflows/android.yml').decode()) != contract:
            continue
        jobs = api('/actions/runs/' + str(run['id']) + '/jobs?filter=latest&per_page=100')['jobs']
        if passed(jobs):
            return run
    return None

def main():
    run = find_reusable()
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write('reused=' + ('true' if run else 'false') + '\n')
    if run:
        print('Reusing successful UI/source checks with identical inputs: ' + run['html_url'])
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
            summary.write('UI/source inputs and check commands unchanged. Reused successful checks: ' + run['html_url'] + '\n\nAndroid build, unit tests, lint, signing and release verification still run.\n')
    else:
        print('No matching successful UI checks; running the full checks job.')

if __name__ == '__main__':
    main()
