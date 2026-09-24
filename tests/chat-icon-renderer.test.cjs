const {test, afterEach} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');

const script = fs.readFileSync('app/src/main/assets/chat-icon-renderer.js', 'utf8');
const opened = [];
afterEach(() => { for (const dom of opened.splice(0)) dom.window.close(); });
function setup(html) {
  const dom = new JSDOM(html, {url: 'https://chatgpt.com/c/test', runScripts: 'outside-only'});
  opened.push(dom); dom.window.eval(script);
  return dom.window;
}
const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

test('renders exact names, filename aliases, and semantic categories at 128px', () => {
  const w = setup('<div data-message-author-role="assistant">[[icon:해냈다]] [01-idle] [[icon:질문]]</div>');
  const images = w.document.querySelectorAll('.mc-chat-icon img');
  assert.equal(images.length, 3);
  assert.equal(images[0].alt, '해냈다');
  assert.equal(images[1].alt, '대기중');
  assert.equal(images[2].alt, '주인님?');
  assert.match(images[0].src, /https:\/\/chatgpt\.com\/__mobile_codex_icons__\/06-done\.png$/);
  assert.equal(images[0].style.width, '');
  assert.match(w.document.getElementById('mc-chat-icon-renderer-style').textContent, /width:128px/);
});

test('does not process user text, composer controls, or code', () => {
  const w = setup('<div data-message-author-role="user">[[icon:해냈다]]</div><div data-message-author-role="assistant"><code>[[icon:해냈다]]</code><input value="[[icon:해냈다]]"><pre>[[icon:해냈다]]</pre></div>');
  assert.equal(w.document.querySelectorAll('.mc-chat-icon').length, 0);
  assert.equal(w.document.querySelector('[data-message-author-role="user"]').textContent, '[[icon:해냈다]]');
});

test('observes assistant nodes added by SPA navigation and ignores unrelated additions', async () => {
  const w = setup('<main></main>');
  const assistant = w.document.createElement('article');
  assistant.dataset.messageAuthorRole = 'assistant'; assistant.textContent = '새 응답 [[icon:하트]]';
  w.document.querySelector('main').appendChild(assistant);
  const user = w.document.createElement('article');
  user.dataset.messageAuthorRole = 'user'; user.textContent = '[[icon:해냈다]]';
  w.document.body.appendChild(user);
  await tick();
  assert.equal(assistant.querySelectorAll('.mc-chat-icon').length, 1);
  assert.equal(user.querySelectorAll('.mc-chat-icon').length, 0);
});

test('reinjection is idempotent and preserves the original token when an image fails', () => {
  const w = setup('<div data-message-author-role="assistant">[[icon:해냈다]]</div>');
  w.eval(script);
  assert.equal(w.document.querySelectorAll('.mc-chat-icon').length, 1);
  assert.equal(w.document.querySelectorAll('#mc-chat-icon-renderer-style').length, 1);
  const image = w.document.querySelector('.mc-chat-icon img');
  image.dispatchEvent(new w.Event('error'));
  const wrapper = w.document.querySelector('.mc-chat-icon');
  assert.equal(wrapper.textContent, '[[icon:해냈다]]');
  assert.equal(wrapper.querySelector('img'), null);
});
