'use strict';

/* Sonviaf — 作品档案台 P0
   Work identity, original playback, four labelled actions, honest states.
   Upload creates a Work draft; separation only starts when the user asks. */

const DEMO_WORKS = [
  { id: 'SVF-0001', title: 'Neural Poison (3)', file: 'Neural Poison (3).wav', dur: 192, durText: '03:12' },
  { id: 'SVF-0002', title: '我曾遥望银河星光', file: '我曾遥望银河星光.wav', dur: 168, durText: '02:48' },
  { id: 'SVF-0003', title: 'Midnight Drive', file: 'Midnight Drive - take 2.mp3', dur: 221, durText: '03:41' },
  { id: 'SVF-0004', title: '白色噪音', file: '白色噪音.flac', dur: 245, durText: '04:05' },
].map((work) => ({ ...work, rel: null, srcUrl: null, real: false, playable: false, separation: 'idle' }));

const state = {
  works: [],
  workId: null,
  query: '',
  peaks: new Map(),
  loadingPeaks: new Set(),
  errorNoted: false,
  view: 'wave',
  midiNotes: null,
  midiLoading: false,
  midiWidth: 0,
};

const midiPlayer = {
  ctx: null,
  nodes: [],
  pos: 0,
  playing: false,
  startPos: 0,
  startedAt: 0,
  timer: 0,
  ensure() {
    if (!this.ctx) {
      const Ctx = window.AudioContext || window.webkitAudioContext;
      if (!Ctx) return null;
      this.ctx = new Ctx();
    }
    if (this.ctx.state === 'suspended') this.ctx.resume();
    return this.ctx;
  },
  duration() {
    return state.midiNotes && state.midiNotes.notes ? state.midiNotes.duration || 0 : 0;
  },
  stopNodes() {
    this.nodes.forEach((node) => {
      try { node.stop(); } catch (error) { /* already stopped */ }
    });
    this.nodes = [];
  },
  pause() {
    if (!this.playing) return;
    const ctx = this.ctx;
    if (ctx) this.pos = Math.min(this.duration(), this.startPos + (ctx.currentTime - this.startedAt));
    this.stopNodes();
    this.playing = false;
    window.clearInterval(this.timer);
    updatePlayButton(false);
  },
  play() {
    const notes = state.midiNotes && state.midiNotes.notes;
    const duration = this.duration();
    if (!notes || !notes.length || !duration) return;
    const ctx = this.ensure();
    if (!ctx) return;
    if (this.pos >= duration - 0.05) this.pos = 0;
    this.stopNodes();
    const base = ctx.currentTime + 0.06;
    const offset = this.pos;
    notes.forEach((note) => {
      const start = note[0];
      const end = note[0] + note[1];
      if (end <= offset) return;
      const t0 = base + Math.max(0, start - offset);
      const dur = end - Math.max(start, offset);
      if (dur <= 0.01) return;
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'triangle';
      osc.frequency.value = 440 * 2 ** ((note[2] - 69) / 12);
      gain.gain.setValueAtTime(0.0001, t0);
      gain.gain.exponentialRampToValueAtTime(Math.min(0.16, 0.025 + (note[3] / 127) * 0.1), t0 + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
      osc.connect(gain).connect(ctx.destination);
      osc.start(t0);
      osc.stop(t0 + dur + 0.03);
      this.nodes.push(osc);
    });
    this.playing = true;
    this.startPos = offset;
    this.startedAt = ctx.currentTime;
    updatePlayButton(true);
    window.clearInterval(this.timer);
    this.timer = window.setInterval(() => this.tick(), 100);
    this.tick();
  },
  tick() {
    if (!this.playing) return;
    const ctx = this.ctx;
    const duration = this.duration();
    const pos = Math.min(duration, this.startPos + (ctx.currentTime - this.startedAt));
    this.pos = pos;
    if (pos >= duration - 0.02) {
      this.playing = false;
      this.pos = 0;
      this.stopNodes();
      window.clearInterval(this.timer);
      updatePlayButton(false);
      setProgress(0, duration);
      return;
    }
    const ratio = duration ? pos / duration : 0;
    setProgress(ratio, duration);
    followPlayback(ratio);
  },
  seek(ratio) {
    const duration = this.duration();
    if (!duration) return;
    const next = Math.max(0, Math.min(1, ratio)) * duration;
    const wasPlaying = this.playing;
    if (wasPlaying) this.pause();
    this.pos = next;
    setProgress(next / duration, duration);
    if (wasPlaying) this.play();
  },
};

function originalLabel() {
  if (state.view === 'wave') return '原作';
  if (state.view === 'midi') return '原始 MIDI';
  return '原始曲谱';
}

function followPlayback(ratio) {
  if (state.view === 'midi') {
    const pane = DOM.midiView;
    if (!state.midiWidth) return;
    const x = ratio * state.midiWidth;
    const left = pane.scrollLeft;
    const width = pane.clientWidth;
    if (x < left + 60 || x > left + width - 120) {
      pane.scrollLeft = Math.max(0, x - width * 0.35);
    }
  } else if (state.view === 'score') {
    const W = state.scoreWidth || 0;
    if (!W) return;
    const x = 96 + ratio * (W - 136);
    const cursor = document.getElementById('scoreCursor');
    if (cursor) {
      cursor.setAttribute('x1', String(x));
      cursor.setAttribute('x2', String(x));
    }
    const ind = document.getElementById('scorePageInd');
    if (ind) ind.textContent = fmtTime(ratio * (state.midiNotes ? state.midiNotes.duration : 0));
    const pane = document.getElementById('scoreScroll');
    if (pane && midiPlayer.playing) {
      const left = Math.max(0, x - pane.clientWidth * 0.35);
      if (Math.abs(pane.scrollLeft - left) > 60) pane.scrollLeft = left;
    }
  }
}

function viewDuration() {
  const work = currentWork();
  if (!work) return 0;
  if (state.view === 'wave') return Number.isFinite(DOM.audio.duration) && DOM.audio.duration > 0 ? DOM.audio.duration : work.dur || 0;
  return midiPlayer.duration();
}

const $ = (sel, root = document) => root.querySelector(sel);

const DOM = {
  workList: $('#workList'),
  search: $('#search'),
  workTitle: $('#workTitle'),
  moreBtn: $('#moreBtn'),
  moreMenu: $('#moreMenu'),
  libToggle: $('#libToggle'),
  viewTabs: $('#viewTabs'),
  midiView: $('#midiView'),
  scoreView: $('#scoreView'),
  dockTools: $('#dockTools'),
  chainList: $('#chainList'),
  chainFlag: $('#chainFlag'),
  ab: $('#ab'),
  abOriginal: $('#ab [data-ab="original"]'),
  abProcessed: $('#ab [data-ab="processed"]'),
  procPanel: $('#procPanel'),
  procName: $('#procName'),
  procIcon: $('#procIcon'),
  procParams: $('#procParams'),
  procBypass: $('#procBypass'),
  procApply: $('#procApply'),
  procRemove: $('#procRemove'),
  procVersions: $('#procVersions'),
  procClose: $('#procClose'),
  waveArea: $('#waveArea'),
  playBtn: $('#playBtn'),
  timeNow: $('#timeNow'),
  timeAll: $('#timeAll'),
  timeline: $('#timeline'),
  progress: $('#progress'),
  separateBtn: $('#separateBtn'),
  backingBtn: $('#backingBtn'),
  singBtn: $('#singBtn'),
  exportBtn: $('#exportBtn'),
  statusLine: $('#statusLine'),
  details: $('#details'),
  detailsList: $('#detailsList'),
  sepModal: $('#sepModal'),
  singModal: $('#singModal'),
  toasts: $('#toasts'),
  file: $('#file'),
  audio: $('#audio'),
};

/* ---------- helpers ---------- */
const esc = (value) =>
  String(value).replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));

