'use strict';
const tabs = [...document.querySelectorAll('[role="tab"]')];
function selectTab(index) {
  tabs.forEach((tab, i) => {
    const selected = i === index;
    tab.setAttribute('aria-selected', String(selected));
    tab.tabIndex = selected ? 0 : -1;
    document.getElementById(tab.getAttribute('aria-controls')).hidden = !selected;
  });
  const afterShot = index === 1;
  document.getElementById('demo-cue').hidden = afterShot;
  document.querySelector('.demo-horizon').hidden = afterShot;
  document.getElementById('annotation').hidden = !afterShot;
  document.getElementById('reflection').hidden = !afterShot;
}
tabs.forEach((tab, index) => {
  tab.addEventListener('click', () => selectTab(index));
  tab.addEventListener('keydown', event => {
    let next;
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    if (event.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length;
    if (event.key === 'Home') next = 0;
    if (event.key === 'End') next = tabs.length - 1;
    if (next !== undefined) {
      event.preventDefault();
      selectTab(next);
      tabs[next].focus();
    }
  });
});
