/* No networking here: consent refers to an immutable native snapshot. */
(function(root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory;
  else root.MobileCodexReports = factory;
})(typeof window === 'object' ? window : globalThis, function(options) {
  'use strict';
  const {document, call, t, show, close} = options;
  const $ = id => document.getElementById(id);
  let reportId = '', sending = false, epoch = 0, availabilityRevision = 0, cancelling = null;
  const status = text => { $('report-status').textContent = text; };
  function controls() { $('report-send').disabled = sending || !reportId; $('report-send').textContent = t(sending ? '전송 중…' : '공개 GitHub에 전송'); }
  function launchers(available) {
    for (const id of ['report-open', 'report-login-open', 'report-error-open']) $(id).hidden = !available;
  }
  function clearPreview() {
    ++epoch; reportId = '';
    $('report-payload').textContent = '';
    controls();
  }
  function available(value) {
    ++availabilityRevision;
    const active = value === true || value?.available === true;
    launchers(active);
    if (!active || value?.previewInvalidated === true) {
      clearPreview();
      $('report-result').hidden = true;
      delete $('report-result').dataset.url;
      if (!sending) status(t(active ? '오류 정보가 변경되었습니다. 다시 열어 확인해 주세요.' : '아직 전송할 오류 정보가 없습니다. 오류가 발생한 뒤 다시 열어 주세요.'));
    }
  }
  async function refresh() {
    const revision = availabilityRevision;
    try {
      const value = await call('reports.status');
      if (revision === availabilityRevision) available(value);
    } catch { /* Keep launchers hidden until native availability is known. */ }
  }
  launchers(false);
  controls();
  async function open() {
    if (cancelling) await cancelling;
    if (sending) { show('report-dialog'); return; }
    const generation = ++epoch; reportId = ''; controls();
    $('report-payload').textContent = ''; $('report-result').hidden = true;
    status(t('오류 정보를 준비하고 있습니다.')); show('report-dialog');
    try {
      const value = await call('reports.preview'); if (generation !== epoch) return;
      if (value.status !== 'preview') { if (value.status === 'unavailable') available(false); status(t('아직 전송할 오류 정보가 없습니다. 오류가 발생한 뒤 다시 열어 주세요.')); return; }
      reportId = value.reportId;
      $('report-payload').textContent = JSON.stringify(value.payload, null, 2);
      status(''); controls();
    } catch { if (generation === epoch) status(t('오류 정보를 준비하지 못했습니다. 다시 열어 주세요.')); }
  }
  async function send() {
    if (sending || !reportId) return;
    sending = true; const revision = availabilityRevision; controls();
    status(t('전송 중입니다. 취소해도 이미 등록된 글은 남을 수 있습니다.'));
    try {
      const value = await call('reports.send', {reportId, consent:true});
      if (value.status === 'sent' && /^https:\/\/github\.com\/nokryong\/mobile-codex\/issues\/[1-9][0-9]*$/.test(value.url)) {
        $('report-result').dataset.url = value.url; $('report-result').hidden = false;
        status(t(value.grouped ? '같은 오류의 기존 이슈에 접수했습니다.' : '오류 정보를 GitHub에 등록했습니다.'));
        clearPreview();
        if (revision === availabilityRevision) launchers(false);
      } else status(t(value.status === 'cancelled' ? '전송을 취소했습니다. 다시 보내려면 오류 정보를 다시 열어 확인해 주세요.' : '접수를 확인하지 못했습니다. 인터넷 연결을 확인하고 다시 전송해 주세요. 같은 신고는 중복 등록하지 않습니다.'));
    } catch { status(t('접수를 확인하지 못했습니다. 인터넷 연결을 확인하고 다시 전송해 주세요. 같은 신고는 중복 등록하지 않습니다.')); }
    finally { sending = false; controls(); }
  }
  async function cancel() {
    if (cancelling) return cancelling;
    clearPreview();
    if (sending) status(t('전송을 취소하고 있습니다…'));
    else close('report-dialog');
    // Reopening waits for native revocation, so an older cancel cannot erase
    // a newly requested consent snapshot. The report itself remains retryable.
    cancelling = call('reports.cancel').catch(() => {});
    try { await cancelling; } finally { cancelling = null; }
  }
  $('report-send').addEventListener('click', send);
  $('report-open').addEventListener('click', open);
  $('report-login-open').addEventListener('click', open);
  $('report-error-open').addEventListener('click', open);
  $('report-result').addEventListener('click', () => call('ui.externalBrowser', {url:$('report-result').dataset.url}).catch(() => status(t('링크를 열지 못했습니다. 다시 시도해 주세요.'))));
  return {open, send, cancel, available, refresh, isSending:() => sending};
});
