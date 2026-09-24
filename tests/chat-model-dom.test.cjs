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
  w.Element.prototype.getBoundingClientRect = function () { const y=this.hasAttribute('data-far')?500:10;
    return {x:10,y,width:100,height:40,left:10,top:y,right:110,bottom:y+40}; };
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

test('uses the popup linked to the setting button when another popup is visible', () => {
  const {adapter} = setup(trigger+'<div role="menu" id="unrelated"><div role="menuitemradio">Pro</div></div>'+
    '<div role="menu" id="settings"><div role="menuitem" aria-label="성능" aria-describedby="value hint"></div></div>'+
    '<span id="value">High, 5개 중 3번째.</span><span id="hint">화살표 키로 조정합니다.</span>');
  assert.equal(adapter.inspect().type,'stepper');
  assert.equal(adapter.inspect().level,'High');
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

test('finds a composer model button without aria-haspopup when the menu is closed', () => {
  const {adapter} = setup('<button data-far>Pro</button><form><div id="prompt-textarea" contenteditable="true"></div><button type="button">6 Pro</button></form>');
  assert.equal(adapter.inspect().state,'closed');
  assert.equal(adapter.inspect().level,'Pro');
  assert.equal(adapter.inspect().trigger.label,'Pro');
});

test('finds a nearby model button outside the prompt form', () => {
  const {adapter} = setup('<form><div id="prompt-textarea"></div><button type="button">Send</button></form>'+
    '<button aria-haspopup="menu">High</button>');
  assert.equal(adapter.inspect().state,'closed');
  assert.equal(adapter.inspect().level,'High');
});

test('finds the unique composer menu trigger when its visible label is absent from DOM text', () => {
  const {adapter} = setup('<button aria-haspopup="menu">Other menu</button><div id="prompt-textarea" data-far></div>'+
    '<button aria-haspopup="menu" data-far aria-expanded="false"></button>');
  const state = adapter.inspect();
  assert.equal(state.state,'closed');
  assert.equal(state.trigger.haspopup,'menu');
  assert.equal(state.level,'');
});

test('does not guess among multiple unnamed composer menu triggers', () => {
  const {adapter} = setup('<div id="prompt-textarea" data-far></div>'+
    '<button aria-haspopup="menu" data-far></button><button aria-haspopup="menu" data-far></button>');
  assert.equal(adapter.inspect().state,'ambiguous');
});

test('reads a model label referenced by aria-labelledby', () => {
  const {adapter} = setup('<span id="model-name">6 Pro</span><button aria-haspopup="menu" aria-labelledby="model-name"></button>');
  assert.equal(adapter.inspect().level,'Pro');
});

test('reads the visible model value even when a generic aria-label is present', () => {
  const {adapter} = setup('<button aria-haspopup="menu" aria-label="모델 선택">6 Pro</button>');
  assert.equal(adapter.inspect().state,'closed');
  assert.equal(adapter.inspect().level,'Pro');
});

test('does not mistake a Pro submenu button for the composer model trigger', () => {
  const {adapter} = setup('<form><div id="prompt-textarea"></div><button aria-haspopup="menu">Medium</button></form>'+
    '<div role="menu"><button aria-haspopup="menu">Pro</button><div role="menuitem" aria-label="성능" aria-describedby="value hint"></div></div>'+
    '<span id="value">Medium, 5개 중 2번째.</span><span id="hint">화살표 키로 조정합니다.</span>');
  assert.equal(adapter.inspect().state,'open');
  assert.equal(adapter.inspect().trigger.label,'Medium');
  assert.equal(adapter.inspect().level,'Medium');
});

test('reads the fifth step as Pro even when its description mentions High', () => {
  const {adapter} = setup(trigger+'<div role="menu" id="settings"><div role="menuitem" aria-label="성능" aria-describedby="value hint"></div></div>'+
    '<span id="value">Pro, 5개 중 5번째. High 다음 단계.</span><span id="hint">화살표 키로 조정합니다.</span>');
  assert.equal(adapter.inspect().type,'stepper');
  assert.equal(adapter.inspect().level,'Pro');
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

test('Pro failure evidence separates value text, descriptions and ordinal conflict', () => {
  const {adapter} = setup(trigger+'<div role="menu" id="settings"><div role="menuitem" aria-label="성능" aria-describedby="value hint"></div></div>'+
    '<span id="value">Pro, 5개 중 5번째. High 다음 단계.</span><span id="hint">화살표 키로 조정합니다.</span>');
  const state = adapter.inspect();
  assert.equal(state.level, 'Pro');
  assert.equal(state.levelFromText, 'High');
  assert.equal(state.levelFromOrdinal, 'Pro');
  assert.equal(state.levelSource, 'stepper-ordinal');
  assert.equal(state.levelConflict, true);
  assert.equal(state.control.valueEvidence.ariaValueText, '');
  assert.deepEqual(Array.from(state.control.valueEvidence.descriptions),
    ['Pro, 5개 중 5번째. High 다음 단계.', '화살표 키로 조정합니다.']);
});

test('counts remain available when entering Pro introduces a second control', () => {
  const {adapter} = setup(trigger+'<div role="menu" id="settings"><input type="range"><div role="radio">Pro Extended</div></div>');
  const state = adapter.inspect();
  assert.equal(state.state,'ambiguous');
  assert.equal(state.triggerCount,1); assert.equal(state.popupCount,1); assert.equal(state.controlCount,2);
  assert.equal(state.controls.length,2);
});

test('Pro suboptions are ambiguous, not disabled, and preserve setting labels', () => {
  const {w,adapter} = setup(trigger+'<div role="menu" id="settings"><div role="radio">Pro Standard</div><div role="radio">Pro Extended</div></div>');
  let clicks = 0; w.document.querySelectorAll('[role="radio"]').forEach(e => e.addEventListener('click', () => clicks++));
  const result = adapter.choose('Pro');
  assert.equal(result.reason,'ambiguous-option'); assert.equal(clicks,0);
  assert.deepEqual(Array.from(result.options, e => e.valueEvidence.displayText), ['Pro Standard','Pro Extended']);
  assert.notEqual(result.options[0].id,result.options[1].id);
});

test('native key evidence survives script reinjection and never implies value application', () => {
  const {w,adapter} = setup(trigger+'<div role="menu" id="settings"><input type="range" min="0" max="4" value="3"></div>');
  assert.equal(adapter.focus(71,9).ok,true);
  w.eval(script); // evaluateModel injects this asset on every call
  const control = w.document.querySelector('input');
  control.dispatchEvent(new w.KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true}));
  control.dispatchEvent(new w.KeyboardEvent('keyup',{key:'ArrowRight',bubbles:true}));
  const state = w.MCChatModelDom.inspect();
  assert.equal(state.level,'X-High'); // input delivered, no official handler applied a value
  assert.equal(state.inputObservation.operationId,71);
  assert.equal(state.inputObservation.events.length,2);
  assert.equal(state.inputObservation.events[0].targetInsideControl,true);
  assert.equal(state.inputObservation.events[0].trusted,false); // synthetic DOM test only
});

test('observation distinguishes wrong receiver and captures no message or typed keys', () => {
  const {w,adapter} = setup('<textarea id="secret">private message secret@example.com</textarea>'+trigger+
    '<div role="menu" id="settings"><input type="range" min="0" max="4" value="3"></div>');
  adapter.focus(72,9);
  const text = w.document.getElementById('secret'); text.focus();
  text.dispatchEvent(new w.KeyboardEvent('keydown',{key:'a',bubbles:true}));
  text.dispatchEvent(new w.KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true}));
  const state = adapter.inspect();
  assert.equal(state.inputObservation.events.length,1);
  assert.equal(state.inputObservation.events[0].targetInsideControl,false);
  assert.ok(!JSON.stringify(state).includes('private message'));
  assert.ok(!JSON.stringify(state).includes('secret@example.com'));
  adapter.stopObservation();
  text.dispatchEvent(new w.KeyboardEvent('keyup',{key:'ArrowRight',bubbles:true}));
  assert.equal(adapter.inspect().inputObservation.events.length,1);
});

test('new key observation clears the previous operation and bounds settings text', () => {
  const {w,adapter} = setup(trigger+'<div role="menu" id="settings"><input type="range" aria-valuetext="High token=SECRET email@example.com '+ 'z'.repeat(200)+'" min="0" max="4" value="2"></div>');
  adapter.focus(1,1);
  w.document.querySelector('input').dispatchEvent(new w.KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true}));
  adapter.focus(2,2);
  const state=adapter.inspect();
  assert.equal(state.inputObservation.operationId,2); assert.equal(state.inputObservation.events.length,0);
  const raw=state.control.valueEvidence.ariaValueText;
  assert.ok(raw.length <= 160); assert.ok(!raw.includes('SECRET')); assert.ok(!raw.includes('email@example.com'));
});
