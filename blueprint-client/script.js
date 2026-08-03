const playBtn = document.getElementById('playBtn');
const statusText = document.getElementById('statusText');
const statusDetail = document.getElementById('statusDetail');
const progressFill = document.getElementById('progressFill');
const progressLabel = document.getElementById('progressLabel');
const consoleOutput = document.getElementById('consoleOutput');
const enabledCount = document.getElementById('enabledCount');
const modList = document.getElementById('modList');
const modPanel = document.getElementById('modPanel');
const modPanelContent = document.getElementById('modPanelContent');
const toggleModsBtn = document.getElementById('toggleModsBtn');
const closePanelBtn = document.getElementById('closePanelBtn');

const loadingScreen = document.getElementById('loadingScreen');
const loadingStatus = document.getElementById('loadingStatus');
const loadingPercent = document.getElementById('loadingPercent');
const loadingFill = document.getElementById('loadingFill');

const mods = [
  { name: 'Sodium', desc: 'Boosts FPS and keeps movement silky', enabled: true },
  { name: 'Lithium', desc: 'Better world and entity performance', enabled: true },
  { name: 'Better Combat', desc: 'Cleaner PvP hit feel and responsiveness', enabled: true },
  { name: 'Ksyxis', desc: 'Faster world loading and smoother joins', enabled: true },
  { name: 'Dynamic Lights', desc: 'Adds better lighting for combat visibility', enabled: false },
  { name: 'FastQuit', desc: 'Quits worlds faster for quick matches', enabled: false }
];

let panelOpen = false;
let hideLoadingTimer = null;

const loading = {
  show(status) {
    if (!loadingScreen) return;
    clearTimeout(hideLoadingTimer);
    loadingScreen.classList.remove('error');
    loadingScreen.classList.add('visible');
    loadingScreen.setAttribute('aria-hidden', 'false');
    this.set(0, status);
  },
  set(percent, status) {
    if (!loadingScreen) return;
    loadingFill.style.width = `${percent}%`;
    loadingPercent.textContent = `${percent}%`;
    if (status) loadingStatus.textContent = status;
  },
  hideAfter(delay) {
    if (!loadingScreen) return;
    clearTimeout(hideLoadingTimer);
    hideLoadingTimer = setTimeout(() => {
      loadingScreen.classList.remove('visible');
      loadingScreen.setAttribute('aria-hidden', 'true');
    }, delay);
  }
};

function renderMods() {
  const enabled = mods.filter((mod) => mod.enabled).length;
  if (enabledCount) {
    enabledCount.textContent = `${enabled} / ${mods.length} mods`;
  }

  const buildMarkup = (container) => {
    if (!container) return;
    container.innerHTML = mods
      .map((mod, index) => `
        <label class="mod-item">
          <span class="mod-toggle">
            <input type="checkbox" data-index="${index}" ${mod.enabled ? 'checked' : ''} />
            <span>
              <strong>${mod.name}</strong>
              <span>${mod.desc}</span>
            </span>
          </span>
          <span class="chip">${mod.enabled ? 'On' : 'Off'}</span>
        </label>
      `)
      .join('');
  };

  buildMarkup(modList);
  buildMarkup(modPanelContent);
}

function togglePanel(force) {
  if (!modPanel) return;
  panelOpen = typeof force === 'boolean' ? force : !panelOpen;
  modPanel.classList.toggle('open', panelOpen);
}

function handleModToggle(event) {
  const input = event.target.closest('input[data-index]');
  if (!input) return;
  const index = Number(input.dataset.index);
  mods[index].enabled = input.checked;
  renderMods();
}

playBtn.addEventListener('click', () => {
  playBtn.disabled = true;
  playBtn.textContent = 'Launching...';

  statusText.textContent = 'Launching';
  statusDetail.textContent = 'Preparing Minecraft 1.21.11';
  consoleOutput.textContent = 'Starting launcher...';
  progressFill.style.width = '0%';
  progressLabel.textContent = '0%';
  loading.show('Starting launcher...');

  const steps = [
    { value: 20, text: 'Checking game files...' },
    { value: 50, text: 'Preparing Java runtime...' },
    { value: 80, text: 'Loading Minecraft 1.21.11...' },
    { value: 100, text: 'Minecraft is ready. Enjoy your session.' }
  ];

  let index = 0;

  const tick = () => {
    if (index >= steps.length) {
      playBtn.disabled = false;
      playBtn.textContent = 'Play Again';
      statusText.textContent = 'Ready';
      statusDetail.textContent = 'Blueprint Client is live';
      loading.hideAfter(900);
      return;
    }

    const step = steps[index];
    progressFill.style.width = `${step.value}%`;
    progressLabel.textContent = `${step.value}%`;
    consoleOutput.textContent = step.text;
    loading.set(step.value, step.text);
    index += 1;

    setTimeout(tick, 550);
  };

  tick();
});

if (modList) modList.addEventListener('change', handleModToggle);
if (modPanelContent) modPanelContent.addEventListener('change', handleModToggle);
if (toggleModsBtn) toggleModsBtn.addEventListener('click', () => togglePanel());
if (closePanelBtn) closePanelBtn.addEventListener('click', () => togglePanel(false));

document.addEventListener('keydown', (event) => {
  if (event.code === 'ShiftRight') {
    event.preventDefault();
    togglePanel();
  }
});

renderMods();
