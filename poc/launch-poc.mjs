/**
 * MAA-Checker P0 PoC：用"自研最小启动器"离线启动一个已安装的整合包实例，并盯日志判断是否进入主菜单。
 *
 * 目的（只为验证机制，不做产品化）：
 *   1) 实例 JSON 里的标准参数模板能否直接被我们替换并启动（不依赖 HMCL/PCL2 的 CLI）；
 *   2) 离线凭据（自造 uuid + --accessToken 0）能否正常进游戏；
 *   3) 客户端"进主菜单"的日志判据是什么、要多久；
 *   4) 我们启动的进程能否被干净地结束。
 *
 * 用法：
 *   node launch-poc.mjs [实例名] [--mx=3G] [--timeout=180]
 *   例：node launch-poc.mjs 1.21.1-Test --mx=3G
 *
 * 注意：只读实例目录；日志与命令记录写在 poc/logs/ 下；不改动 mods/config。
 */
import fs from 'node:fs';
import path from 'node:path';
import { spawn, spawnSync, execFileSync } from 'node:child_process';

const LAUNCHER_ROOT = 'D:/Minecraft/minecraft';
const GAME_ROOT = path.join(LAUNCHER_ROOT, '.minecraft');
const INSTANCE = process.argv[2] ?? '1.21.1-Test';
const MX = (process.argv.find((a) => a.startsWith('--mx=')) ?? '--mx=3G').slice(5);
const TIMEOUT_S = Number((process.argv.find((a) => a.startsWith('--timeout=')) ?? '--timeout=180').slice(10));
const POC_LOG_DIR = path.resolve('poc/logs');

// 进度标记（早期）与成功标记（后期）必须分开：实测 "Setting user:" 只有 20s，那时客户端还在构造 mod，
// 真正"到主菜单"的可靠信号是声音子系统/图集创建这些后期行。
const PROGRESS_MARKERS = ['Setting user:', 'Backend library:', 'LWJGL Version'];
const SUCCESS_MARKERS = [
  'Sound engine started',
  'OpenAL initialized',
  'Created: 1024x512x4 minecraft:textures/atlas/blocks.png',
];
const FAILURE_MARKERS = [
  'Crash report saved to',
  'Failed to start the minecraft server',
  'ModLoadingException',
  // 客户端（1.21.1 NeoForge 实测）模组加载失败的标志：
  'Mod loading failures have occurred',
  'ModLoadingCrashException',
  'Mod loading issue for:',
  'Loading errors encountered',
  'Missing or unsupported mandatory dependencies',
  'A mod crashed on startup',
  'A mod crashed',
  'Mixin apply failed',
  'Exception in thread "main"',
];

function log(...a) {
  const line = a.join(' ');
  console.log(line);
  fs.appendFileSync(path.join(POC_LOG_DIR, `${INSTANCE}-poc.log`), line + '\n');
}

