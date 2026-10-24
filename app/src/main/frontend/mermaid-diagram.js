import mermaid from 'mermaid';

class SentryMermaid extends HTMLElement {
  constructor() {
    super();
    this.scale = 1;
    this.counter = 0;
    this.attachShadow({ mode: 'open' });
    this.shadowRoot.innerHTML = `
      <style>
        :host { display: block; position: relative; overflow: auto; border: 1px solid var(--lumo-contrast-20pct, #ccc); border-radius: 8px; min-height: 12rem; background: var(--lumo-base-color, #fff); }
        .stage { transform-origin: 0 0; padding: 1rem; display: inline-block; }
        .message { padding: 1rem; color: var(--lumo-secondary-text-color, #555); }
        .node { cursor: pointer; }
        .stage svg g.node text, .stage svg g.node tspan { fill: #1b2330 !important; }
        .node:focus { outline: 3px solid var(--lumo-primary-color, #1a73e8); }
      </style>
      <div class="message" role="status"></div>
      <div class="stage" tabindex="0" aria-label="Change diagram"></div>`;
  }

  async render(source) {
    const stage = this.shadowRoot.querySelector('.stage');
    const message = this.shadowRoot.querySelector('.message');
    try {
      const dark = document.documentElement.getAttribute('theme') === 'dark';
      mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: dark ? 'dark' : 'default', flowchart: { htmlLabels: false }, maxTextSize: 20000 });
      await mermaid.parse(source);
      const { svg } = await mermaid.render('sentry-diagram-' + (++this.counter), source);
      stage.innerHTML = svg;
      message.textContent = '';
      this.lastSvg = svg;
      this.applyScale();
      stage.querySelectorAll('g.node').forEach((node) => {
        node.setAttribute('tabindex', '0');
        node.setAttribute('role', 'button');
        const pick = () => {
          const match = /flowchart-(n\d+)-/.exec(node.id);
          if (match) {
            this.dispatchEvent(new CustomEvent('node-selected', { detail: { id: match[1] } }));
          }
        };
        node.addEventListener('click', pick);
        node.addEventListener('keydown', (event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); pick(); } });
      });
      return true;
    } catch (problem) {
      stage.innerHTML = '';
      message.textContent = 'The diagram could not be drawn. The previous one is kept if there was one. ' + (problem && problem.message ? problem.message.split('\n')[0] : '');
      if (this.lastSvg) {
        stage.innerHTML = this.lastSvg;
      }
      return false;
    }
  }

  applyScale() {
    this.shadowRoot.querySelector('.stage').style.transform = 'scale(' + this.scale + ')';
  }

  zoom(delta) {
    this.scale = Math.min(3, Math.max(0.3, this.scale + delta));
    this.applyScale();
  }

  fit() {
    const svg = this.shadowRoot.querySelector('.stage svg');
    if (!svg) { return; }
    const width = svg.getBoundingClientRect().width / this.scale;
    this.scale = width > 0 ? Math.min(1.5, Math.max(0.3, (this.clientWidth - 40) / width)) : 1;
    this.applyScale();
  }

  svgText() {
    return this.lastSvg || '';
  }
}

if (!customElements.get('sentry-mermaid')) {
  customElements.define('sentry-mermaid', SentryMermaid);
}
