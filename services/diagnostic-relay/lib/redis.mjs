import {Rejected} from './schema.mjs';

export const RATE_SCRIPT = `local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[2]) end; return n<=tonumber(ARGV[1]) and 1 or 0`;
export const RELEASE_SCRIPT = `if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end`;
export const CONSUME_SCRIPT = `local v=redis.call('GET',KEYS[1]); if v==ARGV[1] then redis.call('DEL',KEYS[1]); return 1 else return 0 end`;

// Every coordination operation is durable and atomic on Redis. Never fall back
// to process memory or a serverless filesystem when storage is unavailable.
export class RedisStore {
  constructor(url, token, fetcher = fetch) {
    if (!/^https:\/\/[a-z0-9-]+\.upstash\.io\/?$/.test(url || '') || !token) throw new Rejected(503, 'not_configured');
    this.url = url; this.token = token; this.fetcher = fetcher;
  }
  async command(args) {
    try {
      const response = await this.fetcher(this.url, {method:'POST', redirect:'error', headers:{authorization:'Bearer ' + this.token, 'content-type':'application/json'}, body:JSON.stringify(args), signal:AbortSignal.timeout(5000)});
      if (!response.ok) throw new Error();
      const data = await response.json(); if (data.error) throw new Error(); return data.result;
    } catch { throw new Rejected(503, 'storage_unavailable'); }
  }
  async get(key) { const value = await this.command(['GET', 'mcdiag:' + key]); return value ? JSON.parse(value) : null; }
  async set(key, value, ttl = 604800) { await this.command(['SET', 'mcdiag:' + key, JSON.stringify(value), 'EX', ttl]); }
  async setOnce(key, value, ttl) { return (await this.command(['SET', 'mcdiag:' + key, JSON.stringify(value), 'EX', ttl, 'NX'])) === 'OK'; }
  async rate(key, limit, seconds) { return (await this.command(['EVAL', RATE_SCRIPT, 1, 'mcdiag:' + key, limit, seconds])) === 1; }
  async consume(key, value) { return (await this.command(['EVAL', CONSUME_SCRIPT, 1, 'mcdiag:' + key, JSON.stringify(value)])) === 1; }
  async unlock(key, value) { await this.command(['EVAL', RELEASE_SCRIPT, 1, 'mcdiag:' + key, JSON.stringify(value)]); }
}
