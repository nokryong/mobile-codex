const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {JSDOM} = require('jsdom');
const C = require('../app/src/main/assets/web/ui-core.js');
const changed = diff => diff.hunks.flatMap(hunk=>hunk.lines).filter(line=>line.kind==='-'||line.kind==='+');
const visibleCount = diff => diff.hunks.reduce((sum,hunk)=>sum+1+hunk.lines.length,0)+(diff.truncated?1:0)+(diff.approximate?1:0);

test('distant edits form numbered hunks and unchanged middle lines stay unchanged',()=>{
  const a=Array.from({length:50},(_,i)=>'line '+i),b=a.slice();b[3]='edit 3';b[40]='edit 40';
  const diff=C.fileDiff(a.join('\n')+'\n',b.join('\n')+'\n');
  assert.equal(diff.hunks.length,2);assert.equal(diff.approximate,false);assert.equal(diff.truncated,false);
  assert.deepEqual(changed(diff).map(line=>[line.kind,line.text]),[['-','line 3'],['+','edit 3'],['-','line 40'],['+','edit 40']]);
  assert.deepEqual(diff.hunks.map(h=>[h.oldStart,h.oldCount,h.newStart,h.newCount]),[[1,7,1,7],[38,7,38,7]]);
  assert.ok(diff.hunks.flatMap(h=>h.lines).some(line=>line.kind===' '&&line.text==='line 4'));
});

test('empty files, insertion, deletion and final newline changes have correct ranges',()=>{
  assert.equal(C.fileDiff('','').identical,true);assert.deepEqual(C.fileDiff('','').hunks,[]);
  const add=C.fileDiff('','one\ntwo\n');assert.deepEqual(add.hunks.map(h=>[h.oldStart,h.oldCount,h.newStart,h.newCount]),[[0,0,1,2]]);
  assert.ok(changed(add).every(line=>line.kind==='+'));
  const remove=C.fileDiff('one\ntwo\n','');assert.deepEqual(remove.hunks.map(h=>[h.oldStart,h.oldCount,h.newStart,h.newCount]),[[1,2,0,0]]);
  assert.ok(changed(remove).every(line=>line.kind==='-'));
  const newline=C.fileDiff('last','last\n');assert.deepEqual(changed(newline).map(line=>[line.kind,line.text,line.noNewline]),[['-','last',true],['+','last',false]]);
  assert.equal(C.fileDiff('same\n','same\n').identical,true);
  assert.deepEqual(changed(C.fileDiff('a\nb\nc\n','a\ninsert\nb\nc\n')).map(line=>[line.kind,line.text]),[['+','insert']]);
  assert.deepEqual(changed(C.fileDiff('a\nb\nc\n','a\nc\n')).map(line=>[line.kind,line.text]),[['-','b']]);
});

test('repeated unchanged lines align around distant replacements',()=>{
  const a=Array(80).fill('repeat'),b=a.slice();a[5]='old first';a[65]='old last';b[5]='new first';b[65]='new last';
  const diff=C.fileDiff(a.join('\n'),b.join('\n'));
  assert.equal(diff.approximate,false);assert.equal(diff.hunks.length,2);
  assert.deepEqual(changed(diff).map(line=>[line.kind,line.text]),[['-','old first'],['+','new first'],['-','old last'],['+','new last']]);
});

test('small random edits reconstruct the requested file including repeated lines',()=>{
  let seed=42;const random=n=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed%n;};
  for(let trial=0;trial<200;trial++){
    const a=Array.from({length:random(20)},()=>String.fromCharCode(97+random(4))),b=a.slice();
    for(let edit=0;edit<5;edit++){const index=random(b.length+1);b.splice(index,random(2),...Array.from({length:random(3)},()=>String.fromCharCode(97+random(4))));}
    const before=a.join('\n')+(a.length&&random(2)?'\n':''),after=b.join('\n')+(b.length&&random(2)?'\n':''),diff=C.fileDiff(before,after);
    assert.equal(diff.approximate,false);assert.equal(diff.truncated,false);
    const original=before.match(/[^\n]*\n|[^\n]+$/g)||[];let cursor=0,reconstructed='';
    for(const hunk of diff.hunks){
      const start=hunk.oldStart-(hunk.oldCount?1:0);reconstructed+=original.slice(cursor,start).join('');
      for(const line of hunk.lines)if(line.kind!=='-')reconstructed+=line.text+(line.noNewline?'':'\n');
      cursor=start+hunk.oldCount;
    }
    reconstructed+=original.slice(cursor).join('');assert.equal(reconstructed,after,'random trial '+trial);
  }
});

