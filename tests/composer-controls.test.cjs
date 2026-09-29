const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');

const webRoot = 'app/src/main/assets/web/';
const html = fs.readFileSync(webRoot + 'index.html', 'utf8');
const css = fs.readFileSync(webRoot + 'app.css', 'utf8') + '\n' + fs.readFileSync(webRoot + 'visual-system.css', 'utf8');

function rulesOf(sheet) {
  return Array.from(sheet.cssRules).flatMap(rule => rule.cssRules ? rulesOf(rule) : [rule]);
}

function setup(width) {
  const dom = new JSDOM(html.replace('</head>', '<style>' + css + '</style></head>'), {
    pretendToBeVisual: true,
    beforeParse(window) {
      Object.defineProperty(window, 'innerWidth', {value: width, configurable: true});
    }
  });
  return dom;
}

test('expanded composer keeps 44px targets at every supported text zoom', () => {
  for (const width of [320, 393, 800, 1280]) {
    const dom = setup(width);
    const {document, getComputedStyle} = dom.window;
    document.getElementById('composer').classList.add('composer-expanded');
    document.getElementById('prompt').focus();
    document.getElementById('send').hidden = true;
    document.getElementById('stop').hidden = false;
    document.getElementById('fast-mode').setAttribute('aria-pressed', 'true');
    for (const zoom of [75, 85, 100, 150]) {
      document.documentElement.style.setProperty('--text-size-factor', String(zoom / 100));
      for (const id of ['fast-mode', 'voice-input', 'send', 'stop']) {
        const style = getComputedStyle(document.getElementById(id));
        assert.equal(style.width, '44px', `${id} width at ${width}px / ${zoom}%`);
        assert.equal(style.height, '44px', `${id} height at ${width}px / ${zoom}%`);
        assert.equal(style.minHeight, '44px', `${id} target at ${width}px / ${zoom}%`);
      }
      assert.equal(getComputedStyle(document.getElementById('approval-mode')).height, '44px');
      assert.equal(getComputedStyle(document.getElementById('composer-options')).minHeight, '44px');
    }
    const fast = document.getElementById('fast-mode');
    fast.disabled = true;
    assert.equal(getComputedStyle(fast).opacity, '0.42', `disabled Fast remains visibly disabled at ${width}px`);
    dom.window.close();
  }
});

test('composer visible surfaces use a 32px token while icons remain compact', () => {
  const dom = setup(393);
  const {document, getComputedStyle} = dom.window;
  const rules = rulesOf(document.styleSheets[0]);
  const rule = selector => rules.filter(candidate => candidate.selectorText === selector).at(-1);

  assert.equal(getComputedStyle(document.getElementById('fast-mode').querySelector('svg')).width, '17px');
  assert.equal(getComputedStyle(document.getElementById('voice-input').querySelector('svg')).height, '17px');
  assert.equal(getComputedStyle(document.getElementById('send').querySelector('svg')).width, '17px');
  assert.equal(rule('.approval-mode-picker::before').style.inset, '6px 0');
  assert.equal(rule('.composer-bottom .options-button::before').style.inset, '6px 0');
  assert.equal(rule('.send-button::before').style.inset, 'calc((44px - var(--composer-visible-control-size)) / 2)');
  assert.equal(rule('.composer-actions .fast-mode-button::before').style.width, 'var(--composer-visible-control-size)');
  assert.equal(rule('.composer-actions .fast-mode-button[aria-pressed=true]').style.background, 'transparent');
  assert.equal(rule('.composer-actions .fast-mode-button[aria-pressed=true]::before').style.background, '#fff3cd');
  assert.equal(rule('html[data-theme=dark] .composer-actions .fast-mode-button[aria-pressed=true]::before').style.background, '#453b25');
  dom.window.close();
});