function readJson(p) {
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

/**
 * 找出真正的"版本 JSON"。
 * 实例目录里还有 modrinth.index.json / patchouli_data.json 之类的干扰文件，
 * 盲取第一个 json 会抓错（实测：中文名实例的第一个 json 就是干扰项）。
 * 规则：优先 id 与目录名一致 → 其次有 mainClass + libraries → 都不满足则返回 null。
 */
function findVersionJson(instDir, instanceName) {
  const names = fs.readdirSync(instDir).filter((f) => f.endsWith('.json'));
  const parsed = [];
  for (const f of names) {
    try { parsed.push({ file: f, json: readJson(path.join(instDir, f)) }); } catch { /* 忽略 */ }
  }
  const byId = parsed.find((p) => p.json.id === instanceName);
  if (byId) return byId;
  return parsed.find((p) => p.json.mainClass && Array.isArray(p.json.libraries)) ?? null;
}

function ruleAllows(entry) {
  if (!entry.rules) return true;
  let allowed = false;
  for (const r of entry.rules) {
    const osName = r.os?.name;
    const matches = !osName || osName === 'windows' || osName === 'universal';
    if (matches) allowed = r.action === 'allow';
  }
  return allowed;
}

function pickJava(requiredMajor) {
  const candidates = [
    'C:/Program Files/Microsoft/jdk-21.0.7.6-hotspot/bin/java.exe',
    path.join(process.env.APPDATA ?? '', '.hmcl/java/windows-x86_64/mojang-java-runtime-delta/bin/java.exe'),
    'C:/Program Files/Microsoft/jdk-17.0.12.7-hotspot/bin/java.exe',
    'java',
  ];
  for (const c of candidates) {
    if (c !== 'java' && !fs.existsSync(c)) continue;
    // 注意：java -version 的输出在 stderr，且退出码为 0，必须两个流都看
    const r = spawnSync(c, ['-version'], { encoding: 'utf8' });
    const text = `${r.stderr ?? ''}${r.stdout ?? ''}`;
    const m = text.match(/version "(\d+)/);
    const major = m ? Number(m[1]) : 0;
    if (major >= requiredMajor) return { java: c, major, out: text.split('\n')[0].trim() };
  }
  return null;
}

function main() {
  fs.mkdirSync(POC_LOG_DIR, { recursive: true });
  const instDir = path.join(GAME_ROOT, 'versions', INSTANCE);
  if (!fs.existsSync(instDir)) throw new Error('实例目录不存在: ' + instDir);

  const picked = findVersionJson(instDir, INSTANCE);
  if (!picked) throw new Error('实例目录里找不到版本 JSON（含 mainClass + libraries 的那个）');
  const v = picked.json;
  const clientJarName = v.jar ?? picked.file.replace(/\.json$/, '');
  const requiredMajor = v.javaVersion?.majorVersion ?? 21;
  log(`=== 实例 ${INSTANCE} ===`);
  log(`版本JSON=${picked.file} mainClass=${v.mainClass} jar=${clientJarName} libraries=${(v.libraries ?? []).length} 要求 Java=${requiredMajor}`);

  // 1) natives：优先用实例里已解压的 natives-*
  const nativesDirName = fs.readdirSync(instDir).find((d) => d.startsWith('natives-') && fs.statSync(path.join(instDir, d)).isDirectory());
  if (!nativesDirName) throw new Error('未找到 natives-* 目录（请先用启动器启动过一次）');
  const nativesDir = path.join(instDir, nativesDirName);

  // 2) classpath：133 个库的 artifact.path + 客户端 jar
  const classpath = [
    ...(v.libraries ?? [])
      .filter((l) => l.downloads?.artifact?.path)
      .map((l) => path.join(GAME_ROOT, 'libraries', l.downloads.artifact.path)),
    path.join(instDir, `${clientJarName}.jar`),
  ].join(';');

  // 3) 离线凭据：优先复用 usercache 里的玩家名（保持与实例一致），否则造一个
  let playerName = 'MaaTester';
  let playerUuid = '00000000000040008000000000000000';
  const cache = path.join(instDir, 'usercache.json');
  if (fs.existsSync(cache)) {
    try {
      const list = readJson(cache);
      if (Array.isArray(list) && list[0]?.name) {
        playerName = list[0].name;
        playerUuid = (list[0].uuid ?? playerUuid).replace(/-/g, '');
      }
    } catch { /* 忽略 */ }
  }

  const vars = {
    natives_directory: nativesDir,
    launcher_name: 'MAA-Checker-PoC',
    launcher_version: '0.1',
    classpath,
    classpath_separator: ';',
    library_directory: path.join(GAME_ROOT, 'libraries'),
    version_name: v.id ?? INSTANCE,
    primary_jar_name: `${clientJarName}.jar`,
    game_directory: instDir,
    assets_root: path.join(GAME_ROOT, 'assets'),
    assets_index_name: v.assetIndex?.id ?? '17',
    auth_player_name: playerName,
    auth_uuid: playerUuid,
    auth_access_token: '0',
    user_type: 'legacy',
    version_type: 'release',
    resolution_width: '854',
    resolution_height: '480',
  };
  const subst = (s) => String(s).replace(/\$\{([a-z_]+)\}/g, (_, k) => (k in vars ? vars[k] : ''));

  // 4) 组装命令
  const jvmArgs = (v.arguments?.jvm ?? []).filter(ruleAllows).flatMap((e) => (typeof e === 'string' ? [e] : e.value)).map(subst);
  const gameArgs = (v.arguments?.game ?? []).map(subst).filter((a) => a !== '');
  const java = pickJava(requiredMajor);
  if (!java) throw new Error(`找不到 Java ${requiredMajor}+`);
  const cmd = [java.java, `-Xmx${MX}`, '-Xms1G', ...jvmArgs, v.mainClass, ...gameArgs];
  log(`Java: ${java.out} (major=${java.major})`);
  log(`命令行(脱敏): ${cmd.map((c) => (c === '0' && cmd[cmd.indexOf('--accessToken') + 1] === c ? '<token>' : c)).join(' ').slice(0, 1200)}`);

  // 5) 启动
  const outLog = path.join(POC_LOG_DIR, `${INSTANCE}-stdout.log`);
  const t0 = Date.now();
  const child = spawn(cmd[0], cmd.slice(1), { cwd: instDir, stdio: ['ignore', 'pipe', 'pipe'] });
  const outStream = fs.createWriteStream(outLog);
  child.stdout.pipe(outStream);
  child.stderr.pipe(outStream);
  log(`进程已启动 pid=${child.pid}，内存 -Xmx${MX}，超时 ${TIMEOUT_S}s；监视 ${path.join(instDir, 'logs/latest.log')}`);

  // 6) 盯日志判据
  const latestLog = path.join(instDir, 'logs', 'latest.log');
  // 用"内容锚点"定位本次新增：先记住旧日志的末尾片段，
  // 之后只认锚点之后的内容；锚点找不到（文件被重建/轮转）才把整份当新日志。
  // 比字节偏移与 birthtime 都稳（实测：浮点 birthtimeMs 在 Windows 上会抖动，导致把旧日志当新内容）。
  let anchor = null;
  if (fs.existsSync(latestLog)) {
    const txt = fs.readFileSync(latestLog, 'utf8');
    anchor = txt.length > 120 ? txt.slice(-120) : null;
    log(`（启动前 latest.log 已有 ${txt.length} 字节，使用末尾锚点识别本次新增内容）`);
  }
  let verdict = null;
  let evidence = '';
  const loggedProgress = new Set();
  const timer = setInterval(() => {
    if (verdict) return;
    let text = '';
    try { text = fs.readFileSync(latestLog, 'utf8'); } catch { return; }
    const at = anchor ? text.lastIndexOf(anchor) : -1;
    const fresh = at >= 0 ? text.slice(at + anchor.length) : text;
    for (const m of PROGRESS_MARKERS) {
      if (!loggedProgress.has(m) && fresh.includes(m)) {
        loggedProgress.add(m);
        log(`   …进度: "${m}"（启动后 ${((Date.now() - t0) / 1000).toFixed(1)}s）`);
      }
    }
    for (const m of FAILURE_MARKERS) {
      if (fresh.includes(m)) {
        verdict = 'FAIL';
        evidence = m;
        log(`❌ 命中崩溃判据: "${evidence}"（启动后 ${((Date.now() - t0) / 1000).toFixed(1)}s）`);
        // 实测：错误块（"Missing or unsupported mandatory dependencies:" 之后的 Mod ID 列表）
        // 会在同一秒内继续写出，检测到标志行时常常只拿到表头 → 等 2.5s 再一次性抓完整块。
        setTimeout(() => {
          captureDiagnosis();
          finish('FAIL');
        }, 2500);
      }
    }
    for (const m of SUCCESS_MARKERS) {
      if (!verdict && fresh.includes(m)) { verdict = 'PASS'; evidence = m; }
    }
    if (verdict === 'PASS') {
      log(`✅ 命中主菜单判据: "${evidence}"（启动后 ${((Date.now() - t0) / 1000).toFixed(1)}s）`);
      finish('PASS');
    } else if (!verdict && Date.now() - t0 > TIMEOUT_S * 1000) {
      log(`⏱️ ${TIMEOUT_S}s 内未命中任何判据（进程存活=${child.exitCode === null}）`);
      finish('TIMEOUT');
    }
  }, 500);

  let finished = false;

  /** 抓诊断素材：本次日志里的故障块 + 本次新产生的 crash-report（实测有的故障不落崩溃报告） */
  function captureDiagnosis() {
    const out = [];
    try {
      const text = fs.readFileSync(latestLog, 'utf8');
      const at = anchor ? text.lastIndexOf(anchor) : -1;
      const fresh = at >= 0 ? text.slice(at + anchor.length) : text;
      out.push('### latest.log 关键行');
      out.push(...fresh.split(/\r?\n/).filter((l) =>
        /Mod loading issue for:|Failure message:|Mod file:|Missing or unsupported|Mod ID:|Expected range:|Loading errors|Caused by:|Incompatible mods/.test(l)));
    } catch { /* ignore */ }
    const crashDir = path.join(instDir, 'crash-reports');
    if (fs.existsSync(crashDir)) {
      const freshReports = fs.readdirSync(crashDir)
        .map((f) => ({ f, t: fs.statSync(path.join(crashDir, f)).mtimeMs }))
        .filter((x) => x.t > t0)
        .sort((a, b) => b.t - a.t);
      if (freshReports.length) {
        out.push('### 本次新产生的 crash-report: ' + freshReports[0].f);
        out.push(fs.readFileSync(path.join(crashDir, freshReports[0].f), 'utf8').slice(0, 4000));
      } else {
        out.push('### 本次未产生 crash-report（该故障类型只写日志、不落崩溃报告）');
      }
    }
    const diagFile = path.join(POC_LOG_DIR, `${INSTANCE}-diagnosis.txt`);
    fs.writeFileSync(diagFile, out.join('\n') + '\n');
    log(`   ↳ 诊断素材: ${diagFile}（${out.length} 段）`);
  }
  function finish(result) {
    if (finished) return;
    finished = true;
    clearInterval(timer);
    // 进主菜单后让它多活 8s，确认不是刚起来就崩
    // 命中"到主菜单"后再多观察一会儿，确认不是刚进界面就崩
    const waitMs = result === 'PASS' ? 15000 : 0;
    setTimeout(() => {
      try { execFileSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' }); } catch { /* 已退出 */ }
      log(`结果=${result} 证据="${evidence}" 总耗时=${((Date.now() - t0) / 1000).toFixed(1)}s 已结束进程 pid=${child.pid}`);
      log(`客户端 stdout 落盘: ${outLog}`);
      process.exit(result === 'PASS' ? 0 : 1);
    }, waitMs);
  }

  child.on('exit', (code) => {
    if (!finished) {
      // 非 0 退出 = 启动失败（即使日志里没命中关键字）；0 = 玩家自己关掉或被我们结束
      const fail = code !== 0;
      log(fail
        ? `❌ 游戏进程非 0 退出 code=${code} → 判定启动失败，见 ${outLog}`
        : `⚠️ 游戏进程退出 code=${code}（未命中任何判据），见 ${outLog}`);
      finish(fail ? 'FAIL' : 'EXITED');
    }
  });
}

main();
