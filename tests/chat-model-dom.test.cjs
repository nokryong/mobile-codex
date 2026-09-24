const {test, afterEach} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');

const script = fs.readFileSync('app/src/main/assets/chat-model-dom.js', 'utf8');
const opened = [];
afterEach(() => { for (const dom of opened.splice(0)) dom.window.close(); });
function setup(html) {
  const dom = new JSDOM(html, {url:'https://chatgpt.com/',runScripts:'outside-only'});
  opened.push(dom);
  const {window:w} = dom;
  w.Element.prototype.getBoundingClientRect = function () { return {x:10,y:10,width:100,height:40,left:10,top:10,right:110,bottom:50}; };
  w.eval(script);
  return {w,adapter:w.MCChatModelDom};
}
const trigger = '<button aria-haspopup="menu" aria-expanded="true" aria-controls="settings">추론 수준</button>';

test('ignores an unrelated range and uses the current settings popup', () => {
  const {adapter} = setup('<input type="range" value="0">'+trigger+
    '<div role="menu" id="settings"><div role="menuitem" aria-label="성능" aria-describedby="value hint" tabindex="0"></div></div>'+
    '<span id="value">High, 5개 중 3번째.</span><span id="hint">왼쪽/오른쪽 화살표 키로 성능을 조정합니다.</span>');
  assert.equal(adapter.inspect().type,'stepper');
  assert.equal(adapter.inspect().level,'High');
  assert.deepEqual([adapter.inspect().position,adapter.inspect().total],[3,5]);
});

test('ignores a hidden old menu', () => {
  const {adapter} = setup(trigger+'<div role="menu" style="display:none"><div role="menuitemradio">Instant</div></div>'+
    '<div role="menu" id="settings"><div role="menuitem" aria-haspopup="menu">성능</div></div>');
  assert.equal(adapter.inspect().type,'submenu');
});

test('submenu opener is not treated as a slider', () => {
  const {adapter} = setup(trigger+'<div role="menu" id="settings"><div role="menuitem" aria-haspopup="menu">성능</div></div>');
  assert.equal(adapter.inspect().type,'submenu');
  assert.equal(adapter.focus().type,'submenu');
});

test('radio options use activation and the chosen checked state', () => {
  const {w,adapter} = setup(trigger+'<div role="menu" id="settings"><div role="menuitemradio" aria-checked="true">High</div><div role="menuitemradio" aria-checked="false">X-High</div></div>');
  const options = w.document.querySelectorAll('[role="menuitemradio"]');
  options[1].addEventListener('click', () => { options[0].setAttribute('aria-checked','false'); options[1].setAttribute('aria-checked','true'); });
  assert.equal(adapter.inspect().type,'options');
  assert.equal(adapter.choose('X-High').ok,true);
  assert.equal(adapter.inspect().options[1].checked,'true');
  assert.equal(adapter.inspect().level,'X-High');
});

test('follows an aria-controls link to the open submenu', () => {
  const {adapter} = setup(trigger+'<div role="menu" id="settings"><div role="menuitem" aria-haspopup="menu" aria-controls="performance">성능</div></div>'+
    '<div role="menu" id="performance"><div role="menuitemradio" aria-checked="true">High</div><div role="menuitemradio" aria-checked="false">X-High</div></div>');
  assert.equal(adapter.inspect().type,'options');
  assert.equal(adapter.inspect().level,'High');
});

test('real slider reads its numeric position and confirms DOM focus', () => {
  const {w,adapter} = setup(trigger+'<div role="menu" id="settings"><input type="range" min="0" max="4" value="2"></div>');
  assert.equal(adapter.inspect().type,'slider');
  assert.equal(adapter.inspect().level,'High');
  const input = w.document.querySelector('input'); input.focus = () => {};
  assert.equal(adapter.focus().ok,false);
});

test('normalizes localized labels without treating arbitrary text as a model', () => {
  const {adapter} = setup('<button aria-haspopup="menu">  매우　높음  </button>');
  assert.equal(adapter.inspect().level,'X-High');
  assert.equal(adapter.level('X - High, 5개 중 4번째.'),'X-High');
  assert.equal(adapter.level('Prompt settings'),'');
});

test('recognizes the reasoning trigger before its current label is available', () => {
  const {adapter} = setup('<button aria-haspopup="menu" aria-label="추론 수준" aria-expanded="false"></button>');
  assert.equal(adapter.inspect().state,'closed');
  assert.equal(adapter.inspect().level,'');
});

test('disabled and unavailable options fail closed', () => {
  const {adapter} = setup(trigger+'<div role="menu" id="settings"><div role="menuitemradio" aria-disabled="true">Pro</div></div>');
  assert.equal(adapter.choose('Pro').reason,'disabled');
  assert.equal(adapter.choose('Instant').reason,'unsupported');
});

test('multiple live controls are ambiguous instead of choosing the first', () => {
  const {adapter} = setup(trigger+'<div role="menu" id="settings"><div role="slider" aria-valuenow="2" aria-valuemin="0" aria-valuemax="4"></div><div role="menuitem" aria-label="성능" aria-describedby="value"></div></div><span id="value">High, 5개 중 3번째.</span>');
  assert.equal(adapter.inspect().state,'ambiguous');
});
