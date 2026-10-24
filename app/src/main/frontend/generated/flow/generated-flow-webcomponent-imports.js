import { injectGlobalWebcomponentCss } from 'Frontend/generated/jar-resources/theme-util.js';

import { injectGlobalCss } from 'Frontend/generated/jar-resources/theme-util.js';

import { css, unsafeCSS, registerStyles } from '@vaadin/vaadin-themable-mixin';
import $cssFromFile_0 from '@vaadin/vaadin-lumo-styles/lumo.css?inline';
import '@vaadin/vertical-layout/src/vaadin-vertical-layout.js';
import '@vaadin/app-layout/src/vaadin-app-layout.js';
import '@vaadin/button/src/vaadin-button.js';
import '@vaadin/tooltip/src/vaadin-tooltip.js';
import 'Frontend/generated/jar-resources/disableOnClickFunctions.js';
import '@vaadin/horizontal-layout/src/vaadin-horizontal-layout.js';
import '@vaadin/grid/src/vaadin-grid.js';
import '@vaadin/grid/src/vaadin-grid-column.js';
import '@vaadin/grid/src/vaadin-grid-sorter.js';
import '@vaadin/checkbox/src/vaadin-checkbox.js';
import 'Frontend/generated/jar-resources/flow-component-renderer.js';
import 'Frontend/generated/jar-resources/flow-component-directive.js';
import 'Frontend/generated/jar-resources/vaadin-grid/gridConnector.ts';
import 'Frontend/generated/jar-resources/vaadin-grid/vaadin-grid-flow-selection-column.ts';
import '@vaadin/grid/src/vaadin-grid-column-group.js';
import 'Frontend/generated/jar-resources/lit-renderer.ts';
import '@vaadin/context-menu/src/vaadin-context-menu.js';
import 'Frontend/generated/jar-resources/vaadin-context-menu/contextMenuConnector.ts';
import 'Frontend/generated/jar-resources/vaadin-context-menu/contextMenuTargetConnector.ts';
import '@vaadin/notification/src/vaadin-notification.js';
import '@vaadin/dialog/src/vaadin-dialog.js';
import '@vaadin/text-field/src/vaadin-text-field.js';
import '@vaadin/combo-box/src/vaadin-combo-box.js';
import 'Frontend/generated/jar-resources/vaadin-combo-box/comboBoxConnector.ts';
import '@vaadin/multi-select-combo-box/src/vaadin-multi-select-combo-box.js';
import '@vaadin/common-frontend/ConnectionIndicator.js';
import 'Frontend/generated/jar-resources/ReactRouterOutletElement.tsx';

injectGlobalWebcomponentCss($cssFromFile_0.toString());
const loadOnDemand = (key) => {
  const pending = [];
  if (key === 'f88e503142382516e40f3c7644a20d18fc9ca25d3a39677b91147fd086404cc9') {
    pending.push(import('./chunks/chunk-d5809ba7c149827fbbabea6ceeedfbe8f3c3cb0c5fcd9e8836a4373497415222.js'));
  }
  if (key === '179286a09183acbb83c61c78075b2cd15cb0b3dc81c1059807fd6cb35fb3f1b9') {
    pending.push(import('./chunks/chunk-5945d5a2ba2fbe000150843e41b1a4477689fad47b549d097cc10bfeac291d15.js'));
  }
  if (key === 'e1627d8e74f541081b6bceac6d75f3bf6c648820a0d1c3ff87c594fb2ee78ffc') {
    pending.push(import('./chunks/chunk-60f2799bea41fe26519be1c498b5743c8df7d548d784a61da235425f843d70ee.js'));
  }
  return Promise.all(pending);
}
window.Vaadin = window.Vaadin || {};
window.Vaadin.Flow = window.Vaadin.Flow || {};
window.Vaadin.Flow.loadOnDemand = loadOnDemand;
window.Vaadin.Flow.resetFocus = () => {
 let ae=document.activeElement;
 while(ae&&ae.shadowRoot) ae = ae.shadowRoot.activeElement;
 return !ae || ae.blur() || ae.focus() || true;
}