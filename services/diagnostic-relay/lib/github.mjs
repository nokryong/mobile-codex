import {Rejected, publicBody, issueTitle} from './schema.mjs';
export const REPOSITORY = 'nokryong/mobile-codex';
const API = 'https://api.github.com/repos/' + REPOSITORY;
export class GitHubReporter {
  constructor(token, fetcher = fetch) { if (!token) throw new Rejected(503, 'not_configured'); this.token = token; this.fetcher = fetcher; }
  async request(path, method = 'GET', body) {
    if (method === 'POST') await this.authorized();
    try {
      const response = await this.fetcher(API + path, {method, redirect:'error', headers:{authorization:'Bearer ' + this.token, accept:'application/vnd.github+json', 'x-github-api-version':'2022-11-28', 'content-type':'application/json', 'user-agent':'Mobile-Codex-Diagnostic-Relay'}, body:body ? JSON.stringify(body) : undefined, signal:AbortSignal.timeout(8000)});
      if (!response.ok) throw new Error(); return await response.json();
    } catch { throw new Rejected(503, 'github_unavailable'); }
  }
  async authorized() {
    try {
      const response = await this.fetcher('https://api.github.com/user', {redirect:'error', headers:{authorization:'Bearer ' + this.token, accept:'application/vnd.github+json', 'x-github-api-version':'2022-11-28', 'user-agent':'Mobile-Codex-Diagnostic-Relay'}, signal:AbortSignal.timeout(8000)});
      if (!response.ok || (await response.json()).login !== 'nokryong') throw new Error();
    } catch { throw new Rejected(503, 'reporter_not_authorized'); }
  }
  async create(report, marker) { return this.issue(await this.request('/issues', 'POST', {title:issueTitle(report), body:publicBody(report, marker)})); }
  async comment(number, report, marker) {
    this.number(number);
    await this.request('/issues/' + number + '/comments', 'POST', {body:publicBody(report, marker)});
    return {number, url:this.url(number)};
  }
  number(number) { if (!Number.isSafeInteger(number) || number < 1) throw new Rejected(503, 'invalid_github_response'); }
  url(number) { this.number(number); return 'https://github.com/' + REPOSITORY + '/issues/' + number; }
  issue(data) { this.number(data.number); if (data.html_url !== this.url(data.number)) throw new Rejected(503, 'invalid_github_response'); return {number:data.number, url:data.html_url}; }
  async find(marker, issueNumber) {
    // Reconcile an uncertain POST before considering another write. Never
    // retry an unknown write blindly. A missing marker stays pending.
    for (let page = 1; page <= 5; page++) {
      const path = issueNumber ? `/issues/${issueNumber}/comments?per_page=100&page=${page}` : `/issues?state=all&sort=created&direction=desc&per_page=100&page=${page}`;
      const rows = await this.request(path);
      if (!Array.isArray(rows)) throw new Rejected(503, 'invalid_github_response');
      const row = rows.find(item => item.user?.login === 'nokryong' && !item.pull_request && item.body?.includes('<!-- ' + marker + ' -->'));
      if (row) return issueNumber ? {number:issueNumber, url:this.url(issueNumber)} : this.issue(row);
      if (rows.length < 100) break;
    }
    return null;
  }
}
