'use strict';

/** 独立草图的固定样例；不访问账号、接口、云音频或生产学习缓存。 */
const words = [
  { id: 'cat', written: '猫', kana: 'ねこ', gloss: '猫；猫科动物', senses: ['猫', '猫科动物', '家养的小型猫科哺乳动物'], example: '猫が窓のそばにいる。', translation: '猫在窗边。', status: 'due', focus: true },
  { id: 'travel', written: '旅行', kana: 'りょこう', gloss: '旅行；游览', senses: ['旅行', '出门游览', '行旅'], example: '来月、旅行に行きます。', translation: '下个月去旅行。', status: 'due', focus: false },
  { id: 'promise', written: '約束', kana: 'やくそく', gloss: '约定；承诺', senses: ['约定', '承诺', '双方商定的事情'], example: '友達と約束をした。', translation: '和朋友做了约定。', status: 'new', focus: false },
  { id: 'listen', written: '聞く', kana: 'きく', gloss: '听；询问', senses: ['听', '询问', '听从'], example: '音楽を聞きます。', translation: '听音乐。', status: 'learning', focus: true },
];

/** 题型注册表将内容、作答方式和资源需求分开，演示后续扩展的入口。 */
const types = [
  { id: 'LISTEN_RECALL', name: '听音回忆', group: '回忆与自测', kind: 'recall', audio: true, note: '听发音 → 回忆单词和意思 → 翻面自评。第一版题型。' },
  { id: 'WORD_MEANING', name: '看单词选释义', group: '选择题', kind: 'choice', cue: 'written', target: 'gloss', note: '看到日语单词，从四个选项中找出意思。' },
  { id: 'MEANING_WORD', name: '看释义选单词', group: '选择题', kind: 'choice', cue: 'gloss', target: 'written', note: '看到中文释义，找出对应的日语单词。' },
  { id: 'KANA_MEANING', name: '看假名选释义', group: '选择题', kind: 'choice', cue: 'kana', target: 'gloss', note: '只显示假名，练习读音与含义的对应。' },
  { id: 'MEANING_KANA', name: '看释义选假名', group: '选择题', kind: 'choice', cue: 'gloss', target: 'kana', note: '看到意思，从假名选项中找到读音。' },
  { id: 'WORD_KANA', name: '看单词选假名', group: '选择题', kind: 'choice', cue: 'written', target: 'kana', note: '看到汉字词，选择正确读音。纯假名词不适合这一题型。' },
  { id: 'WRONG_MEANING', name: '看单词选错义', group: '选择题', kind: 'choice', cue: 'written', wrongMeaning: true, note: '找出不属于该词的意思，题面明确提醒“选错义”。' },
  { id: 'AUDIO_WORD', name: '听发音选单词', group: '听音选择', kind: 'choice', audio: true, target: 'written', note: '先听发音，再从四个日语单词中选择。' },
  { id: 'AUDIO_MEANING', name: '听发音选释义', group: '听音选择', kind: 'choice', audio: true, target: 'gloss', note: '先听发音，再选择对应含义。' },
  { id: 'MEANING_SPELL_WORD', name: '看释义拼单词', group: '拼写题', kind: 'spelling', cue: 'gloss', target: 'written', note: '看到中文意思，使用日语输入法输入单词。' },
  { id: 'WORD_SPELL_KANA', name: '看单词拼假名', group: '拼写题', kind: 'spelling', cue: 'written', target: 'kana', note: '看到单词，输入读音；演示接受对应的平假名和片假名。' },
  { id: 'SENTENCE_SPELL', name: '拼写例句', group: '拼写题', kind: 'spelling', cue: 'translation', target: 'example', note: '本草图用中文例句提示，输入日文整句。提示方式是本项目提案。' },
  { id: 'QUICK_RECALL', name: '快速自测', group: '回忆与自测', kind: 'recall', cue: 'written', quick: true, note: '紧凑词条、遮挡释义、逐词翻面自评；演示显示单词的模式。' },
];

const $ = id => document.getElementById(id);
const selected = new Set(['LISTEN_RECALL']);
let session = null;
let answerRevealed = false;
let objectiveCorrect = null;

/** 所有文字都经 textContent 写入；固定样例也不拼接可执行 HTML。 */
function node(tag, text = '', className = '') {
  const element = document.createElement(tag);
  element.textContent = text;
  element.className = className;
  return element;
}

