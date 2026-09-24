/* Matches a saved reply to one observed user turn. No authentication data enters this module. */
(function (root) {
  'use strict';
  function textOf(message) {
    const parts = message?.content?.parts;
    return Array.isArray(parts) ? parts.map(part => typeof part === 'string' ? part :
      part && typeof part.text === 'string' ? part.text : '').filter(Boolean).join('\n') : '';
  }
  function match(data, expected, sentAt, observedUserId) {
    const mapping = data?.mapping || {};
    const entries = Object.entries(mapping);
    const isUser = node => node?.message?.author?.role === 'user';
    let userId = '';
    if (observedUserId) {
      const node = mapping[observedUserId] || entries.find(([,value]) => value?.message?.id === observedUserId)?.[1];
      if (!isUser(node) || !node.message.content?.parts?.includes(expected)) return {kind:'waiting'};
      userId = entries.find(([,value]) => value === node)?.[0] || '';
    } else {
      const users = entries.filter(([,node]) => isUser(node) && node.message.content?.parts?.includes(expected)
        && (!sentAt || Number(node.message.create_time) >= sentAt - 3));
      if (users.length > 1) return {kind:'ambiguous',reason:'같은 내용의 사용자 메시지가 여러 개입니다.'};
      if (!users.length) return {kind:'waiting'};
      userId = users[0][0];
    }
    const onPath = (id, ancestor) => {
      const seen = new Set();
      while (id && !seen.has(id)) { if (id === ancestor) return true; seen.add(id); id = mapping[id]?.parent; }
      return false;
    };
    const current = typeof data?.current_node === 'string' && mapping[data.current_node] ? data.current_node : '';
    const candidates = entries.filter(([id,node]) => {
      const m = node?.message;
      if (m?.author?.role !== 'assistant' || m.status !== 'finished_successfully' ||
          (m.recipient && m.recipient !== 'all') ||
          (m.content?.content_type && m.content.content_type !== 'text') || !textOf(m)) return false;
      if (!onPath(id, userId) || (current && !onPath(current, id))) return false;
      let parent = node.parent; const seen = new Set();
      while (parent && parent !== userId && !seen.has(parent)) {
        seen.add(parent);
        if (isUser(mapping[parent])) return false;
        parent = mapping[parent]?.parent;
      }
      return parent === userId;
    });
    if (!candidates.length) return {kind:'waiting'};
    let chosen;
    if (current) {
      const leaves = candidates.filter(([id]) => !candidates.some(([other]) => other !== id && onPath(other, id)));
      if (leaves.length !== 1) return {kind:'ambiguous',reason:'현재 대화 분기의 최종 답변을 특정하지 못했습니다.'};
      chosen = leaves[0];
    } else if (candidates.length === 1) chosen = candidates[0];
    else return {kind:'ambiguous',reason:'대화 분기를 특정하지 못했습니다.'};
    const [assistantId,node] = chosen, message = node.message;
    const text = textOf(message), metadata = message.metadata || {};
    return {kind:'ok',userMessageId:userId,assistantMessageId:assistantId,
      reply:text.slice(0,200000),truncated:text.length>200000,
      model:metadata.resolved_model_slug || metadata.model_slug || ''};
  }
  root.MCChatRequery = {match};
  if (typeof module !== 'undefined' && module.exports) module.exports = root.MCChatRequery;
})(typeof window === 'undefined' ? globalThis : window);
