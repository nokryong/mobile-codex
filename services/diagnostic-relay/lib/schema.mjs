import {createHash} from 'node:crypto';

export const MAX_BODY = 4096;
export class Rejected extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}
export const hash = value => createHash('sha256').update(value).digest('hex');
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
const oneOf = values => value => typeof value === 'string' && values.includes(value);
const integer = (min, max) => value => Number.isSafeInteger(value) && value >= min && value <= max;
const fields = {
  schema: value => value === 1 || value === 2,
  reportId: value => typeof value === 'string' && uuid.test(value),
  appVersion: oneOf(['0.3.5', '0.3.6']),
  runtime: oneOf(['codex_01621']),
  errorCode: oneOf(['initialize_timeout', 'start_failure', 'transport_closed', 'operation_failed', 'relay_verification']),
  operation: oneOf(['login', 'start', 'chat', 'other']),
  stage: oneOf(['before_spawn', 'spawned', 'initialize_sent', 'initialized', 'ready', 'running', 'unknown']),
  homeKind: oneOf(['active', 'pending', 'unknown']),
  elapsedMs: integer(0, 600000),
  spawnMs: integer(-1, 600000),
  writeMs: integer(-1, 600000),
  firstByteMs: integer(-1, 600000),
  responseMs: integer(-1, 600000),
  processAlive: value => typeof value === 'boolean',
  exitCode: integer(-1, 255),
  stdoutBytes: integer(0, 100000000),
  stdoutLines: integer(0, 1000000),
  invalidJson: integer(0, 1000000),
  unmatchedIds: integer(0, 1000000),
  stderrBytes: integer(0, 100000000),
  stderrCategory: oneOf(['none', 'native_link', 'panic', 'memory', 'config']),
  androidApi: integer(29, 100),
  abi: oneOf(['arm64', 'other']),
  manufacturer: oneOf(['samsung', 'google', 'xiaomi', 'oppo', 'vivo', 'oneplus', 'motorola', 'sony', 'asus', 'other']),
  deviceClass: oneOf(['phone', 'tablet']),
  ramClass: oneOf(['le4gb', 'le8gb', 'gt8gb', 'unknown']),
  pageSize: oneOf(['4k', '16k', 'other', 'unknown'])
};
const transport = {
  challengeId: value => typeof value === 'string' && /^[a-f0-9]{48}$/.test(value),
  proof: integer(0, 100000000)
};
// v2 keeps the same 27 fields; null means no measurement was collected.
// Preserve v1's validation and acceptance for already released APKs.
const nullableMeasurements = ['elapsedMs', 'processAlive', 'stdoutBytes', 'stdoutLines', 'invalidJson', 'unmatchedIds', 'stderrBytes'];
function reportFields(schema) {
  if (schema !== 2) return fields;
  return {...fields,
    appVersion: value => typeof value === 'string' && /^(?:unknown|(?:0|[1-9][0-9]{0,2})\.(?:0|[1-9][0-9]{0,2})\.(?:0|[1-9][0-9]{0,2}))$/.test(value),
    errorCode: value => fields.errorCode(value) || oneOf(['request_timeout', 'permission_denied', 'invalid_response', 'storage_failure', 'io_failure', 'login_failed',
      'rpc_invalid_request', 'rpc_method_missing', 'rpc_internal', 'rpc_rejected', 'turn_failed', 'context_limit', 'usage_limit',
      'upstream_connection', 'upstream_auth', 'upstream_bad_request', 'upstream_internal', 'sandbox_failure'])(value),
    ...Object.fromEntries(nullableMeasurements.map(key => [key, value => value === null || fields[key](value)]))};
}

// Deliberately a flat scalar format. Reject duplicate keys, escaping, nested
// objects, arbitrary Unicode/free text and huge inputs before JSON.parse.
export function parseFlat(text) {
  if (typeof text !== 'string' || Buffer.byteLength(text) > MAX_BODY) throw new Rejected(413, 'body_too_large');
  const source = text.trim();
  if (!source.startsWith('{') || !source.endsWith('}')) throw new Rejected(400, 'invalid_schema');
  const inner = source.slice(1, -1), result = Object.create(null);
  let offset = 0;
  const token = /\s*"([A-Za-z][A-Za-z0-9]*)"\s*:\s*("[A-Za-z0-9._-]*"|-?(?:0|[1-9][0-9]*)|true|false|null)\s*(,|$)/y;
  while (offset < inner.length) {
    token.lastIndex = offset;
    const match = token.exec(inner);
    if (!match || Object.hasOwn(result, match[1])) throw new Rejected(400, 'invalid_schema');
    result[match[1]] = JSON.parse(match[2]); offset = token.lastIndex;
    if (match[3] === ',' && !inner.slice(offset).trim()) throw new Rejected(400, 'invalid_schema');
  }
  return result;
}
export function validate(object, definition) {
  if (Object.keys(object).length !== Object.keys(definition).length) throw new Rejected(400, 'invalid_schema');
  for (const [key, rule] of Object.entries(definition)) if (!Object.hasOwn(object, key) || !rule(object[key])) throw new Rejected(400, 'invalid_schema');
  return object;
}
export function reportPayload(text) {
  const parsed = parseFlat(text);
  const incoming = validate(parsed, {...reportFields(parsed.schema), ...transport});
  const report = Object.fromEntries(Object.keys(fields).map(key => [key, incoming[key]]));
  return {report, challengeId: incoming.challengeId, proof: incoming.proof};
}
export function challengePayload(text) { return validate(parseFlat(text), {reportId: fields.reportId}); }
export function signature(report) {
  // Group by failure and compatibility class, never by a person/device ID.
  const compatibility = [report.appVersion, report.runtime, report.errorCode, report.operation, report.stage, report.androidApi, report.abi, report.pageSize];
  if (report.schema === 2) compatibility.unshift(2);
  return hash(JSON.stringify(compatibility));
}
export function sampleHash(report) {
  return hash(JSON.stringify(Object.entries(report).filter(([key]) => key !== 'reportId')));
}
export function proofValid(challengeId, proof) { return hash(challengeId + ':' + proof).startsWith('0000'); }
export function publicBody(report, marker) {
  return `Anonymous diagnostic report, explicitly submitted from Mobile Codex.\n\nOnly the fixed diagnostic fields shown below were submitted. No account, conversation, file, path or raw log content is accepted. Values are untrusted device observations. This report does not establish a root cause.\n\n\`\`\`json\n${JSON.stringify(report, null, 2)}\n\`\`\`\n\n<!-- ${marker} -->`;
}
export function issueTitle(report) {
  return report.errorCode === 'relay_verification' ? '[Diagnostic relay verification] Synthetic test — no user data' : `[App diagnostic] ${report.errorCode} · Android API ${report.androidApi} · ${report.appVersion}`;
}