/** 页面切换不重绘训练卡片，保留当前输入、翻面和判定状态。 */
function page(name) {
  if (name === 'train' && !session) start('today', ['LISTEN_RECALL']);
  for (const id of ['home', 'list', 'types', 'train']) $(id).classList.toggle('hidden', id !== name);
  document.querySelectorAll('[data-page]').forEach(button => button.classList.toggle('active', button.dataset.page === name));
  if (name === 'home') {
    $('resume').classList.toggle('hidden', !session || session.done);
    $('resume-text').textContent = session ? `还有 ${session.ids.length - session.completed.size} 个词，继续当前题型组合。` : '';
    $('today-count').textContent = session ? session.completed.size : 0;
  }
  window.scrollTo({ top: 0 });
}

/** 列表状态是示例，不将已学与永久掌握混为一谈。 */
function list(filter = 'all') {
  $('word-list').replaceChildren();
  for (const word of words.filter(w => filter === 'all' || (filter === 'focus' && w.focus) || w.status === filter)) {
    const row = node('article', '', 'word-row');
    const body = node('div');
    const title = node('strong', word.written);
    title.append(node('span', word.kana, 'kana'));
    body.append(title, node('p', word.gloss, 'muted'), node('span', ({ new: '未学习', due: '待复习', learning: '学习中' })[word.status] + (word.focus ? ' · 耳词' : ''), 'chip ' + (word.status === 'new' ? 'new' : '')));
    row.append(body);
    $('word-list').append(row);
  }
  document.querySelectorAll('[data-filter]').forEach(button => button.classList.toggle('active', button.dataset.filter === filter));
}

/** 可同时勾选题型，单独体验按钮直接启动一种；活动批次的题型快照不会改变。 */
function catalog() {
  for (const group of ['选择题', '听音选择', '拼写题', '回忆与自测']) {
    $('type-catalog').append(node('h2', group));
    const grid = node('div', '', 'type-grid');
    for (const type of types.filter(t => t.group === group)) {
      const card = node('article', '', 'type-card');
      const label = node('label');
      const input = document.createElement('input');
      input.type = 'checkbox';
      input.value = type.id;
      input.checked = selected.has(type.id);
      input.addEventListener('change', () => { input.checked ? selected.add(type.id) : selected.delete(type.id); selectionSummary(); });
      label.append(input, node('span', type.name));
      const button = node('button', '单独体验', 'outline');
      button.setAttribute('aria-label', '体验' + type.name);
      button.addEventListener('click', () => start('today', [type.id]));
      card.append(label, node('p', type.note, 'muted'), button);
      grid.append(card);
    }
    $('type-catalog').append(grid);
  }
  selectionSummary();
}

function selectionSummary() {
  $('selection-summary').textContent = '勾选顺序：' + ([...selected].map(id => types.find(t => t.id === id).name).join(' → ') || '请至少选择一种');
  $('selection-error').textContent = '';
}

/** 按最多十词切组，到期词先于新词；常规入口保持第一版听音回忆。 */
function start(mode, typeIds) {
  const ids = words.filter(w => w.status === 'due' || w.status === 'new' || mode === 'extra').map(w => w.id);
  const groups = [];
  for (let offset = 0; offset < ids.length; offset += 10) groups.push(ids.slice(offset, offset + 10));
  session = { ids, groups, groupIndex: 0, typeIndex: 0, types: typeIds.map(id => types.find(t => t.id === id)), queue: [...groups[0]], trials: new Map(ids.map(id => [id, []])), completed: new Map(), done: false, feedback: '' };
  render();
  page('train');
}

/** 固定演示池的选项唯一；正式出题必须处理多义、同音和多个合法答案。 */
function optionsFor(type, word) {
  let options;
  if (type.wrongMeaning) {
    const unrelated = words.find(w => w.id !== word.id).senses[0];
    options = [...word.senses.map(text => ({ text, correct: false })), { text: unrelated, correct: true }];
  } else {
    options = words.map(w => ({ text: w[type.target], correct: w.id === word.id }));
  }
  // 每次作答重新打乱，避免正确选项的位置成为提示。
  for (let i = options.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [options[i], options[j]] = [options[j], options[i]];
  }
  return options;
}

/** 首尾空白及全半角归一化；仅假名读音题转换片假名，整句只允许省略句末句号。 */
function normalize(value, target) {
  let text = value.normalize('NFKC').trim();
  if (target === 'kana') text = text.replace(/[ァ-ヶ]/g, char => String.fromCharCode(char.charCodeAt(0) - 0x60));
  if (target === 'example') text = text.replace(/。$/, '');
  return text;
}

