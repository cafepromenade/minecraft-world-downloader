/*
 * Accessibility & theme controls for the World Downloader web console.
 * Builds a floating menu, persists choices to localStorage, and applies them as data-* attributes on
 * <html> (see the matching rules in style.css). Includes presets for common needs (ADHD focus,
 * autism-calm, dyslexia/low-vision reading) plus light/dark/high-contrast themes.
 */
(function () {
  "use strict";

  var KEYS = {
    theme: { attr: "data-theme", values: ["dark", "light", "contrast"], def: "dark" },
    motion: { attr: "data-motion", values: ["full", "reduced"], def: "full" },
    calm: { attr: "data-calm", values: ["off", "on"], def: "off" },
    focus: { attr: "data-focus", values: ["off", "on"], def: "off" },
    font: { attr: "data-font", values: ["default", "dyslexic"], def: "default" },
    scale: { attr: "data-scale", values: ["normal", "large", "xlarge"], def: "normal" },
  };
  var STORE = "wdl.a11y";

  function load() {
    try { return JSON.parse(localStorage.getItem(STORE)) || {}; } catch (e) { return {}; }
  }
  function save(state) {
    try { localStorage.setItem(STORE, JSON.stringify(state)); } catch (e) {}
  }

  // Respect system preferences when the user hasn't chosen yet.
  function withDefaults(state) {
    var s = Object.assign({}, state);
    if (!s.theme && window.matchMedia && matchMedia("(prefers-color-scheme: light)").matches) s.theme = "light";
    if (!s.motion && window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches) s.motion = "reduced";
    if (!s.theme && window.matchMedia && matchMedia("(prefers-contrast: more)").matches) s.theme = "contrast";
    return s;
  }

  function apply(state) {
    var s = withDefaults(state);
    Object.keys(KEYS).forEach(function (k) {
      var cfg = KEYS[k];
      var val = s[k] || cfg.def;
      if (val === cfg.def && k !== "theme") {
        document.documentElement.removeAttribute(cfg.attr);
      } else {
        document.documentElement.setAttribute(cfg.attr, val);
      }
    });
  }

  var state = load();
  apply(state);

  function set(key, value) {
    state[key] = value;
    save(state);
    apply(state);
    render();
  }
  function applyPreset(preset) {
    if (preset === "adhd") state = { theme: state.theme || "dark", motion: "reduced", focus: "on", calm: "off", font: "default", scale: "large" };
    else if (preset === "autism") state = { theme: state.theme || "dark", motion: "reduced", calm: "on", focus: "off", font: "default", scale: "normal" };
    else if (preset === "reading") state = { theme: "light", motion: "reduced", font: "dyslexic", scale: "large", calm: "off", focus: "off" };
    else if (preset === "lowvision") state = { theme: "contrast", motion: "reduced", font: "default", scale: "xlarge", calm: "off", focus: "off" };
    save(state); apply(state); render();
  }

  var fab, panel;
  function row(label, key) {
    var cfg = KEYS[key];
    var html = '<div class="a11y-row"><label>' + label + '</label><div class="a11y-seg">';
    cfg.values.forEach(function (v) {
      var cur = (state[key] || cfg.def) === v;
      html += '<button type="button" data-key="' + key + '" data-val="' + v + '" aria-pressed="' + cur + '">' + v + '</button>';
    });
    return html + "</div></div>";
  }

  function render() {
    if (!panel) return;
    panel.innerHTML =
      '<h3>Accessibility</h3>' +
      '<p class="a11y-sub">Quick presets, then fine-tune. Saved on this device.</p>' +
      '<div class="a11y-row"><label>Presets</label><div class="a11y-seg">' +
        '<button type="button" data-preset="adhd">ADHD focus</button>' +
        '<button type="button" data-preset="autism">Calm</button>' +
        '<button type="button" data-preset="reading">Easy reading</button>' +
        '<button type="button" data-preset="lowvision">Low vision</button>' +
      '</div></div>' +
      row("Theme", "theme") +
      row("Text size", "scale") +
      row("Reading font", "font") +
      row("Motion", "motion") +
      row("Calm (no glow/animation)", "calm") +
      row("Focus mode (dim other panels)", "focus") +
      '<button type="button" class="btn small a11y-reset">Reset to defaults</button>';
  }

  function buildUi() {
    fab = document.createElement("button");
    fab.className = "a11y-fab";
    fab.type = "button";
    fab.setAttribute("aria-label", "Accessibility options");
    fab.setAttribute("aria-haspopup", "dialog");
    fab.textContent = "♿"; // wheelchair symbol
    document.body.appendChild(fab);

    panel = document.createElement("div");
    panel.className = "a11y-panel";
    panel.setAttribute("role", "dialog");
    panel.setAttribute("aria-label", "Accessibility options");
    document.body.appendChild(panel);
    render();

    fab.addEventListener("click", function () {
      var open = panel.classList.toggle("open");
      fab.setAttribute("aria-expanded", String(open));
    });
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape") panel.classList.remove("open");
    });
    panel.addEventListener("click", function (e) {
      var b = e.target.closest("button");
      if (!b) return;
      if (b.dataset.preset) applyPreset(b.dataset.preset);
      else if (b.dataset.key) set(b.dataset.key, b.dataset.val);
      else if (b.classList.contains("a11y-reset")) { state = {}; save(state); apply(state); render(); }
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", buildUi);
  } else {
    buildUi();
  }
})();
