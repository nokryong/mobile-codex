(function(root){
  'use strict';
  const chatIcons = ['idle','acknowledged','thinking','working','question','done','blocked','smug','greeting','inspecting','explaining','discovery','caution','sorry','happy','skeptical','eohu','uhehe','insult','punch','uncertain','disagree','not-allowed','file-request','reviewed','source','fixed','lol','good-grief','wink','heart','sleep'];
  const core={
    chatIconUrl(name){const i=chatIcons.indexOf(name);return i<0 ? null : '/chat-icons/'+String(i+1).padStart(2,'0')+'-'+name+'.png';},
    messageIcon(message){
      if(message.imageError || message.imageStatus==='failed')return 'blocked';
      if(message.imageStatus==='generating')return 'working';
      const text=(message.text||'').replace(/```[\s\S]*?```/g,'').trim();
      if(!text)return message.images?.length?'done':'idle';
      if(/^(어휴|에휴|으휴)/.test(text))return 'eohu';
      if(/^(으헤헤|우헤헤|으흐흐)/.test(text))return 'uhehe';
      if(/^ㅗ(?:\s|$)/.test(text))return 'insult';
      if(/^(주먹|펀치|한 대 맞)/.test(text))return 'punch';
      if(/^(ㄹㅇㅋㅋ|ㅋㅋ|lol\b)/i.test(text))return 'lol';
      if(/^정말이지/.test(text))return 'good-grief';
      if(/^(윙크|wink\b)/i.test(text))return 'wink';
      if(/^(하트|사랑해|heart\b)/i.test(text))return 'heart';
      if(/^(잘\s?자|굿나잇|good\s?night\b)/i.test(text))return 'sleep';
      if(/죄송|미안|sorry/i.test(text))return 'sorry';
      if(/주의|경고|조심|위험|caution|warning/i.test(text))return 'caution';
      if(/그건 안\s?돼|허용되지|권한이 없|not allowed/i.test(text))return 'not-allowed';
      if(/실패|막혔|불가능|오류가 발생|failed|blocked/i.test(text))return 'blocked';
      if(/애매|확실하지|불확실|uncertain/i.test(text))return 'uncertain';
      if(/아닌데|동의하기 어렵|그렇지 않|disagree/i.test(text))return 'disagree';
      if(/의심|진짜\s?\?|skeptical/i.test(text))return 'skeptical';
      if(/(파일|자료|이미지).{0,30}(첨부|보내|올려|주세|줘)|please.{0,20}(attach|upload)/i.test(text))return 'file-request';
      if(/검토(를)?\s?(완료|마쳤|끝냈)|review complete/i.test(text))return 'reviewed';
      if(/수정(을)?\s?(완료|마쳤|끝냈)|수정했|고쳤|fixed/i.test(text))return 'fixed';
      if(/출처|sources?:/i.test(text))return 'source';
      if(/찾았|발견했|원인을 찾|found the|discovered/i.test(text))return 'discovery';
      if(/완료했|완료됐|해결했|해결됐|성공했|completed|fixed/i.test(text))return 'done';
      if(/계획대로|예상대로|as planned/i.test(text))return 'smug';
      if(/좋아요|좋았|기뻐|great|happy/i.test(text))return 'happy';
      if(/[?？]/.test(text))return 'question';
      if(/^(안녕|반가|hello|hi\b)/i.test(text))return 'greeting';
      if(/^(알겠|확인했|접수|네[, .]|understood|got it)/i.test(text))return 'acknowledged';
      if(/살펴보|확인해 볼|검토하|inspect|review/i.test(text))return 'inspecting';
      if(/생각해|고민|추론|thinking/i.test(text))return 'thinking';
      if(/진행 중|작업 중|구현하겠|수정하겠|working/i.test(text))return 'working';
      return 'explaining';
    },
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