/** 每词显示当前题型；切题时清除上题答案，听音题必须先完成模拟播放步骤。 */
function render() {
  answerRevealed = false;
  objectiveCorrect = null;
  $('question').replaceChildren();
  $('answer').replaceChildren();
  for (const id of ['answer', 'grade', 'objective-grade']) $(id).classList.add('hidden');
  $('question').classList.toggle('hidden', session.done);
  $('finish').classList.toggle('hidden', !session.done);
  $('test-feedback').textContent = session.feedback;
  $('test-progress').textContent = session.done ? '完成' : `${session.completed.size}/${session.ids.length} 词完成`;
  $('test-bar').style.width = (session.completed.size / session.ids.length * 100) + '%';
  $('type-route').textContent = session.types.map(t => t.name).join(' → ');
  if (session.done) {
    $('type-progress').textContent = '本次示例训练完成';
    $('completion-results').replaceChildren();
    for (const [id, rating] of session.completed) {
      const row = node('div', '', 'result-row');
      row.append(node('strong', words.find(w => w.id === id).written), node('span', { AGAIN: '× Again', HARD: '△ 困难', GOOD: '○ 记得' }[rating]));
      $('completion-results').append(row);
    }
    return;
  }
  const type = session.types[session.typeIndex];
  const word = words.find(w => w.id === session.queue[0]);
  $('type-progress').textContent = `${type.name} · 第 ${session.groupIndex + 1} 组 · 题型 ${session.typeIndex + 1}/${session.types.length} · 本题型剩余 ${session.queue.length} 词`;
  $('question').append(node('span', type.wrongMeaning ? '请选择不属于这个词的意思' : type.name, 'muted'));
  if (type.cue) {
    if (type.quick) {
      const row = node('div', '', 'quick-row');
      row.append(node('strong', word[type.cue], 'stem'), node('span', '释义已遮挡', 'muted'));
      $('question').append(row);
    } else $('question').append(node('p', word[type.cue], 'stem'));
  }
  const gated = [];
  if (type.kind === 'recall') {
    const reveal = node('button', '显示答案');
    reveal.id = 'reveal';
    reveal.addEventListener('click', revealAnswer);
    gated.push(reveal);
  } else if (type.kind === 'choice') {
    const choices = node('div', '', 'choices');
    for (const option of optionsFor(type, word)) {
      const button = node('button', option.text);
      button.addEventListener('click', () => {
        if (answerRevealed) return;
        for (const candidate of choices.children) candidate.disabled = true;
        if (!option.correct) button.classList.add('wrong');
        for (const candidate of choices.children) if (candidate._correct) candidate.classList.add('correct');
        objectiveCorrect = option.correct;
        revealAnswer();
      });
      button._correct = option.correct;
      choices.append(button);
      gated.push(button);
    }
    $('question').append(choices);
  } else {
    const form = node('form', '', 'spelling');
    const input = document.createElement('input');
    input.id = 'spelling-input';
    input.autocomplete = 'off';
    input.spellcheck = false;
    const label = node('label', type.target === 'kana' ? '请输入假名读音' : type.target === 'example' ? '请输入日文例句' : '请输入日语单词');
    label.htmlFor = input.id;
    const submit = node('button', '检查答案');
    submit.type = 'submit';
    let composing = false;
    input.addEventListener('compositionstart', () => { composing = true; });
    input.addEventListener('compositionend', () => { composing = false; });
    input.addEventListener('keydown', event => { if (event.key === 'Enter' && (event.isComposing || composing || event.keyCode === 229)) event.preventDefault(); });
    form.addEventListener('submit', event => {
      event.preventDefault();
      if (answerRevealed || composing) return;
      if (!input.value.trim()) { $('test-feedback').textContent = '请先输入答案，空白不记作一次答题。'; return; }
      objectiveCorrect = normalize(input.value, type.target) === normalize(word[type.target], type.target);
      input.disabled = true;
      submit.disabled = true;
      revealAnswer();
    });
    form.append(label, input, submit);
    $('question').append(form, node('p', type.target === 'kana' ? '可输入平假名或对应片假名；拼错会自动排到队尾。' : '使用日语输入法；确认候选文字后再检查答案。', 'objective-note'));
    gated.push(input, submit);
  }
  if (type.audio) {
    const listen = node('button', '♪', 'audio');
    listen.id = 'listen';
    listen.setAttribute('aria-label', '模拟听音步骤');
    for (const control of gated) control.disabled = true;
    listen.addEventListener('click', () => {
      if (answerRevealed) return;
      for (const control of gated) control.disabled = false;
      listen.textContent = '↻';
      $('test-feedback').textContent = '听音步骤已演示，可以继续作答。此草图没有实际音频。';
    });
    $('question').insertBefore(listen, $('question').children[1] || null);
    listen.after(node('p', '模拟听音步骤，未连接实际发音', 'muted'));
  }
  if (type.kind === 'recall') $('question').append(gated[0]);
}