const currentWork = () => state.works.find((w) => w.id === state.workId) || null;
const peakKey = (work) => (work ? work.rel || work.srcUrl || null : null);
const audioSrc = (work) => (work && (work.rel ? `/media?rel=${encodeURIComponent(work.rel)}` : work.srcUrl || '')) || '';

function downsample(values, target) {
  if (values.length <= target) return values;
  const step = values.length / target;
  const out = [];
  for (let i = 0; i < target; i += 1) {
    let peak = 0;
    const start = Math.floor(i * step);
    const end = Math.max(Math.floor((i + 1) * step), start + 1);
    for (let j = start; j < end; j += 1) peak = Math.max(peak, values[j] || 0);
    out.push(peak);
  }
  return out;
}

function fmtTime(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) return '00:00';
  const total = Math.round(seconds);
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
}

function toast(message) {
  const node = document.createElement('div');
  node.className = 'toast';
  node.innerHTML = message;
  DOM.toasts.appendChild(node);
  while (DOM.toasts.children.length > 3) DOM.toasts.firstElementChild.remove();
  window.setTimeout(() => node.remove(), 3600);
}

function closeMenu() {
  DOM.moreMenu.hidden = true;
}

function openModal(modal) {
  modal.hidden = false;
}

function closeModal(modal) {
  modal.hidden = true;
}

