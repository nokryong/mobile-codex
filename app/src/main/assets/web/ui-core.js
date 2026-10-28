(function(root){
  'use strict';
  const chatIcons = ['idle','acknowledged','thinking','working','question','done','blocked','smug','greeting','inspecting','explaining','discovery','caution','sorry','happy','skeptical','eohu','uhehe','insult','punch','uncertain','disagree','not-allowed','file-request','reviewed','source','fixed','lol','good-grief','wink','heart','sleep'];
  const chatIconLabels = ['대기중','접수','생각중','작업중','주인님?','해냈다','왜안됨','계획대로','안녕','어디보자','잘보세요','찾았다','잠깐','미안해요','좋았어','진짜?','어흐~','우헤헤','븅신','펀치','조금애매함','아닌데?','그건안돼','파일줘봐','검토완료','출처있음','수정완료','ㄹㅇㅋㅋ','정말이지','윙크','하트','잘자'];
  const chatIconCategories = {
    질문:'question', 확인:'reviewed', 검색:'inspecting', 완료:'done', 긍정:'happy', 부정:'disagree',
    웃기:'uhehe', 사랑:'heart', 화남:'punch', 당황:'blocked', 잠:'sleep', 미안:'sorry', 요청:'file-request',
    작업중:'working', 자랑:'smug', 오류:'blocked', 응원:'heart', 인사:'greeting', 대기:'idle', 생각:'thinking',
    설명:'explaining', 발견:'discovery', 주의:'caution', 의심:'skeptical', 애매:'uncertain', 근거:'source'
  };
  const normalizeIconTag = value => String(value || '').replace(/[\u200B-\u200D\uFEFF\s]/g, '').toLowerCase();
  const chatIconTags = Object.create(null);
  chatIconLabels.forEach((label, index) => { chatIconTags[normalizeIconTag(label)] = chatIcons[index]; });
  Object.entries(chatIconCategories).forEach(([category, icon]) => { chatIconTags[normalizeIconTag(category)] = icon; });
  function fileDiff(before,after,options={}){
    // Bound both alignment work and rendered output: a large rewrite must never
    // allocate a quadratic LCS table or fill the preview with deletions alone.
    const maxLines=Math.max(16,Math.min(2000,options.maxLines||2000)),context=3;
    const lineLimit=50000,textLimit=4000;
    let work=Math.max(0,Math.min(300000,options.workLimit??300000)),approximate=false,truncated=false;
    if(String(before??'')===String(after??''))return {hunks:[],truncated:false,approximate:false,identical:true};
    function source(value){
      const text=String(value??''); let count=0,pos=0,end;
      while(pos<text.length){end=text.indexOf('\n',pos);count++;pos=end<0?text.length:end+1;}
      return {text,count,offset:0};
    }
    const old=source(before),current=source(after);
    // Trim matching edges before line sampling. Otherwise equal-size tail
    // samples can invent a deletion when a very long file only gains a line.
    if(old.count>lineLimit||current.count>lineLimit){
      let ap=0,bp=0,prefix=0,ae=old.text.length,be=current.text.length,suffix=0;
      const lineEnd=(text,pos)=>{const end=text.indexOf('\n',pos);return end<0?text.length:end+1;};
      const lineStart=(text,end)=>end<=1?0:text.lastIndexOf('\n',end-(text[end-1]==='\n'?2:1))+1;
      while(ap<ae&&bp<be){const ax=lineEnd(old.text,ap),bx=lineEnd(current.text,bp);if(old.text.slice(ap,ax)!==current.text.slice(bp,bx))break;ap=ax;bp=bx;prefix++;}
      while(ap<ae&&bp<be){const ax=lineStart(old.text,ae),bx=lineStart(current.text,be);if(old.text.slice(ax,ae)!==current.text.slice(bx,be))break;ae=ax;be=bx;suffix++;}
      const leading=Math.min(context,prefix),trailing=Math.min(context,suffix);
      for(let i=0;i<leading;i++){ap=lineStart(old.text,ap);bp=lineStart(current.text,bp);}
      for(let i=0;i<trailing;i++){ae=lineEnd(old.text,ae);be=lineEnd(current.text,be);}
      old.text=old.text.slice(ap,ae);current.text=current.text.slice(bp,be);
      old.offset=current.offset=prefix-leading;
      old.count-=prefix-leading+suffix-trailing;current.count-=prefix-leading+suffix-trailing;
    }
    const sampled=old.count>lineLimit||current.count>lineLimit;
    truncated=sampled;approximate=sampled;
    function sections(src){
      const head=[],tail=[],half=lineLimit/2;let pos=0,index=0,end;
      while(pos<src.text.length){
        end=src.text.indexOf('\n',pos);end=end<0?src.text.length:end+1;
        if(!sampled||index<half)head.push(src.text.slice(pos,end));
        else if(index>=Math.max(half,src.count-half))tail.push(src.text.slice(pos,end));
        pos=end;index++;
      }
      return sampled?[{lines:head,offset:src.offset},{lines:tail,offset:src.offset+src.count-tail.length}]:[{lines:head,offset:src.offset}];
    }
    const oldSections=sections(old),newSections=sections(current),hunks=[];
    function align(a,b){
      const operations=[];
      const append=(kind,ai,bi)=>operations.push({kind,ai,bi,raw:kind==='+'?b[bi]:a[ai]});
      function myers(a0,a1,b0,b1){
        const n=a1-a0,m=b1-b0,trace=[],v=new Map([[1,0]]);
        for(let d=0;d<=n+m&&work>0;d++){
          trace.push(new Map(v));
          for(let k=-d;k<=d;k+=2){
            if(--work<0)return null;
            let x=k===-d||(k!==d&&(v.get(k-1)??-1)<(v.get(k+1)??-1))?(v.get(k+1)||0):(v.get(k-1)||0)+1,y=x-k;
            while(x<n&&y<m&&a[a0+x]===b[b0+y]){if(--work<0)return null;x++;y++;}
            v.set(k,x);
            if(x>=n&&y>=m){
              const result=[];x=n;y=m;
              for(let step=d;step>=0;step--){
                const previous=trace[step],diagonal=x-y;
                const prior=diagonal===-step||(diagonal!==step&&(previous.get(diagonal-1)??-1)<(previous.get(diagonal+1)??-1))?diagonal+1:diagonal-1;
                const px=previous.get(prior)||0,py=px-prior;
                while(x>px&&y>py){x--;y--;result.push([' ',a0+x,b0+y]);}
                if(step){if(x===px){y--;result.push(['+',a0+x,b0+y]);}else{x--;result.push(['-',a0+x,b0+y]);}}
              }
              return result.reverse();
            }
          }
        }
        return null;
      }
      function compare(a0,a1,b0,b1,depth){
        while(a0<a1&&b0<b1&&a[a0]===b[b0])append(' ',a0++,b0++);
        let suffix=0;while(a0<a1-suffix&&b0<b1-suffix&&a[a1-suffix-1]===b[b1-suffix-1])suffix++;
        const ae=a1-suffix,be=b1-suffix;
        if(a0===ae){for(let j=b0;j<be;j++)append('+',a0,j);}
        else if(b0===be){for(let i=a0;i<ae;i++)append('-',i,b0);}
        else{
          let anchors=[];
          if(depth<32&&work>=ae-a0+be-b0){
            const uniqueA=new Map(),uniqueB=new Map();
            for(let i=a0;i<ae;i++){work--;uniqueA.set(a[i],uniqueA.has(a[i])?-1:i);}
            for(let j=b0;j<be;j++){work--;uniqueB.set(b[j],uniqueB.has(b[j])?-1:j);}
            const pairs=[];
            for(const [line,i] of uniqueA)if(i>=0&&(uniqueB.get(line)??-1)>=0)pairs.push([i,uniqueB.get(line)]);
            const tails=[],parents=[];
            for(let i=0;i<pairs.length;i++){
              let lo=0,hi=tails.length;
              while(lo<hi){const mid=(lo+hi)>>1;if(pairs[tails[mid]][1]<pairs[i][1])lo=mid+1;else hi=mid;}
              parents[i]=lo?tails[lo-1]:-1;tails[lo]=i;
            }
            for(let i=tails.at(-1);i!==undefined&&i>=0;i=parents[i])anchors.push(pairs[i]);
            anchors.reverse();
          }
          if(anchors.length){
            let ai=a0,bi=b0;
            for(const [i,j] of anchors){compare(ai,i,bi,j,depth+1);append(' ',i,j);ai=i+1;bi=j+1;}
            compare(ai,ae,bi,be,depth+1);
          }else{
            const exact=myers(a0,ae,b0,be);
            if(exact){for(const [kind,i,j] of exact)append(kind,i,j);}
            else{approximate=true;for(let i=a0;i<ae;i++)append('-',i,b0);for(let j=b0;j<be;j++)append('+',ae,j);}
          }
        }
        for(let i=0;i<suffix;i++)append(' ',ae+i,be+i);
      }
      compare(0,a.length,0,b.length,0);return operations;
    }
    for(let section=0;section<oldSections.length;section++){
      const a=oldSections[section],b=newSections[section],ops=align(a.lines,b.lines),ranges=[];
      for(let i=0;i<ops.length;){
        if(ops[i].kind===' '){i++;continue;}
        const start=Math.max(0,i-context);while(i<ops.length&&ops[i].kind!==' ')i++;
        const end=Math.min(ops.length,i+context),last=ranges.at(-1);
        if(last&&start<=last.end)last.end=end;else ranges.push({start,end});
      }
      for(const {start,end} of ranges){
        const lines=ops.slice(start,end).map(op=>({kind:op.kind,text:op.raw.endsWith('\n')?op.raw.slice(0,-1):op.raw,oldLine:a.offset+op.ai+1,newLine:b.offset+op.bi+1,noNewline:!op.raw.endsWith('\n')}));
        const oldCount=lines.filter(line=>line.kind!=='+').length,newCount=lines.filter(line=>line.kind!=='-').length;
        hunks.push({oldStart:lines[0].oldLine-(oldCount?0:1),oldCount,newStart:lines[0].newLine-(newCount?0:1),newCount,lines});
      }
    }
    let selected=hunks;
    const hunkLimit=Math.max(1,Math.floor((maxLines-2)/6));
    if(hunks.length>hunkLimit){
      const indexes=new Set([hunks.findIndex(h=>h.lines.some(line=>line.kind==='-')),hunks.findIndex(h=>h.lines.some(line=>line.kind==='+'))]);indexes.delete(-1);
      for(let i=0;indexes.size<hunkLimit&&i<hunks.length;i++)indexes.add(i);
      selected=[...indexes].sort((a,b)=>a-b).slice(0,hunkLimit).map(i=>hunks[i]);truncated=true;
    }
    let remaining=maxLines-2;
    selected=selected.map((hunk,index)=>{
      const allowance=Math.max(1,Math.floor(remaining/(selected.length-index))-1);let lines=hunk.lines;
      if(lines.length>allowance){
        truncated=true;
        const groups=['-','+',' '].map(kind=>lines.map((line,i)=>line.kind===kind?i:-1).filter(i=>i>=0)).filter(group=>group.length);
        const chosen=new Set(),cap=Math.max(groups.filter(group=>lines[group[0]].kind!==' ').length,Math.floor((allowance-1)/2));
        const quotas=groups.map(group=>Math.min(group.length,Math.floor(cap/groups.length)));
        let spare=cap-quotas.reduce((sum,n)=>sum+n,0);
        for(let i=0;spare>0;i=(i+1)%groups.length)if(quotas[i]<groups[i].length){quotas[i]++;spare--;}
        for(let i=0;i<groups.length;i++){const group=groups[i],quota=quotas[i],head=Math.ceil(quota/2);for(const n of group.slice(0,head))chosen.add(n);for(const n of group.slice(group.length-(quota-head)))if(quota>head)chosen.add(n);}
        const preview=[];let previous=-1;
        for(const i of [...chosen].sort((a,b)=>a-b)){if(i>previous+1)preview.push({kind:'omitted',count:i-previous-1});preview.push(lines[i]);previous=i;}
        if(previous<lines.length-1)preview.push({kind:'omitted',count:lines.length-previous-1});lines=preview;
      }
      lines=lines.map(line=>{if(line.kind!=='omitted'&&line.text.length>textLimit){truncated=true;return {...line,text:line.text.slice(0,textLimit)+' …'};}return line;});
      remaining-=lines.length+1;return {...hunk,lines};
    });
    return {hunks:selected,truncated,approximate,identical:String(before??'')===String(after??'')};
  }
  const core={
    fileDiff,
    chatIconNames(){return chatIcons.slice();},
    chatIconUrl(name){const i=chatIcons.indexOf(name);return i<0 ? null : '/chat-icons/'+String(i+1).padStart(2,'0')+'-'+name+'.png';},
    chatIconForTag(tag){return chatIconTags[normalizeIconTag(tag)] || null;},
    parent(path){return path.includes('/')?path.slice(0,path.lastIndexOf('/')):'';},
    basename(path){return path.slice(path.lastIndexOf('/')+1);},
    join(parent,name){return parent?parent+'/'+name:name;},
    size(bytes){if(bytes<1024)return bytes+' B';if(bytes<1048576)return (bytes/1024).toFixed(1)+' KB';return (bytes/1048576).toFixed(1)+' MB';},
    draftKey(workspaceKey,threadId){return JSON.stringify([workspaceKey||'',threadId||'new']);},
    appendDelta(messages,id,delta){let entry=messages.find(m=>m.id===id);if(!entry){entry={id,role:'assistant',text:''};messages.push(entry);}entry.text+=delta;return entry;},
    isLoggedIn(account){return !!(account&&account.type);},
    safeImageUrl(url){return typeof url==='string' && /^\/images\/[0-9a-f]{64}$/.test(url);},
    localImagePath(path){return typeof path==='string' && !!path.trim() && !/[\u0000-\u001f]/.test(path) && !path.startsWith('//') && (!/^[a-z][a-z\d+.-]*:/i.test(path)||/^(sandbox:|file:\/\/\/)/.test(path));},
    groupImageMessages(messages){
      const result=[],groups=new Map();
      for(const message of messages){
        if(message.kind!=='image'||!message.imageGroup){result.push(message);continue;}
        let group=groups.get(message.imageGroup);
        if(!group){group={...message,images:[],imageError:'',imageStatus:'completed'};groups.set(message.imageGroup,group);result.push(group);}
        group.images.push(...message.images||[]);
        if(message.imageStatus==='generating')group.imageStatus='generating';
        if(message.imageError)group.imageError+=(group.imageError?'\n':'')+message.imageError;
      }
      return result;
    },
    safeLoginUrl(url){try{const u=new URL(url);return u.protocol==='https:'&&['auth.openai.com','chatgpt.com'].includes(u.hostname)&&!u.username&&!u.password;}catch{return false;}}
  };
  if(typeof module!=='undefined'&&module.exports)module.exports=core;else root.UiCore=core;
})(typeof window==='undefined'?globalThis:window);