test('bounded rewrite previews retain additions and removals and explain omissions',()=>{
  const a=Array.from({length:9000},(_,i)=>'old '+i).join('\n'),b=Array.from({length:9000},(_,i)=>'new '+i).join('\n');
  const diff=C.fileDiff(a,b,{maxLines:80,workLimit:500});
  assert.equal(diff.approximate,true);assert.equal(diff.truncated,true);assert.ok(visibleCount(diff)<=80);
  assert.ok(changed(diff).some(line=>line.kind==='-'));assert.ok(changed(diff).some(line=>line.kind==='+'));
  assert.ok(diff.hunks.some(h=>h.lines.some(line=>line.kind==='omitted')));
});

test('many separate edits have bounded output while retaining both change directions',()=>{
  const a=Array.from({length:5000},(_,i)=>'line '+i),b=a.slice();for(let i=5;i<b.length;i+=10)b[i]='changed '+i;
  const diff=C.fileDiff(a.join('\n'),b.join('\n'),{maxLines:80});
  assert.equal(diff.truncated,true);assert.ok(visibleCount(diff)<=80,visibleCount(diff)+' preview rows');
  assert.ok(changed(diff).some(line=>line.kind==='-'));assert.ok(changed(diff).some(line=>line.kind==='+'));
  const separate=C.fileDiff('remove\n'+a.join('\n'),a.join('\n')+'\nadd',{maxLines:16});
  assert.ok(visibleCount(separate)<=16);assert.ok(changed(separate).some(line=>line.kind==='-'));assert.ok(changed(separate).some(line=>line.kind==='+'));
});

test('8 MiB inputs with millions of short lines and a long changed line stay bounded',()=>{
  const huge='x\n'.repeat(4*1024*1024),diff=C.fileDiff(huge,huge+'last addition\n');
  assert.equal(diff.truncated,false);assert.equal(diff.identical,false);assert.ok(visibleCount(diff)<=2000);
  assert.ok(changed(diff).some(line=>line.kind==='+'&&line.text==='last addition'));
  assert.equal(changed(diff).filter(line=>line.kind==='-').length,0);
  assert.equal(changed(diff)[0].newLine,4*1024*1024+1);
  const prepend=C.fileDiff(huge,'first addition\n'+huge);
  assert.deepEqual(changed(prepend).map(line=>[line.kind,line.text]),[['+','first addition']]);
  const long=C.fileDiff('a'.repeat(8*1024*1024),'b'.repeat(8*1024*1024));
  assert.equal(long.truncated,true);assert.ok(changed(long).every(line=>line.text.length<=4002));
});

test('diff preview renders markup as text and shows hunk ranges and truncation notice',()=>{
  const root='app/src/main/assets/web/',dom=new JSDOM(fs.readFileSync(root+'index.html','utf8'),{url:'https://appassets.androidplatform.net',runScripts:'outside-only'});
  try{
    const w=dom.window;w.eval(fs.readFileSync(root+'ui-core.js','utf8'));
    const app=fs.readFileSync(root+'app.js','utf8'),start=app.indexOf('  function showFileDiff(before, after) {'),end=app.indexOf('\n  async function previewChange',start);
    w.eval('const C=window.UiCore,$=id=>document.getElementById(id),t=text=>text;function node(tag,text,cls){const el=document.createElement(tag);if(text!==undefined)el.textContent=text;if(cls)el.className=cls;return el;}\n'+app.slice(start,end)+'\nwindow.previewTest=showFileDiff;');
    w.previewTest('before\n','<img src=x onerror=alert(1)>\n');
    const box=w.document.getElementById('change-diff');assert.equal(box.querySelector('img'),null);assert.match(box.textContent,/@@ -1,1 \+1,1 @@/);assert.match(box.textContent,/<img src=x onerror=alert\(1\)>/);
    w.previewTest(Array(6000).fill('before').join('\n'),Array(6000).fill('after').join('\n'));
    assert.ok(box.querySelector('.diff-removed'));assert.ok(box.querySelector('.diff-added'));assert.match(box.textContent,/일부 변경 내용은 생략/);assert.match(box.textContent,/간략히 비교/);
    w.previewTest('no newline','no newline\n');assert.match(box.textContent,/마지막 줄에 줄바꿈 없음/);
  }finally{dom.window.close();}
});
