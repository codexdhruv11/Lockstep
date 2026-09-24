// A DOM stub just large enough to execute the report template's script under node.
// It is not a browser: it records what the script produced so a test can assert on the
// rendered text, which string-matching the HTML file cannot do.
const elements = new Map();

function makeElement(tag) {
  return {
    tagName: (tag || "div").toUpperCase(),
    textContent: "",
    className: "",
    hidden: false,
    children: [],
    style: {},
    appendChild(child) { this.children.push(child); return child; },
    getContext() { return stubCanvasContext; },
  };
}

const stubCanvasContext = {
  save() {}, restore() {}, fillRect() {}, fillText() {},
  set fillStyle(v) {}, set font(v) {},
};

function renderedText(el) {
  if (!el) return "";
  return [el.textContent || "", ...el.children.map(renderedText)].join(" ").trim();
}

global.document = {
  getElementById(id) {
    if (!elements.has(id)) elements.set(id, makeElement("div"));
    return elements.get(id);
  },
  createElement: makeElement,
};
// Chart.js is replaced by a recorder: the test asserts on the data it was handed,
// not on pixels.
global.chartCalls = [];
global.Chart = function (canvas, config) { global.chartCalls.push(config); };
global.__elements = elements;
global.__renderedText = renderedText;
