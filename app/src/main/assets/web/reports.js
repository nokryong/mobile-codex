/* No networking here: consent refers to an immutable native snapshot. */
(function(root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory;
  else root.MobileCodexReports = factory;
})(typeof window === 'object' ? window : globalThis, function(options) {
  'use strict';
  const {document, call, t, show, close} = options;
  const $ = id => document.getElementById(id);
  let reportId = '', sending = false, epoch = 0;
  const status = text => { $('report-status').textContent = text; };
  function controls() { $('report-send').disabled = sending || !reportId; $('report-send').textContent = t(sending ? '전송 중…' : '공개 GitHub에 전송'); }
  async function open() {
    if (sending) { show('report-dialog'); return; }
    const generation = ++epoch; reportId = ''; controls();
    $('report-payload').textContent = ''; $('report-result').hidden = true;
    status(t('오류 정보를 준비하고 있습니다.')); show('report-dialog');
    try {
      const value = await call('reports.preview'); if (generation !== epoch) return;
      if (value.status !== 'preview') { status(t('아직 전송할 오류 정보가 없습니다. 오류가 발생한 뒤 다시 열어 주세요.')); return; }
      reportId = value.reportId;
      $('report-payload').textContent = JSON.stringify(value.payload, null, 2);
      status(''); controls();
    } catch { if (generation === epoch) status(t('오류 정보를 준비하지 못했습니다. 다시 열어 주세요.')); }
  }
  async function send() {
    if (sending || !reportId) return;
    sending = true; const generation = epoch; controls();
    status(t('전송 중입니다. 취소해도 이미 등록된 글은 남을 수 있습니다.'));
    try {
      const value = await call('reports.send', {reportId, consent:true});
      if (generation !== epoch) return;
      if (value.status === 'sent' && /^https:\/\/github\.com\/nokryong\/mobile-codex\/issues\/[1-9][0-9]*$/.test(value.url)) {
        $('report-result').dataset.url = value.url; $('report-result').hidden = false;
        status(t(value.grouped ? '같은 오류의 기존 이슈에 접수했습니다.' : '오류 정보를 GitHub에 등록했습니다.'));
        reportId = '';
      } else status(t(value.status === 'cancelled' ? '전송을 취소했습니다. 이미 등록됐을 수 있으며 재시도하면 접수 상태를 확인합니다.' : '접수를 확인하지 못했습니다. 인터넷 연결을 확인하고 다시 전송해 주세요. 같은 신고는 중복 등록하지 않습니다.'));
    } catch { if (generation === epoch) status(t('접수를 확인하지 못했습니다. 인터넷 연결을 확인하고 다시 전송해 주세요. 같은 신고는 중복 등록하지 않습니다.')); }
    finally { sending = false; controls(); }
  }
  async function cancel() {
    if (sending) {
      status(t('전송을 취소하고 있습니다…'));
      await call('reports.cancel').catch(() => {});
      // Leave the dialog open until the native attempt finishes, preserving
      // the preview/report ID for a safe retry after an uncertain response.
    } else { ++epoch; close('report-dialog'); }
  }
  $('report-send').addEventListener('click', send);
  $('report-cancel').addEventListener('click', cancel);
  $('report-open').addEventListener('click', open);
  $('report-login-open').addEventListener('click', open);
  $('report-error-open').addEventListener('click', open);
  $('report-result').addEventListener('click', () => call('ui.externalBrowser', {url:$('report-result').dataset.url}).catch(() => status(t('링크를 열지 못했습니다. 다시 시도해 주세요.'))));
  return {open, send, cancel, available() { $('report-error-open').hidden = false; $('report-login-open').hidden = false; }, isSending:() => sending};
});
