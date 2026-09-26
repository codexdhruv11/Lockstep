// Runs the report template's own <script> against the DOM stub and prints what a reader
// would see. Usage: node render-report.js <template.html> <data.json>
const fs = require("fs");
require(process.argv[2]);                       // dom stub
const html = fs.readFileSync(process.argv[3], "utf8");
const data = fs.readFileSync(process.argv[4], "utf8");

// Extract the page's real inline script — the last <script> block with no src.
const scripts = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)]
  .map(m => m[1]);
const inline = scripts[scripts.length - 1];

global.document.getElementById("run-data").textContent = data;
eval(inline);

const out = {
  headline: global.__renderedText(global.__elements.get("verdict")),
  runners: global.__renderedText(global.__elements.get("runners")),
  spikes: global.__renderedText(global.__elements.get("spikes")),
  queries: global.__renderedText(global.__elements.get("queries")),
  failures: global.__renderedText(global.__elements.get("failures")),
  plans: global.__renderedText(global.__elements.get("plans")),
  slowlog: global.__renderedText(global.__elements.get("slowlog"))
    + " " + global.__renderedText(global.__elements.get("slowlog-note")),
  shortfall: global.__renderedText(global.__elements.get("shortfall")),
  footer: global.__renderedText(global.__elements.get("footer")),
  chartDatasets: (global.chartCalls[0] ? global.chartCalls[0].data.datasets : []).map(d => d.label),
};
console.log(JSON.stringify(out));
