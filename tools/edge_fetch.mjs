// 用 headless Edge 直接取渲染后的 HTML（兜底：页面被 JS 挑战页拦住时）。
//
//   URL=https://fanqienovel.com/reader/xxx OUT=tools/_probe/page.html node tools/edge_fetch.mjs
//
// 等 window.__INITIAL_STATE__ 出现（或超时），再把 documentElement.outerHTML 写文件。
import { spawn } from "node:child_process";
import { writeFileSync, mkdirSync, rmSync } from "node:fs";
import { dirname } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";

const EDGE = process.env.EDGE_PATH ||
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const PORT = Number(process.env.CDP_PORT || 9344);
const URL_ = process.env.URL;
const OUT = process.env.OUT || "tools/_probe/edge_fetch.html";
const PROFILE = process.env.PROFILE_DIR || "tools/_probe/edge-fetch-profile";
const WAIT_MS = Number(process.env.WAIT_MS || 20000);

if (!URL_) {
  console.error("缺少 URL 环境变量");
  process.exit(2);
}
rmSync(PROFILE, { recursive: true, force: true });
mkdirSync(dirname(OUT), { recursive: true });

const child = spawn(EDGE, [
  "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
  `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  "--window-size=1366,900", "about:blank",
], { stdio: "ignore" });

async function findPage() {
  for (let i = 0; i < 40; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/list`);
      const list = await r.json();
      const page = list.find(t => t.type === "page");
      if (page?.webSocketDebuggerUrl) return page;
    } catch { /* 还没起来 */ }
    await sleep(250);
  }
  throw new Error("CDP 未就绪");
}

const page = await findPage();
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const pending = new Map();
ws.addEventListener("message", (e) => {
  const msg = JSON.parse(e.data);
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
  }
});
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const mid = ++id;
  pending.set(mid, { resolve, reject });
  ws.send(JSON.stringify({ id: mid, method, params }));
});

await new Promise(r => ws.addEventListener("open", r, { once: true }));
await send("Page.enable");
await send("Runtime.enable");
await send("Page.navigate", { url: URL_ });

const deadline = Date.now() + WAIT_MS;
let ready = false;
while (Date.now() < deadline) {
  await sleep(600);
  try {
    const r = await send("Runtime.evaluate", {
      expression: "!!window.__INITIAL_STATE__",
      returnByValue: true,
    });
    if (r?.result?.value === true) { ready = true; break; }
  } catch { /* 导航中 */ }
}
await sleep(500);

const dom = await send("Runtime.evaluate", {
  expression: "document.documentElement.outerHTML",
  returnByValue: true,
});
writeFileSync(OUT, dom?.result?.value || "", "utf8");
console.log(JSON.stringify({ ready, bytes: (dom?.result?.value || "").length, out: OUT }));
ws.close();
child.kill();
process.exit(ready ? 0 : 3);
