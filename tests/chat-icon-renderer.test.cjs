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

test('renders an assistant turn after the web UI moves its role to the article', async () => {
  const w = setup('<main><article data-turn="user">[[icon:안녕]]</article><article data-turn="assistant"><h6 class="sr-only">ChatGPT 답변:</h6><p>[[icon:안녕]]</p></article></main>');
  assert.equal(w.document.querySelectorAll('article[data-turn="assistant"] .mc-chat-icon img').length, 1);
  assert.equal(w.document.querySelector('article[data-turn="user"]').textContent, '[[icon:안녕]]');
  const added = w.document.createElement('article'); added.dataset.turn = 'assistant'; added.innerHTML = '<p>[[icon:해냈다]]</p>';
  w.document.querySelector('main').append(added); await tick();
  assert.equal(added.querySelector('.mc-chat-icon img').alt, '해냈다');
});

test('uses the turn heading when the assistant role attribute is absent', () => {
  const w = setup('<main><article data-testid="conversation-turn-1"><h6 class="sr-only">내가 한 말:</h6><p>[[icon:안녕]]</p></article><article data-testid="conversation-turn-2"><h6 class="sr-only">ChatGPT 답변:</h6><p>[[icon:안녕]]</p></article></main>');
  assert.equal(w.document.querySelectorAll('.mc-chat-icon img').length, 1);
  assert.equal(w.document.querySelector('[data-testid="conversation-turn-1"]').textContent.includes('[[icon:안녕]]'), true);
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

// Shape observed in the running WebView, with no conversation/message IDs or
// user text copied into this fixture. User and assistant share an outer turn.
const currentTurn = '<div data-content-search-turn-key="fixture"><div><h4 class="sr-only">You said:</h4><p id="user">[[icon:안녕]]</p></div>'+
 '<div data-content-search-unit-key="fixture"><h4 data-conversation-role="assistant" class="sr-only">ChatGPT said:</h4>'+
 '<div><div data-markdown-text-style="standard"><p id="answer"><span>[[icon:안녕]]</span></p></div></div></div></div>';

test('observed role heading identifies only its answer block, never the shared user turn',()=>{
 const w=setup('<main>'+currentTurn+'</main>'),d=w.document;
 assert.equal(d.querySelectorAll('.mc-chat-icon img').length,1);
 assert.equal(d.querySelector('#answer img').alt,'안녕');
 assert.equal(d.getElementById('user').textContent,'[[icon:안녕]]');
 assert.equal(d.querySelector('h4[data-conversation-role]').textContent,'ChatGPT said:');
});

test('observes role-heading turns on navigation, late role assignment and streamed text',async()=>{
 const w=setup('<main></main>'),d=w.document,main=d.querySelector('main');
 main.innerHTML=currentTurn.replace(' data-conversation-role="assistant"','');await tick();
 assert.equal(d.querySelectorAll('.mc-chat-icon').length,0);
 main.querySelector('[data-content-search-unit-key] h4').setAttribute('data-conversation-role','assistant');await tick();
 assert.equal(d.querySelectorAll('.mc-chat-icon img').length,1);
 const text=d.createTextNode('[[icon:');d.getElementById('answer').append(text);await tick();
 text.nodeValue='[[icon:해냈다]]';await tick();
 assert.equal(d.querySelectorAll('.mc-chat-icon img').length,2);
 main.replaceChildren();main.innerHTML=currentTurn;await tick();
 assert.equal(d.querySelectorAll('.mc-chat-icon img').length,1);
 assert.equal(d.getElementById('user').textContent,'[[icon:안녕]]');
});

test('continues observing after the document body is replaced',async()=>{
 const w=setup('<main></main>'),d=w.document,body=d.createElement('body');
 body.innerHTML='<main>'+currentTurn+'</main>';d.body.replaceWith(body);await tick();
 assert.equal(d.querySelectorAll('.mc-chat-icon img').length,1);
});
