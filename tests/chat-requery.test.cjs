const {test} = require('node:test');
const assert = require('node:assert/strict');
const {match} = require('../app/src/main/assets/chat-requery.js');
const user = (text,time=100) => ({author:{role:'user'},content:{parts:[text]},create_time:time});
const answer = text => ({author:{role:'assistant'},recipient:'all',status:'finished_successfully',content:{content_type:'text',parts:[text]},metadata:{resolved_model_slug:'gpt-test'}});
const node = (parent,message) => ({parent,message});

test('observed user id distinguishes repeated questions', () => {
  const mapping={u1:node(null,user('같은 질문')),a1:node('u1',answer('첫 답')),
    u2:node('a1',user('같은 질문',110)),a2:node('u2',answer('둘째 답'))};
  assert.equal(match({mapping,current_node:'a2'},'같은 질문',99,'u2').reply,'둘째 답');
  assert.equal(match({mapping,current_node:'a2'},'같은 질문',99,'').kind,'ambiguous');
});

test('current branch excludes an answer from another branch', () => {
  const mapping={u:node(null,user('질문')),a:node('u',answer('현재 답')),
    b:node('u',answer('다른 분기 답'))};
  assert.equal(match({mapping,current_node:'a'},'질문',99,'u').reply,'현재 답');
  assert.equal(match({mapping},'질문',99,'u').kind,'ambiguous');
});

test('later user turns and tool messages are never adopted as this reply', () => {
  const mapping={u:node(null,user('질문')),tool:node('u',{author:{role:'tool'},status:'finished_successfully',content:{parts:['도구 출력']}}),
    later:node('tool',user('다른 질문',110)),a:node('later',answer('다른 답'))};
  assert.equal(match({mapping,current_node:'a'},'질문',99,'u').kind,'waiting');
});

test('an observed id that is not saved does not fall back to matching text', () => {
  const mapping={u:node(null,user('질문')),a:node('u',answer('답'))};
  assert.equal(match({mapping,current_node:'a'},'질문',99,'missing').kind,'waiting');
});

test('only a finished user-facing text answer qualifies', () => {
  const mapping={u:node(null,user('질문')),a:node('u',{...answer('초안'),status:'in_progress'}),
    tool:node('u',{...answer('도구'),recipient:'python'})};
  assert.equal(match({mapping},'질문',99,'u').kind,'waiting');
});
