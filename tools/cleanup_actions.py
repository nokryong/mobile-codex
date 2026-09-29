#!/usr/bin/env python3
"""Remove obsolete main-branch failures, retaining live runs and UI reuse evidence."""
import json
import os
import urllib.error
import urllib.request

FAILED = {'failure', 'cancelled', 'timed_out', 'startup_failure'}

def candidates(runs, protected):
    ordered = sorted(runs, key=lambda r: r['id'], reverse=True)
    builds = [r for r in ordered if r.get('name') == 'Android APK' and r.get('head_branch') == 'main']
    keep = set(protected)
    # Keep the current failure until a newer completed build supersedes it.
    latest = next((r for r in builds if r.get('status') == 'completed'), None)
    if latest and latest.get('conclusion') in FAILED:
        keep.add(latest['id'])
    return [r for r in ordered if r['id'] not in keep and r.get('status') == 'completed'
            and ((r in builds and r.get('conclusion') in FAILED)
                 or (r.get('name') == 'Actions history cleanup'))]

def api(path, method='GET'):
    request = urllib.request.Request('https://api.github.com/repos/' + os.environ['GITHUB_REPOSITORY'] + path,
        method=method, headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json'})
    with urllib.request.urlopen(request, timeout=30) as response:
        raw = response.read()
        return json.loads(raw) if raw else None

def main():
    runs = []
    for page in range(1, 21):
        batch = api('/actions/runs?per_page=100&page=' + str(page))['workflow_runs']
        runs.extend(batch)
        if len(batch) < 100:
            break
    protected = {int(os.environ['GITHUB_RUN_ID'])}
    # A failed APK run can still contain the most recent successful browser suite.
    # Keep that evidence until a newer full browser check replaces it.
    for run in sorted(runs, key=lambda r: r['id'], reverse=True)[:20]:
        if run.get('name') != 'Android APK' or run.get('head_branch') != 'main' or run.get('status') != 'completed':
            continue
        jobs = api('/actions/runs/' + str(run['id']) + '/jobs?filter=latest&per_page=100')['jobs']
        if any(j.get('name') == 'Fast UI and source checks' and j.get('conclusion') == 'success'
               and any(s.get('name') == 'Check responsive browser layouts' and s.get('conclusion') == 'success'
                       for s in j.get('steps', [])) for j in jobs):
            protected.add(run['id'])
            break
    deleted = []
    for run in candidates(runs, protected):
        path = '/actions/runs/' + str(run['id'])
        fresh = api(path)
        if fresh.get('status') != 'completed':
            continue
        api(path, 'DELETE')
        try:
            api(path)
            raise RuntimeError('Deleted run still exists: ' + str(run['id']))
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
        deleted.append(run['id'])
        print('Deleted obsolete run:', run['id'])
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as output:
        output.write('Removed ' + str(len(deleted)) + ' obsolete runs. Kept active runs, successful builds, latest failure and latest full browser evidence.\n')

if __name__ == '__main__':
    main()
