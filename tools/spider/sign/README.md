# 签名与「全量书目」：能拿什么、拿不到什么

## 一、结论先说

| 目标 | 免签名能不能做到 | 说明 |
|------|------------------|------|
| 起点「全部书籍信息」（书名/作者/分类/状态/简介/封面） | ✅ **能** | `/all/` 137 个筛选项 × 50 页 × 20 本 ≈ 13.7 万本上限；只需通过一次 `probe.js`（cookie），不需要 App 签名 |
| 番茄「全部书籍信息」 | ⚠️ **只能拿榜单 26 本** | web 端没有列书/搜索接口；要更多必须走 App 的**签名接口** |
| 番茄正文 | ✅ 免费章能拿 | `/reader/{id}` 的 SSR + 字体反混淆（见 `docs/全站爬取.md`） |
| 番茄付费章 | ❌ | 服务端按账号权限下发，签名解决不了授权 |

**签名解决的是「接口准入」，不是「内容授权」。** 这一点必须先认清，否则逆向半个月也只是把 403 换成 200 的付费墙。

## 二、两站的准入机制

### 起点（阅文）

- **Web**：`202 + probe.js` 指纹页 → 浏览器跑完拿 cookie → 之后都是普通 GET。
  项目里用 headless Edge（PC）/ 隐藏 WebView（手机）过一次即可，**没有密码学签名**。
- **App / ajax 接口**：带 `cookie + _csrfToken`，部分接口还要 `X-Request-Id` 与请求体加密；
  这些都能从客户端流量里观察，但同样**不改变内容授权**。

所以起点根本不需要「伪造签名」就能拿到全量书目——`catalog_full.py` 干的就是这件事。

### 番茄（字节）

App 接口（`api5-normal-sinfonlineb.fqnovel.com` 等）要求一组头：

| 头 | 内容 |
|----|------|
| `X-Argus` | 分段信封：`0x01 + b64(设备参数密文) + 0x02 + b64(时间/随机段) + 0x03 + b64(签名段)` |
| `X-Gorgon` | `8400` + 20 字节定长（时间 + cookie/params 摘要） |
| `X-Khronos` | 秒级时间戳 |
| `X-Helios` / `X-Ladon` | 更后的补充头，按接口启用 |

这些由 **secsdk（`libsign.so`）** 在 native 层算出：设备指纹 + 时间 + 随机数 → AES/SM3 → 定长输出，
**随版本变化**。所以纯 Python 复刻不现实，工程上只有两条路：

1. **设备侧调用**（推荐）：从 APK 抽 `libsign.so`，真机/Termux 里 `ctypes`/JNI 调它，
   把「参数 → 头」包成一个外部命令；
2. **逆向重写**：把 so 反汇编翻成等价算法，版本升级跟着改（维护成本高）。

## 三、本仓库给的骨架

```
tools/spider/sign/
├── envelope.py   # Argus 三段信封 / Gorgon 定长头 / Khronos / query 规范化（确定的部分，可测）
├── cipher.py     # 可插拔加密：AES-CBC（真）+ Xor/HMAC（联调桩，服务器不认）
├── secsdk.py     # 签名器契约：StubSigner（本地桩）/ ExternalCommandSigner（交给真机）
├── verify.py     # 探针：拿返回的 code/message 判断「服务器认不认」
└── selftest.py   # python -m tools.spider.sign.selftest（全绿）
```

设计原则：**把「能确定验证的部分」和「必须依赖设备的部分」切开**。
信封格式、参数排序、头名、错误码判定都可以单测；加密核心永远从外部注入，
换签名器只改一行命令。

### 接一个真机签名器

```bash
# 1) 抽 so（需要已装 App 的设备 / APK）
adb shell "run-as com.dragon.read ls lib/"      # 或直接从 APK 解压 lib/arm64-v8a/libsign.so
adb pull /data/app/.../lib/arm64-v8a/libsign.so .

# 2) 写个包装（示例：Termux 里用 ctypes 调导出函数，导出名随版本变化）
cat > ~/signer.py <<'EOF'
import ctypes, json, sys
lib = ctypes.CDLL("/data/data/com.termux/files/home/libsign.so")
req = json.load(sys.stdin)
# 具体入口按 so 的导出表来：常见是 Java_* 或 sign/doCommand
out = lib.sign(...)
print(json.dumps({"X-Argus": ..., "X-Gorgon": ..., "X-Khronos": ...}))
EOF

# 3) 接到爬虫
python -m tools.spider.sign.verify --url "https://api5-normal-sinfonlineb.fqnovel.com/reading/reader/full?item_id=..." \
  --signer "python $HOME/signer.py"
```

`verify.py` 的输出会告诉你：无签名 / 桩签名 / 真机签名 三种情况下 `code` 分别是什么——
**只有 `code == 0` 才算过**，别拿「HTTP 200」当成功（字节系的失败也是 200）。

## 四、全量书目怎么跑

```bash
# 起点：137 个筛选取全（断点续跑，已完成的页会跳过）
python -m tools.spider.catalog_full --source qidian

# 小样
python -m tools.spider.catalog_full --source qidian --filters 4 --pages 2

# 番茄：榜单 26 本 + 官方三级分类
python -m tools.spider.catalog_full --source fanqie
```

实测（本机，2026-10）：

| 项 | 数值 |
|----|------|
| 起点筛选项 | **135–137 个**（随站点导航浮动） |
| 每筛选上限 | 50 页 × 20 本 = **1000 本** |
| 理论上限 | ≈ **13.5 万本**（去重后） |
| 抓取速度 | ≈ 0.45 s/页 → 全部跑完约 40–50 分钟 |
| 番茄 | **26 本**（榜单上限，站点侧的硬限制） |

抓完的结果直接进 `data/library.db` 的 `books` 表，`serve.py` 的 `/api/books` 就能分页浏览，
App 的「本地爬虫库」可以直接读。

## 五、边界（别自欺）

- 签名过了 ≠ 能拿到 VIP 正文：内容按账号订阅下发，接口层面没有「绕过」的余地；
- 番茄的「全部书籍」在 web 侧不存在入口，App 侧即使签名通过，也只能按榜单/推荐/搜索逐页拿，
  拿不到「一次列全站」的接口——这是产品结构，不是技术限制；
- 站点会按 IP / 设备限速，签名正确也会 403/验证码；
- 本项目把这些都当**本地测试夹具**处理，产出的数据只用于自用阅读工具。