/** 翻面自评或客观判题后显示词条；错误不能通过“困难”或“记得”按钮直接通过。 */
function revealAnswer() {
  if (answerRevealed || session.done) return;
  answerRevealed = true;
  const type = session.types[session.typeIndex];
  const word = words.find(w => w.id === session.queue[0]);
  if (type.kind === 'recall') $('question').classList.add('hidden');
  $('answer').classList.remove('hidden');
  const example = node('div', '', 'example');
  example.append(node('p', word.example), node('p', word.translation, 'muted'));
  $('answer').append(node('h2', word.written), node('p', word.kana, 'muted'), node('p', word.gloss, 'gloss'), example);
  if (type.kind === 'recall') {
    $('grade').classList.remove('hidden');
    $('test-feedback').textContent = '按回忆自评；忘记会自动排到本题型队尾。';
  } else {
    $('objective-grade').classList.remove('hidden');
    $('objective-hard').classList.toggle('hidden', !objectiveCorrect);
    $('objective-next').textContent = objectiveCorrect ? '答对，继续' : '继续 · 自动队尾重试';
    $('test-feedback').textContent = objectiveCorrect ? '答对了。可以选择“答对但困难”。' : '答错了，本次按 Again 记录，确认后自动加入队尾。';
  }
}

/** 完成整组当前题型才换下一题型；最后一种题型每词通过即完成，不等整组提交。 */
function rate(rating) {
  if (!answerRevealed || session.done) return;
  const id = session.queue.shift();
  session.trials.get(id).push({ type: session.types[session.typeIndex].id, rating });
  if (rating === 'AGAIN') {
    session.queue.push(id);
    session.feedback = '这个词已自动加入当前题型剩余队尾，稍后再练。';
  } else {
    if (session.typeIndex === session.types.length - 1) {
      const ratings = session.trials.get(id).map(t => t.rating);
      session.completed.set(id, ratings.includes('AGAIN') ? 'AGAIN' : ratings.includes('HARD') ? 'HARD' : 'GOOD');
    }
    session.feedback = '当前题型已通过。' + (session.typeIndex < session.types.length - 1 ? '其他题型尚未完成，暂不提交这个词。' : '这个词的所有题型已完成。');
  }
  if (!session.queue.length) {
    session.typeIndex++;
    if (session.typeIndex === session.types.length) { session.typeIndex = 0; session.groupIndex++; }
    if (session.groupIndex === session.groups.length) session.done = true;
    else session.queue = [...session.groups[session.groupIndex]];
  }
  render();
}

document.querySelectorAll('[data-page]').forEach(button => button.addEventListener('click', () => page(button.dataset.page)));
document.querySelectorAll('[data-start]').forEach(button => button.addEventListener('click', () => start(button.dataset.start, ['LISTEN_RECALL'])));
document.querySelectorAll('[data-filter]').forEach(button => button.addEventListener('click', () => list(button.dataset.filter)));
document.querySelectorAll('[data-rating]').forEach(button => button.addEventListener('click', () => rate(button.dataset.rating)));
$('objective-next').addEventListener('click', () => rate(objectiveCorrect ? 'GOOD' : 'AGAIN'));
$('objective-hard').addEventListener('click', () => { if (objectiveCorrect) rate('HARD'); });
$('common-types').addEventListener('click', () => {
  selected.clear();
  for (const id of ['WORD_MEANING', 'WORD_SPELL_KANA', 'LISTEN_RECALL']) selected.add(id);
  document.querySelectorAll('#type-catalog input').forEach(input => { input.checked = selected.has(input.value); });
  selectionSummary();
});
$('start-types').addEventListener('click', () => {
  const ids = [...selected];
  if (!ids.length) { $('selection-error').textContent = '请至少选择一种题型。'; return; }
  start('today', ids);
});
list();
catalog();