/* ---------- routing ---------- */
function readHash() {
  try {
    const raw = decodeURIComponent(location.hash.replace(/^#\/?/, ''));
    return raw ? raw.split('/')[0] : null;
  } catch (error) {
    return null;
  }
}

function syncHash() {
  const next = state.workId ? `#${state.workId}` : '';
  if (location.hash !== next) history.replaceState(null, '', next || location.pathname);
}

/* ---------- boot ---------- */
async function boot() {
  try {
    const response = await fetch('/api/library', { cache: 'no-store' });
    if (!response.ok) throw new Error(`status ${response.status}`);
    const data = await response.json();
    state.works = (data.works || []).map((item) => ({
      id: item.id,
      title: item.name,
      file: item.file,
      rel: item.rel,
      srcUrl: null,
      dur: item.dur,
      durText: item.dur ? fmtTime(item.dur) : '—',
      sr: item.sr,
      bits: item.bits,
      ch: item.ch,
      size: item.size,
      playable: !!item.playable,
      real: true,
      separation: 'idle',
      chains: { wave: [], midi: [], score: [] },
      versions: { wave: 'original', midi: 'original', score: 'original' },
    }));
    if (!state.works.length) throw new Error('empty library');
  } catch (error) {
    state.works = DEMO_WORKS.map((work) => ({ ...work }));
    toast('未连接到本地曲库，当前展示示例作品。');
  }

  const parsed = readHash();
  const candidate = state.works.find((w) => w.id === parsed);
  const initial = state.works.find((w) => w.playable && (w.dur || 0) >= 45) || state.works[0];
  if (candidate || initial) selectWork((candidate || initial).id);
  else renderAll();
}

/* ---------- selection ---------- */
function selectWork(id) {
  const work = state.works.find((w) => w.id === id);
  if (!work) return;
  stopAudio();
  closeMenu();
  closeProc();
  state.workId = id;
  state.errorNoted = false;
  loadAudio(work);
  setProgress(0);
  renderAll();
  ensurePeaks(work);
  syncHash();
}

/* ---------- audio ---------- */
function loadAudio(work) {
  const audio = DOM.audio;
  const src = audioSrc(work);
  if (!src || !work.playable) {
    audio.pause();
    audio.removeAttribute('src');
    audio.load();
    updatePlayButton(false);
    DOM.timeAll.textContent = work.durText || '—';
    return;
  }
  audio.src = src;
  DOM.timeAll.textContent = work.durText;
}

function stopAudio() {
  const audio = DOM.audio;
  audio.pause();
  try {
    audio.currentTime = 0;
  } catch (error) {
    /* ignore seek before metadata */
  }
  updatePlayButton(false);
}

function updatePlayButton(playing) {
  const use = DOM.playBtn.querySelector('use');
  if (use) use.setAttribute('href', playing ? '#i-pause' : '#i-play');
  DOM.playBtn.setAttribute('aria-label', playing ? '暂停原作' : '播放原作');
  DOM.playBtn.title = playing ? '暂停' : '播放';
}

function setProgress(ratio, duration) {
  const p = Math.max(0, Math.min(1, Number.isFinite(ratio) ? ratio : 0));
  DOM.progress.style.width = `${p * 100}%`;
  DOM.timeline.setAttribute('aria-valuenow', String(Math.round(p * 100)));
  const head = DOM.waveArea.querySelector('.wave-big .head');
  if (head) {
    head.style.left = `${p * 100}%`;
    head.classList.toggle('active', p > 0);
  }
  const midiHead = document.getElementById('midiHead');
  if (midiHead) midiHead.setAttribute('x', String(p * (state.midiWidth || 0)));
  const scoreBar = document.getElementById('scoreProgress');
  if (scoreBar) scoreBar.style.width = `${p * 100}%`;
  const total = duration || DOM.audio.duration || 0;
  DOM.timeNow.textContent = p > 0 ? fmtTime(p * total) : '00:00';
}

function togglePlay() {
  const work = currentWork();
  if (state.view !== 'wave') {
    if (!isCaseWork(work)) {
      toast('这首歌没有可播放的 MIDI');
      return;
    }
    if (!state.midiNotes) {
      loadCaseMidi();
      toast('MIDI 载入中，稍后再按一次播放');
      return;
    }
    if (midiPlayer.playing) midiPlayer.pause();
    else midiPlayer.play();
    return;
  }
  if (!work || !audioSrc(work) || !work.playable) {
    toast('这个作品当前没有可播放的原作。');
    return;
  }
  const audio = DOM.audio;
  if (audio.paused) {
    audio.play().catch(() => {
      if (!state.errorNoted) {
        state.errorNoted = true;
        toast('浏览器无法播放这个文件。');
      }
    });
  } else {
    audio.pause();
  }
}

function seekTo(ratio) {
  if (state.view !== 'wave') {
    midiPlayer.seek(ratio);
    return;
  }
  const audio = DOM.audio;
  if (!Number.isFinite(audio.duration) || audio.duration <= 0) return;
  audio.currentTime = Math.max(0, Math.min(audio.duration, ratio * audio.duration));
  setProgress(audio.currentTime / audio.duration);
}

/* ---------- waveform ---------- */
async function ensurePeaks(work) {
  const key = peakKey(work);
  if (!key || state.peaks.has(key) || state.loadingPeaks.has(key)) return;
  state.loadingPeaks.add(key);
  try {
    let peaks = 'unavailable';
    if (work.rel) {
      const response = await fetch(`/api/waveform?rel=${encodeURIComponent(work.rel)}&buckets=180`, { cache: 'no-store' });
      const data = await response.json();
      if (response.ok && Array.isArray(data.peaks)) peaks = data.peaks;
    } else if (work.srcUrl && (work.size || 0) < 200 * 1024 * 1024) {
      peaks = await localPeaks(work.srcUrl);
    }
    state.peaks.set(key, peaks);
  } catch (error) {
    state.peaks.set(key, 'unavailable');
  } finally {
    state.loadingPeaks.delete(key);
  }
  if (state.workId === work.id) {
    if (state.view === 'wave') renderWave();
    const audio = DOM.audio;
    if (Number.isFinite(audio.duration) && audio.duration > 0) setProgress(audio.currentTime / audio.duration);
  }
}

async function localPeaks(url) {
  const Ctx = window.AudioContext || window.webkitAudioContext;
  if (!Ctx) return 'unavailable';
  const response = await fetch(url);
  const buffer = await response.arrayBuffer();
  const ctx = new Ctx();
  try {
    const decoded = await ctx.decodeAudioData(buffer);
    const channel = decoded.getChannelData(0);
    const buckets = 180;
    const step = Math.max(1, Math.floor(channel.length / buckets));
    const stride = Math.max(1, Math.floor(step / 64));
    const peaks = [];
    for (let i = 0; i < buckets; i += 1) {
      const start = i * step;
      const end = Math.min(channel.length, start + step);
      let peak = 0;
      for (let j = start; j < end; j += stride) peak = Math.max(peak, Math.abs(channel[j] || 0));
      peaks.push(Math.min(100, Math.round(peak * 100)));
    }
    return peaks;
  } catch (error) {
    return 'unavailable';
  } finally {
    if (ctx.close) ctx.close();
  }
}

/* ---------- render ---------- */
function filteredWorks() {
  const q = state.query.trim().toLowerCase();
  if (!q) return state.works;
  return state.works.filter((work) =>
    String(`${work.title} ${work.file || ''} ${work.rel || ''}`).toLowerCase().includes(q),
  );
}

function renderLibrary() {
  const list = filteredWorks();
  if (!list.length) {
    DOM.workList.innerHTML = `<li class="work-empty">${state.query ? '没有匹配的作品' : '还没有作品'}</li>`;
    return;
  }
  DOM.workList.innerHTML = list
    .map((work) => {
      const active = work.id === state.workId;
      return `<li class="work${active ? ' active' : ''}" data-work="${esc(work.id)}" role="button" tabindex="0" aria-pressed="${active}">
        <div class="w-title" title="${esc(work.title)}">${esc(work.title)}</div>
        <div class="w-meta">${esc(work.durText || '—')}</div>
      </li>`;
    })
    .join('');
}

function renderHeader() {
  const work = currentWork();
  if (!work) {
    DOM.workTitle.textContent = '导入第一首音乐';
    return;
  }
  DOM.workTitle.textContent = work.title;
}

function renderActions() {
  const work = currentWork();
  const requested = !!work && work.separation === 'requested';
  DOM.separateBtn.disabled = !work || requested;
  DOM.separateBtn.title = requested ? '等待处理' : '分轨';
  DOM.separateBtn.setAttribute('aria-label', requested ? '等待处理' : '分轨');
  DOM.backingBtn.disabled = !work;
  DOM.singBtn.disabled = !work;
  DOM.exportBtn.disabled = !work;
}

function renderStatus() {
  const work = currentWork();
  if (!work || work.separation !== 'requested') {
    DOM.statusLine.hidden = true;
    DOM.statusLine.textContent = '';
    return;
  }
  DOM.statusLine.hidden = false;
  DOM.statusLine.innerHTML = '<svg class="icon"><use href="#i-alert"></use></svg><span>Core unavailable · 等待 Core 接入</span>';
}

function renderWave() {
  const work = currentWork();
  if (!work) {
    DOM.waveArea.innerHTML = '<p class="wave-empty"><svg class="icon"><use href="#i-note"></use></svg><span>导入一首音乐，先成为作品</span></p>';
    return;
  }

  const key = peakKey(work);
  if (!key) {
    DOM.waveArea.innerHTML = '<p class="wave-empty"><svg class="icon"><use href="#i-alert"></use></svg><span>没有可播放的原作</span></p>';
    return;
  }

  const peaks = state.peaks.get(key);
  if (peaks === 'unavailable') {
    DOM.waveArea.innerHTML = '<p class="wave-empty"><svg class="icon"><use href="#i-alert"></use></svg><span>波形不可用</span></p>';
    return;
  }
  if (!Array.isArray(peaks)) {
    DOM.waveArea.innerHTML = '';
    return;
  }

  const bars = downsample(peaks, window.innerWidth < 720 ? 84 : 180)
    .map((h) => `<i style="height:${Math.max(2, h)}%"></i>`)
    .join('');
  DOM.waveArea.innerHTML = `<div class="wave-big" role="button" tabindex="0" aria-label="波形，点按定位">${bars}<span class="head"></span></div>`;
}

function renderAll() {
  renderLibrary();
  renderHeader();
  renderActions();
  renderStatus();
  renderDock();
  if (state.view === 'wave') renderWave();
  else if (state.view === 'midi') renderMidi();
  else renderScore();
  renderChain();
  renderAB();
  renderDetails();
  if (!DOM.procPanel.hidden) renderVersions();
}

/* ---------- details ---------- */
function renderDetails() {
  if (DOM.details.hidden) return;
  const work = currentWork();
  if (!work) {
    DOM.detailsList.innerHTML = '<div class="row"><dt>状态</dt><dd>未选择作品</dd></div>';
    return;
  }
  const rows = [
    ['文件', work.file || '—'],
    ['时长', work.durText || '—'],
    ['采样率', work.sr ? `${Math.round(work.sr / 1000)} kHz` : '—'],
    ['位深', work.bits ? `${work.bits} bit` : '—'],
    ['声道', work.ch === 1 ? '单声道' : work.ch ? '立体声' : '—'],
    ['来源', work.rel ? 'AI WAV 曲库' : '本机导入'],
    ['原作', '始终保留'],
    ['状态', work.separation === 'requested' ? 'Core unavailable' : '已导入'],
  ];
  DOM.detailsList.innerHTML = rows
    .map(([label, value]) => `<div class="row"><dt>${esc(label)}</dt><dd>${esc(value)}</dd></div>`)
    .join('');
}

function openDetails() {
  closeMenu();
  DOM.details.hidden = false;
  renderDetails();
}

function closeDetails() {
  DOM.details.hidden = true;
}

/* ---------- actions ---------- */
function requestSeparation() {
  const work = currentWork();
  if (!work || work.separation === 'requested') return;
  openModal(DOM.sepModal);
}

function startSeparation() {
  const work = currentWork();
  closeModal(DOM.sepModal);
  if (!work) return;
  work.separation = 'requested';
  renderHeader();
  renderActions();
  renderStatus();
  toast('分轨请求已记录：Core 未连接，暂无结果。');
}

function promptBacking() {
  const work = currentWork();
  if (!work) return;
  toast('先分轨，才能制作伴奏。');
  if (work.separation !== 'requested') openModal(DOM.sepModal);
}

function promptSing() {
  openModal(DOM.singModal);
}

function exportOriginal() {
  const work = currentWork();
  const src = audioSrc(work);
  if (!work || !src) {
    toast('没有可导出的原作。');
    return;
  }
  const link = document.createElement('a');
  link.href = src;
  link.download = work.file || `${work.title}.wav`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  toast('已导出原作。');
}

/* ---------- rename ---------- */
let editingRename = false;

function startRename() {
  const work = currentWork();
  if (!work || editingRename) return;
  closeMenu();
  editingRename = true;
  DOM.workTitle.contentEditable = 'true';
  DOM.workTitle.classList.add('editing');
  DOM.workTitle.focus();
  const range = document.createRange();
  range.selectNodeContents(DOM.workTitle);
  const selection = window.getSelection();
  selection.removeAllRanges();
  selection.addRange(range);
}

function finishRename(save) {
  if (!editingRename) return;
  editingRename = false;
  DOM.workTitle.contentEditable = 'false';
  DOM.workTitle.classList.remove('editing');
  const work = currentWork();
  const next = DOM.workTitle.textContent.replace(/\s+/g, ' ').trim();
  if (save && work && next) {
    work.title = next;
    renderHeader();
    renderLibrary();
    renderDetails();
  } else {
    renderHeader();
  }
}

/* ---------- remove ---------- */
function removeWork() {
  const work = currentWork();
  if (!work) return;
  closeMenu();
  const index = state.works.indexOf(work);
  state.works = state.works.filter((w) => w.id !== work.id);
  const next = state.works[index] || state.works[index - 1] || state.works[0];
  toast('已从当前作品库移除（仅本次会话）。');
  if (next) {
    selectWork(next.id);
  } else {
    state.workId = null;
    stopAudio();
    DOM.timeAll.textContent = '00:00';
    renderAll();
    syncHash();
  }
}

/* ---------- upload ---------- */
function handleUpload(fileList) {
  const file = fileList && fileList[0];
  if (!file) return;
  const srcUrl = URL.createObjectURL(file);
  const title = file.name.replace(/\.[^.]+$/, '') || '未命名';
  const work = {
    id: `SVF-L${String(state.works.length).padStart(3, '0')}`,
    title,
    file: file.name,
    rel: null,
    srcUrl,
    size: file.size,
    dur: null,
    durText: '—',
    sr: null,
    bits: null,
    ch: null,
    playable: true,
    real: false,
    separation: 'idle',
    chains: { wave: [], midi: [], score: [] },
    versions: { wave: 'original', midi: 'original', score: 'original' },
  };
  state.works.unshift(work);
  state.workId = work.id;
  state.query = '';
  DOM.search.value = '';
  loadAudio(work);
  setProgress(0);
  renderAll();
  ensurePeaks(work);
  syncHash();
  toast('已导入：原作保留，等待你决定是否分轨。');
  DOM.file.value = '';
}


/* ---------- post-processing workbench ---------- */
const TOOLS = {
  eq: { name: '均衡 EQ', short: 'EQ', icon: '#i-eq', params: [
    { id: 'low', label: '低频', min: -12, max: 12, step: 1, value: 0, unit: 'dB' },
    { id: 'mid', label: '中频', min: -12, max: 12, step: 1, value: 0, unit: 'dB' },
    { id: 'high', label: '高频', min: -12, max: 12, step: 1, value: 0, unit: 'dB' },
    { id: 'q', label: 'Q', min: 0.3, max: 8, step: 0.1, value: 1, unit: '' },
  ] },
  dynamics: { name: '动态', short: '动态', icon: '#i-dynamics', params: [
    { id: 'threshold', label: '阈值', min: -60, max: 0, step: 1, value: -18, unit: 'dB' },
    { id: 'ratio', label: '压缩比', min: 1, max: 20, step: 1, value: 4, unit: ':1' },
    { id: 'attack', label: '启动', min: 1, max: 100, step: 1, value: 15, unit: 'ms' },
    { id: 'release', label: '释放', min: 20, max: 500, step: 5, value: 120, unit: 'ms' },
  ] },
  loudness: { name: '响度', short: '响度', icon: '#i-loudness', params: [
    { id: 'target', label: '目标', min: -23, max: -9, step: 0.5, value: -14, unit: 'LUFS' },
    { id: 'tp', label: '真峰上限', min: -3, max: 0, step: 0.1, value: -1, unit: 'dBTP' },
  ] },
  tune: { name: '修音', short: '修音', icon: '#i-tune', params: [
    { id: 'strength', label: '强度', min: 0, max: 100, step: 1, value: 60, unit: '%' },
    { id: 'range', label: '范围', min: 0, max: 100, step: 1, value: 40, unit: '%' },
    { id: 'vibrato', label: '保留颤音', min: 0, max: 100, step: 1, value: 30, unit: '%' },
  ] },
  repair: { name: '修复', short: '修复', icon: '#i-repair', params: [
    { id: 'type', label: '类型', type: 'select', options: ['去爆音', '去齿音', '去咔嚓'], value: '去爆音' },
    { id: 'amount', label: '强度', min: 0, max: 100, step: 1, value: 50, unit: '%' },
  ] },
  denoise: { name: '降噪', short: '降噪', icon: '#i-denoise', params: [
    { id: 'amount', label: '强度', min: 0, max: 100, step: 1, value: 40, unit: '%' },
    { id: 'smooth', label: '平滑', min: 0, max: 100, step: 1, value: 50, unit: '%' },
  ] },
  limiter: { name: '限幅', short: '限幅', icon: '#i-limiter', params: [
    { id: 'ceiling', label: '上限', min: -3, max: 0, step: 0.1, value: -1, unit: 'dBTP' },
    { id: 'release', label: '释放', min: 10, max: 500, step: 5, value: 80, unit: 'ms' },
  ] },
  width: { name: '声场宽度', short: '声场', icon: '#i-width', params: [
    { id: 'width', label: '宽度', min: 0, max: 200, step: 5, value: 100, unit: '%' },
  ] },
  midi_quantize: { name: 'MIDI 量化', short: '量化', icon: '#i-grid', params: [
    { id: 'grid', label: '网格', type: 'select', options: ['1/8', '1/16', '1/32'], value: '1/16' },
    { id: 'strength', label: '强度', min: 0, max: 100, step: 1, value: 100, unit: '%' },
  ] },
  midi_velocity: { name: '力度', short: '力度', icon: '#i-velocity', params: [
    { id: 'curve', label: '曲线', type: 'select', options: ['柔和', '线性', '重音'], value: '线性' },
    { id: 'amount', label: '幅度', min: 0, max: 100, step: 1, value: 50, unit: '%' },
  ] },
  midi_transpose: { name: '移调', short: '移调', icon: '#i-tune', params: [
    { id: 'semitones', label: '半音', min: -12, max: 12, step: 1, value: 0, unit: 'st' },
  ] },
  midi_humanize: { name: '人性化', short: '人性化', icon: '#i-dynamics', params: [
    { id: 'timing', label: '时间', min: 0, max: 50, step: 1, value: 8, unit: 'ms' },
    { id: 'velocity', label: '力度', min: 0, max: 30, step: 1, value: 10, unit: '%' },
  ] },
  midi_legato: { name: '连音', short: '连音', icon: '#i-width', params: [
    { id: 'overlap', label: '重叠', min: 0, max: 30, step: 1, value: 10, unit: '%' },
  ] },
  score_quantize: { name: '曲谱量化', short: '量化', icon: '#i-grid', params: [
    { id: 'grid', label: '网格', type: 'select', options: ['1/4', '1/8', '1/16'], value: '1/8' },
    { id: 'strength', label: '强度', min: 0, max: 100, step: 1, value: 100, unit: '%' },
  ] },
  score_key: { name: '调号', short: '调号', icon: '#i-sharp', params: [
    { id: 'key', label: '调', type: 'select', options: ['C', 'G', 'D', 'A', 'E', 'F', 'Bb', 'Eb'], value: 'C' },
  ] },
  score_meter: { name: '拍号', short: '拍号', icon: '#i-grid', params: [
    { id: 'meter', label: '拍号', type: 'select', options: ['4/4', '3/4', '6/8'], value: '4/4' },
  ] },
  score_layout: { name: '排版', short: '排版', icon: '#i-page', params: [
    { id: 'bars', label: '每行小节', min: 2, max: 8, step: 1, value: 4, unit: '' },
    { id: 'size', label: '字号', min: 6, max: 12, step: 0.5, value: 8, unit: 'pt' },
  ] },
};

const TOOLSETS = {
  wave: ['eq', 'dynamics', 'loudness', 'tune', 'repair', 'denoise', 'limiter', 'width'],
  midi: ['midi_quantize', 'midi_velocity', 'midi_transpose', 'midi_humanize', 'midi_legato'],
  score: ['score_quantize', 'score_key', 'score_meter', 'score_layout'],
};

function renderDock() {
  const tools = TOOLSETS[state.view] || TOOLSETS.wave;
  DOM.dockTools.innerHTML = tools.map((id) => {
    const def = TOOLS[id];
    return `<button class="tool" type="button" data-tool="${id}" aria-label="${esc(def.name)}" title="${esc(def.name)}"><svg class="icon"><use href="${def.icon}"></use></svg></button>`;
  }).join('');
}

function isCaseWork(work) {
  return !!work && work.file === '自己的神话.wav';
}

async function loadCaseMidi() {
  if (state.midiNotes || state.midiLoading) return;
  if (window.SVF_CASE_MIDI) {
    state.midiNotes = window.SVF_CASE_MIDI;
    if (state.view === 'midi') renderMidi();
    else if (state.view === 'score') renderScore();
    const total = midiPlayer.duration();
    if (state.view !== 'wave' && total) {
      DOM.timeAll.textContent = fmtTime(total);
      setProgress(0, total);
    }
    return;
  }
  state.midiLoading = true;
  try {
    const response = await fetch('assets/case/lead-notes.json', { cache: 'no-store' });
    state.midiNotes = await response.json();
  } catch (error) {
    state.midiNotes = { notes: [], duration: 0, error: true };
  }
  state.midiLoading = false;
  const total = midiPlayer.duration();
  if (state.view === 'midi') {
    renderMidi();
  } else if (state.view === 'score') {
    renderScore();
  }
  if (state.view !== 'wave' && total) {
    DOM.timeAll.textContent = fmtTime(total);
    setProgress(0, total);
  }
}

function renderMidi() {
  const work = currentWork();
  if (!work) {
    DOM.midiView.innerHTML = '';
    return;
  }
  if (!isCaseWork(work)) {
    DOM.midiView.innerHTML = '<p class="viewempty">这首歌没有 MIDI</p>';
    return;
  }
  if (!state.midiNotes) {
    DOM.midiView.innerHTML = '<p class="viewempty">读取 MIDI…</p>';
    loadCaseMidi();
    return;
  }
  const notes = state.midiNotes.notes || [];
  if (!notes.length) {
    DOM.midiView.innerHTML = '<p class="viewempty">MIDI 不可用</p>';
    return;
  }
  const duration = state.midiNotes.duration || 1;
  const W = Math.max(1600, Math.round(duration * 14));
  const H = 420;
  const pad = 16;
  const pitches = notes.map((note) => note[2]).sort((a, b) => a - b);
  const lo = pitches[Math.floor(pitches.length * 0.03)] - 1;
  const hi = pitches[Math.floor(pitches.length * 0.97)] + 1;
  const rows = hi - lo + 1;
  const rowH = (H - pad * 2) / rows;
  const beats = [];
  const beat = 60 / (state.midiNotes.tempo || 102);
  for (let t = 0; t <= duration; t += beat * 4) {
    const x = (t / duration) * W;
    beats.push(`<line x1="${x.toFixed(1)}" y1="0" x2="${x.toFixed(1)}" y2="${H}" stroke="#ecece6" stroke-width="1"/>`);
  }
  const rects = notes.map((note) => {
    const x = (note[0] / duration) * W;
    const w = Math.max(1.4, (note[1] / duration) * W);
    const y = pad + Math.min(rows - 1, Math.max(0, hi - note[2])) * rowH;
    const opacity = (0.35 + (note[3] / 127) * 0.6).toFixed(2);
    return `<rect x="${x.toFixed(1)}" y="${y.toFixed(1)}" width="${w.toFixed(1)}" height="${Math.max(2, rowH - 1).toFixed(1)}" fill="#ff6b4a" opacity="${opacity}"/>`;
  }).join('');
  DOM.midiView.innerHTML = `<div class="roll-head">MIDI · 主唱 · ${notes.length} 个音符 · ${state.midiNotes.tempo || 102} BPM · ${fmtTime(duration)}</div>
    <svg width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-label="MIDI 钢琴卷帘">${beats.join('')}${rects}<line id="midiHead" x1="0" y1="0" x2="0" y2="${H}" stroke="#14181a" stroke-width="1.5" opacity=".75"/></svg>`;
  state.midiWidth = W;
}

function renderScore() {
  const work = currentWork();
  if (!work) {
    DOM.scoreView.innerHTML = '';
    return;
  }
  if (!isCaseWork(work)) {
    DOM.scoreView.innerHTML = '<p class="viewempty">这首歌没有曲谱</p>';
    return;
  }
  if (!state.midiNotes) {
    DOM.scoreView.innerHTML = '<p class="viewempty">读取曲谱数据…</p>';
    loadCaseMidi();
    return;
  }
  const notes = state.midiNotes.notes || [];
  const duration = state.midiNotes.duration || 0;
  if (!notes.length || !duration) {
    DOM.scoreView.innerHTML = '<p class="viewempty">曲谱不可用</p>';
    return;
  }
  const tempo = state.midiNotes.tempo || 102;
  const pxPerSec = 100;
  const left = 96;
  const W = Math.ceil(left + duration * pxPerSec + 40);
  const pitches = notes.map((note) => note[2]).sort((a, b) => a - b);
  const center = pitches[Math.floor(pitches.length / 2)];
  const pitchY = (pitch) => Math.min(310, Math.max(10, 160 - (pitch - center) * 6));
  const beat = 60 / tempo;
  const barDur = beat * 4;
  const bars = [];
  for (let t = 0, bar = 1; t <= duration; t += barDur, bar += 1) {
    const x = left + t * pxPerSec;
    bars.push(`<line x1="${x}" y1="112" x2="${x}" y2="184" stroke="#d5d5cd"/>`);
    bars.push(`<text x="${x + 4}" y="100" font-size="11" fill="#8a9189">${bar}</text>`);
  }
  const heads = notes.map((note) => {
    const x = left + note[0] * pxPerSec;
    const y = pitchY(note[2]);
    const up = y > 160;
    const stem = `<line x1="${x + 6}" y1="${y}" x2="${x + 6}" y2="${y + (up ? -36 : 36)}" stroke="#14181a" stroke-width="1.3"/>`;
    const open = note[1] > 0.6;
    const head = open
      ? `<ellipse cx="${x}" cy="${y}" rx="7" ry="5" fill="none" stroke="#14181a" stroke-width="1.6" transform="rotate(-20 ${x} ${y})"/>`
      : `<ellipse cx="${x}" cy="${y}" rx="7" ry="5" fill="#14181a" transform="rotate(-20 ${x} ${y})"/>`;
    return head + stem;
  }).join('');
  const staff = [124, 136, 148, 160, 172]
    .map((y) => `<line x1="86" y1="${y}" x2="${W - 10}" y2="${y}" stroke="#b9b9b1"/>`)
    .join('');
  DOM.scoreView.innerHTML = `<div class="score-head"><div class="roll-head">连续曲谱 · MIDI 生成 · ${tempo} BPM · 4/4 · ${fmtTime(duration)} <span class="score-page-ind" id="scorePageInd">0:00</span></div><div class="score-progress"><i id="scoreProgress"></i></div></div>
    <div class="score-scroll" id="scoreScroll"><svg id="scoreSvg" width="${W}" height="320" viewBox="0 0 ${W} 320" style="width:${W}px;height:320px;flex:0 0 auto" role="img" aria-label="连续曲谱">
      <text x="8" y="192" font-size="86" font-family="serif" fill="#14181a">𝄞</text>
      <text x="82" y="190" font-size="32" font-family="serif" fill="#14181a">♭♭♭</text>
      <text x="138" y="152" font-size="18" fill="#14181a">4</text>
      <text x="138" y="176" font-size="18" fill="#14181a">4</text>
      ${staff}${bars.join('')}
      <line x1="86" y1="112" x2="86" y2="184" stroke="#14181a" stroke-width="1.6"/>
      ${heads}
      <line id="scoreCursor" x1="${left}" y1="84" x2="${left}" y2="232" stroke="#ff6b4a" stroke-width="2.4"/>
    </svg></div>`;
  state.scoreWidth = W;
}

function setView(view) {
  state.view = view;
  document.querySelectorAll('.viewtab').forEach((button) => {
    const active = button.dataset.view === view;
    button.classList.toggle('on', active);
    button.setAttribute('aria-selected', String(active));
  });
  audio.pause();
  midiPlayer.pause();
  midiPlayer.pos = 0;
  DOM.waveArea.hidden = view !== 'wave';
  DOM.midiView.hidden = view !== 'midi';
  DOM.scoreView.hidden = view !== 'score';
  if (view === 'wave') renderWave();
  else if (view === 'midi') renderMidi();
  else renderScore();
  if (view !== 'wave' && isCaseWork(currentWork()) && !state.midiNotes) loadCaseMidi();
  renderDock();
  renderChain();
  renderAB();
  if (!DOM.procPanel.hidden) renderVersions();
  closeProc();
  if (view === 'score') {
    const pane = document.getElementById('scoreScroll');
    if (pane) pane.scrollLeft = 0;
    const ind = document.getElementById('scorePageInd');
    if (ind) ind.textContent = '1 / 4';
  }
  const total = viewDuration();
  DOM.timeAll.textContent = total ? fmtTime(total) : '—';
  setProgress(0, total);
  updatePlayButton(false);
}

let procEdit = null;

function ensureChains(work) {
  if (!work) return { wave: [], midi: [], score: [] };
  if (!work.chains) work.chains = { wave: [], midi: [], score: [] };
  ['wave', 'midi', 'score'].forEach((key) => {
    if (!Array.isArray(work.chains[key])) work.chains[key] = [];
  });
  return work.chains;
}

function ensureChain(work) {
  return work ? ensureChains(work)[state.view] : [];
}

function versionOf(work) {
  if (!work) return 'original';
  if (!work.versions) work.versions = { wave: 'original', midi: 'original', score: 'original' };
  return work.versions[state.view] || 'original';
}

function setVersion(work, value) {
  if (!work) return;
  if (!work.versions) work.versions = { wave: 'original', midi: 'original', score: 'original' };
  work.versions[state.view] = value;
}

function renderChain() {
  const work = currentWork();
  const chain = ensureChain(work);
  if (!chain.length && work) setVersion(work, 'original');
  DOM.chainList.innerHTML = chain.length
    ? chain.map((node) => {
        const def = TOOLS[node.tool];
        return `<button class="chain-chip${node.bypass ? ' bypassed' : ''}" type="button" data-node="${node.id}" title="${esc(node.name)}${node.bypass ? ' · 已旁通' : ''}">
          <svg class="icon"><use href="${def ? def.icon : '#i-sliders'}"></use></svg><span>${esc(def ? def.short : node.name)}</span>
        </button>`;
      }).join('')
    : '<span class="chain-empty">未添加处理</span>';
  DOM.chainFlag.hidden = chain.length === 0;
}

function renderAB() {
  const work = currentWork();
  const has = !!work && ensureChain(work).length > 0;
  DOM.abProcessed.disabled = !has;
  DOM.abOriginal.textContent = originalLabel();
  DOM.abOriginal.classList.toggle('on', !has || versionOf(work) !== 'processed');
  DOM.abProcessed.classList.toggle('on', has && versionOf(work) === 'processed');
}

function renderVersions() {
  const work = currentWork();
  const has = !!work && ensureChain(work).length > 0;
  DOM.procVersions.innerHTML = `
    <button class="ver${!work || versionOf(work) !== 'processed' ? ' on' : ''}" type="button" data-ver="original"><span>${esc(originalLabel())}</span><em>${esc(work ? work.durText : '—')}</em></button>
    <button class="ver${work && versionOf(work) === 'processed' ? ' on' : ''}" type="button" data-ver="processed" ${has ? '' : 'disabled'}><span>处理版本 01</span><em>${has ? '未渲染' : '—'}</em></button>`;
}

function openProc(tool, nodeId) {
  const work = currentWork();
  const def = TOOLS[tool];
  if (!work || !def) return;
  const chain = ensureChain(work);
  const node = nodeId ? chain.find((item) => item.id === nodeId) : null;
  procEdit = { tool, nodeId: node ? node.id : null, params: {}, bypass: node ? !!node.bypass : false };
  def.params.forEach((param) => {
    procEdit.params[param.id] = node && node.params[param.id] != null ? node.params[param.id] : param.value;
  });
  DOM.procIcon.setAttribute('href', def.icon);
  DOM.procName.textContent = def.name;
  DOM.procParams.innerHTML = def.params.map((param) => {
    if (param.type === 'select') {
      return `<div class="param"><label>${esc(param.label)}</label><select data-param="${param.id}">${param.options
        .map((option) => `<option${option === procEdit.params[param.id] ? ' selected' : ''}>${esc(option)}</option>`)
        .join('')}</select><output></output></div>`;
    }
    return `<div class="param"><label>${esc(param.label)}</label><input type="range" data-param="${param.id}" min="${param.min}" max="${param.max}" step="${param.step}" value="${procEdit.params[param.id]}"><output>${procEdit.params[param.id]}${param.unit || ''}</output></div>`;
  }).join('');
  DOM.procBypass.checked = procEdit.bypass;
  DOM.procApply.textContent = node ? '保存' : '加入处理链';
  DOM.procRemove.hidden = !node;
  DOM.procPanel.hidden = false;
  renderVersions();
}

function closeProc() {
  procEdit = null;
  DOM.procPanel.hidden = true;
}

function applyProc() {
  const work = currentWork();
  if (!work || !procEdit) return;
  const chain = ensureChain(work);
  if (procEdit.nodeId) {
    const node = chain.find((item) => item.id === procEdit.nodeId);
    if (node) {
      node.params = { ...procEdit.params };
      node.bypass = procEdit.bypass;
    }
    toast('处理参数已更新（未渲染）');
  } else {
    chain.push({
      id: `p${Date.now()}${Math.floor(Math.random() * 99)}`,
      tool: procEdit.tool,
      name: TOOLS[procEdit.tool].name,
      params: { ...procEdit.params },
      bypass: procEdit.bypass,
    });
    toast(`${TOOLS[procEdit.tool].name} 已加入处理链（未渲染）`);
  }
  closeProc();
  renderChain();
  renderAB();
}

function removeProcNode() {
  const work = currentWork();
  if (!work || !procEdit || !procEdit.nodeId) return;
  work.chain = ensureChain(work).filter((item) => item.id !== procEdit.nodeId);
  closeProc();
  renderChain();
  renderAB();
  toast('已从处理链移除');
}

/* ---------- events ---------- */
DOM.workList.addEventListener('click', (event) => {
  const item = event.target.closest('[data-work]');
  if (item) selectWork(item.dataset.work);
});
DOM.workList.addEventListener('keydown', (event) => {
  if (event.key !== 'Enter' && event.key !== ' ') return;
  const item = event.target.closest('[data-work]');
  if (!item) return;
  event.preventDefault();
  selectWork(item.dataset.work);
});

let searchTimer = 0;
DOM.search.addEventListener('input', () => {
  window.clearTimeout(searchTimer);
  searchTimer = window.setTimeout(() => {
    state.query = DOM.search.value;
    renderLibrary();
  }, 120);
});
DOM.search.addEventListener('keydown', (event) => {
  if (event.key !== 'Escape') return;
  DOM.search.value = '';
  state.query = '';
  renderLibrary();
});

function setLibCollapsed(collapsed) {
  document.querySelector('.stage').classList.toggle('lib-collapsed', collapsed);
  DOM.libToggle.setAttribute('aria-label', collapsed ? '展开作品库' : '收起作品库');
  DOM.libToggle.title = collapsed ? '展开作品库' : '收起作品库';
}

DOM.libToggle.addEventListener('click', () => {
  setLibCollapsed(!document.querySelector('.stage').classList.contains('lib-collapsed'));
});

window.matchMedia('(max-width:860px)').addEventListener('change', (event) => {
  if (event.matches) setLibCollapsed(false);
});

DOM.moreBtn.addEventListener('click', (event) => {
  event.stopPropagation();
  DOM.moreMenu.hidden = !DOM.moreMenu.hidden;
});
DOM.moreMenu.addEventListener('click', (event) => {
  const item = event.target.closest('[data-menu]');
  if (!item) return;
  const action = item.dataset.menu;
  closeMenu();
  if (action === 'rename') startRename();
  else if (action === 'info') openDetails();
  else if (action === 'export') exportOriginal();
  else if (action === 'remove') removeWork();
});
document.addEventListener('click', (event) => {
  if (!event.target.closest('.menu-wrap')) closeMenu();
});

DOM.viewTabs.addEventListener('click', (event) => {
  const button = event.target.closest('[data-view]');
  if (button) setView(button.dataset.view);
});

DOM.dockTools.addEventListener('click', (event) => {
  const button = event.target.closest('[data-tool]');
  if (button) openProc(button.dataset.tool);
});

DOM.chainList.addEventListener('click', (event) => {
  const chip = event.target.closest('[data-node]');
  if (!chip) return;
  const work = currentWork();
  const node = work && ensureChain(work).find((item) => item.id === chip.dataset.node);
  if (node) openProc(node.tool, node.id);
});

DOM.ab.addEventListener('click', (event) => {
  const button = event.target.closest('[data-ab]');
  if (!button) return;
  const work = currentWork();
  if (!work) return;
  if (button.dataset.ab === 'processed') {
    if (!ensureChain(work).length) {
      toast('先添加一个处理');
      return;
    }
    setVersion(work, 'processed');
    toast('处理后版本尚未渲染：Core 未连接');
  } else {
    setVersion(work, 'original');
  }
  renderAB();
  renderVersions();
});

DOM.procParams.addEventListener('input', (event) => {
  const input = event.target.closest('[data-param]');
  if (!input || !procEdit) return;
  const def = TOOLS[procEdit.tool];
  const param = def.params.find((item) => item.id === input.dataset.param);
  const value = input.tagName === 'SELECT' ? input.value : Number(input.value);
  procEdit.params[input.dataset.param] = value;
  const output = input.parentElement.querySelector('output');
  if (output && param) output.textContent = `${value}${param.unit || ''}`;
});

DOM.procBypass.addEventListener('change', () => {
  if (procEdit) procEdit.bypass = DOM.procBypass.checked;
});

DOM.procApply.addEventListener('click', applyProc);
DOM.procRemove.addEventListener('click', removeProcNode);
DOM.procClose.addEventListener('click', closeProc);

DOM.procVersions.addEventListener('click', (event) => {
  const button = event.target.closest('[data-ver]');
  if (!button || button.disabled) return;
  const work = currentWork();
  if (!work) return;
  if (button.dataset.ver === 'processed') {
    if (!ensureChain(work).length) return;
    setVersion(work, 'processed');
    toast('处理后版本尚未渲染：Core 未连接');
  } else {
    setVersion(work, 'original');
  }
  renderAB();
  renderVersions();
});

DOM.separateBtn.addEventListener('click', requestSeparation);
DOM.backingBtn.addEventListener('click', promptBacking);
DOM.singBtn.addEventListener('click', promptSing);
DOM.exportBtn.addEventListener('click', exportOriginal);

$('#sepStart').addEventListener('click', startSeparation);
$('#sepCancel').addEventListener('click', () => closeModal(DOM.sepModal));
$('#singBacking').addEventListener('click', () => {
  closeModal(DOM.singModal);
  toast('Core 未连接，暂时无法生成伴奏。');
});
$('#singExternal').addEventListener('click', () => {
  closeModal(DOM.singModal);
  toast('外部伴奏入口尚未接入。');
});
DOM.sepModal.addEventListener('click', (event) => {
  if (event.target === DOM.sepModal) closeModal(DOM.sepModal);
});
DOM.singModal.addEventListener('click', (event) => {
  if (event.target === DOM.singModal) closeModal(DOM.singModal);
});
$('#closeDetails').addEventListener('click', closeDetails);

DOM.workTitle.addEventListener('keydown', (event) => {
  if (!editingRename) return;
  if (event.key === 'Enter') {
    event.preventDefault();
    finishRename(true);
  } else if (event.key === 'Escape') {
    event.preventDefault();
    finishRename(false);
  }
});
DOM.workTitle.addEventListener('blur', () => finishRename(true));

DOM.waveArea.addEventListener('click', (event) => {
  const wave = event.target.closest('.wave-big');
  if (!wave) return;
  const rect = wave.getBoundingClientRect();
  seekTo((event.clientX - rect.left) / rect.width);
});
DOM.waveArea.addEventListener('keydown', (event) => {
  if (event.key !== 'Enter' && event.key !== ' ') return;
  if (!event.target.closest('.wave-big')) return;
  event.preventDefault();
  togglePlay();
});

DOM.playBtn.addEventListener('click', togglePlay);
DOM.timeline.addEventListener('click', (event) => {
  const rect = DOM.timeline.getBoundingClientRect();
  seekTo((event.clientX - rect.left) / rect.width);
});
$('#uploadBtn').addEventListener('click', () => DOM.file.click());
DOM.file.addEventListener('change', () => handleUpload(DOM.file.files));

const audio = DOM.audio;
audio.addEventListener('play', () => updatePlayButton(true));
audio.addEventListener('pause', () => updatePlayButton(false));
audio.addEventListener('timeupdate', () => {
  if (!Number.isFinite(audio.duration) || audio.duration <= 0) return;
  if (state.view !== 'wave') return;
  setProgress(audio.currentTime / audio.duration, audio.duration);
});
audio.addEventListener('loadedmetadata', () => {
  if (!Number.isFinite(audio.duration) || audio.duration <= 0) return;
  const work = currentWork();
  if (work && !work.rel && work.durText === '—') {
    work.dur = audio.duration;
    work.durText = fmtTime(audio.duration);
    renderHeader();
    renderLibrary();
  }
  if (state.view === 'wave') DOM.timeAll.textContent = fmtTime(audio.duration);
});
audio.addEventListener('ended', () => {
  updatePlayButton(false);
  setProgress(0);
});
audio.addEventListener('error', () => {
  if (!audio.getAttribute('src')) return;
  updatePlayButton(false);
  if (!state.errorNoted) {
    state.errorNoted = true;
    toast('浏览器无法解码这个文件。');
  }
});

document.addEventListener('keydown', (event) => {
  if (event.key !== 'Escape') return;
  if (!DOM.sepModal.hidden) closeModal(DOM.sepModal);
  if (!DOM.singModal.hidden) closeModal(DOM.singModal);
  if (!DOM.details.hidden) closeDetails();
});

window.addEventListener('hashchange', () => {
  const id = readHash();
  if (id && id !== state.workId) selectWork(id);
});

boot();
