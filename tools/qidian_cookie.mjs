// 通用「过 JS 挑战页拿 cookie」脚本（默认用于起点 probe.js，也可跑番茄）。
// 起点 PC 站首次访问返回 202 + probe.js，浏览器跑完脚本才下发 cookie；
// 番茄连续抓多章后会掉进「验证码中间页」，同样要浏览器跑一次才发 cookie。
//
//   node tools/qidian_cookie.mjs                                   # 默认起点
//   PROBE_URL=https://fanqienovel.com/ COOKIE_OUT=... DOMAIN=fanqienovel.com \
//     node tools/qidian_cookie.mjs                                 # 番茄
import { spawn } from "node:child_process";
import { writeFileSync, mkdirSync, rmSync } from "node:fs";
import { setTimeout as sleep } from "node:timers/promises";

const EDGE = process.env.EDGE_PATH ||
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const PORT = Number(process.env.CDP_PORT || 9333);
const TARGET = process.env.PROBE_URL || "https://www.qidian.com/all/";
const OUT = process.env.COOKIE_OUT || "tools/_probe/cookies.json";
const PROFILE = process.env.PROFILE_DIR || "tools/_probe/edge-cdp";
const DOMAIN = (process.env.DOMAIN || "qidian.com").split(",").map(s => s.trim());

rmSync(PROFILE, { recursive: true, force: true });
mkdirSync("tools/_probe", { recursive: true });
mkdirSync(OUT.replace(/[/\\][^/\\]+$/, ""), { recursive: true });

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
    } catch { /* 浏览器还没起来 */ }
    await sleep(250);
  }
  throw new Error("CDP 未就绪");
}

const page = await findPage();
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const pending = new Map();
const events = [];

ws.addEventListener("message", (e) => {
  const msg = JSON.parse(e.data);
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
  } else if (msg.method) {
    events.push(msg.method);
  }
});

const send = (method, params = {}) => new Promise((resolve, reject) => {
  const mid = ++id;
  pending.set(mid, { resolve, reject });
  ws.send(JSON.stringify({ id: mid, method, params }));
});

await new Promise(r => ws.addEventListener("open", r, { once: true }));
await send("Network.enable");
await send("Page.enable");
await send("Page.navigate", { url: TARGET });

// 等 load + 给 probe.js 的执行和二次跳转留时间
for (let i = 0; i < 60; i++) {
  if (events.includes("Page.loadEventFired")) break;
  await sleep(250);
}
await sleep(7000);

const { cookies } = await send("Network.getAllCookies");
const wanted = cookies.filter(c => DOMAIN.some(d => c.domain.includes(d)));
const text = await send("Runtime.evaluate", {
  expression: "document.title + ' | ' + document.body.innerText.slice(0,80)",
  returnByValue: true,
});

writeFileSync(OUT, JSON.stringify({
  url: TARGET,
  title: text?.result?.value || "",
  cookieHeader: wanted.map(c => `${c.name}=${c.value}`).join("; "),
  cookies: wanted,
}, null, 2), "utf8");

console.log("cookies:", wanted.length, "->", OUT);
console.log("page:", text?.result?.value);
ws.close();
child.kill();
process.exit(0);
