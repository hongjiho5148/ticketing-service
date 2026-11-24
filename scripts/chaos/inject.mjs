// 컨테이너에 장애를 넣고, 정해진 시간 뒤에 원래대로 되돌린다. 장애가 시작되고 끝난 실제 시각을 기록한다.
//
//   node scripts/chaos/inject.mjs --target redis --mode stop --at 60 --for 30 --out chaos.json
//   node scripts/chaos/inject.mjs --target mysql --mode pause --at 5 --for 20 --dry-run
//
// mode:
//   stop  = docker stop 후 start. 정상 종료라서 Redis는 종료 전에 데이터를 디스크에 저장한다.
//   kill  = docker kill 후 start. 강제 종료라서 저장하지 못한 데이터를 잃는다. 이 PC의 Docker Desktop에서는 docker kill로
//           죽인 컨테이너를 재시작 정책(unless-stopped)이 있어도 되살리지 않는 것을 확인했다. 다만 Docker 버전과 환경에 따라
//           다를 수 있어서, 죽이는 동안만 재시작 정책을 끄고 끝나면 원래대로 돌려 "정해진 시간 동안 확실히 죽어 있게" 한다.
//           (프로세스가 스스로 죽고 Docker가 되살리는 "충돌 후 자동 복구"는 이 모드가 아니라 다른 시험이다.)
//   pause = docker pause 후 unpause. 프로세스는 살아 있는데 응답이 없는 상태(DB가 멈춘 것 같은 상황).
//
// 중간에 Ctrl+C로 끊거나 오류가 나도 finally와 신호 처리에서 반드시 되돌린다. 장애가 남아 있는 채로 끝나지 않게 하는 것이 이 스크립트의 가장 중요한 일이다.
import { writeFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";
import { TARGETS, containerOf, docker, iso, promQuery, sleep } from "./lib.mjs";

export const MODES = ["stop", "kill", "pause"];

/** 대상이 다시 일을 받을 수 있는 상태가 될 때까지 기다린다. 걸린 시간이 곧 복구 시간의 일부다. */
export async function waitReady(target, container, timeoutMs = 180_000) {
  const deadline = Date.now() + timeoutMs;
  const spec = TARGETS[target];
  while (Date.now() < deadline) {
    try {
      if (target === "redis") {
        const { out } = await docker(["exec", container, "redis-cli", "ping"], { allowFail: true });
        if (out.trim() === "PONG") return Date.now();
      } else if (target === "mysql") {
        const { code } = await docker(["exec", container, "mysqladmin", "ping", "-uroot", "-proot", "--silent"], { allowFail: true });
        if (code === 0) return Date.now();
      } else if (spec.promService || spec.promJob) {
        // 스프링 서비스는 컨테이너가 떠도 한참 뒤에야 요청을 받는다. Prometheus가 수집에 성공하면 준비된 것으로 본다.
        const selector = spec.promJob ? `job="${spec.promJob}"` : `service="${spec.promService}"`;
        const result = await promQuery(`up{${selector}}`);
        if (result.length > 0 && result.every((r) => r.value[1] === "1")) return Date.now();
      } else {
        const { out } = await docker(["inspect", "-f", "{{.State.Status}}", container]);
        if (out.trim() === "running") return Date.now();
      }
    } catch {
      // 아직 준비 전이다. 계속 기다린다.
    }
    await sleep(2000);
  }
  throw new Error(`${target}가 ${timeoutMs / 1000}초 안에 준비되지 않았습니다`);
}

/**
 * 장애를 넣었다가 되돌린다. 기록(각 시각은 호스트 시계 기준 epoch ms)을 돌려준다.
 * atSec 초를 기다렸다가 장애를 넣고, forSec 초 뒤에 되돌린 다음 준비될 때까지 기다린다.
 */
export async function runFault({ target, mode, atSec = 0, forSec = 30, dryRun = false, log = console.log }) {
  if (!TARGETS[target]) throw new Error(`알 수 없는 대상: ${target} (가능: ${Object.keys(TARGETS).join(", ")})`);
  if (!MODES.includes(mode)) throw new Error(`알 수 없는 방식: ${mode} (가능: ${MODES.join(", ")})`);

  const record = { target, mode, atSec, forSec, dryRun };
  if (dryRun) {
    log(`[계획] ${atSec}초 뒤 ${target}를 ${mode}, ${forSec}초 동안 유지한 뒤 복구합니다. (실제로는 아무것도 하지 않음)`);
    return record;
  }

  const container = await containerOf(target);
  record.container = container;
  const originalPolicy =
    (await docker(["inspect", "-f", "{{.HostConfig.RestartPolicy.Name}}", container])).out.trim() || "no";
  record.originalRestartPolicy = originalPolicy;

  // touched: 컨테이너 상태를 한 번이라도 건드렸는가. 건드렸다면 어떤 이유로 끝나든 복구해야 한다.
  let touched = false;
  let restorePromise;
  // 복구는 한 번만 실행하고, 두 번째 호출(신호와 finally가 겹칠 때)은 같은 작업이 끝나기를 기다린다.
  const restore = () => {
    if (!touched) return Promise.resolve();
    restorePromise ??= (async () => {
      record.restoreStartMs = Date.now();
      log(`[복구] ${container} (${mode} 해제)`);
      if (mode === "pause") {
        await docker(["unpause", container], { allowFail: true });
      } else {
        await docker(["start", container], { allowFail: true });
        if (mode === "kill") {
          await docker(["update", `--restart=${originalPolicy}`, container], { allowFail: true });
        }
      }
      record.restoredMs = Date.now();
    })();
    return restorePromise;
  };

  const onSignal = async (signal) => {
    log(`[중단] ${signal} 받음. 장애를 되돌립니다.`);
    await restore();
    process.exit(130);
  };
  process.once("SIGINT", onSignal);
  process.once("SIGTERM", onSignal);

  try {
    record.scheduledStartMs = Date.now() + atSec * 1000;
    await sleep(atSec * 1000);

    log(`[장애] ${target}(${container}) ${mode} 시작`);
    record.faultStartMs = Date.now();
    touched = true;
    if (mode === "pause") {
      await docker(["pause", container]);
    } else if (mode === "kill") {
      await docker(["update", "--restart=no", container]);
      await docker(["kill", container]);
    } else {
      await docker(["stop", container]);
    }
    record.faultAppliedMs = Date.now();

    // 적용이 끝난 시각부터 forSec 동안 장애를 유지한다.
    await sleep(forSec * 1000);
  } finally {
    await restore();
    process.removeListener("SIGINT", onSignal);
    process.removeListener("SIGTERM", onSignal);
  }

  record.readyMs = await waitReady(target, container);
  log(`[준비됨] ${target} 요청을 받을 수 있는 상태 (복구 시작 후 ${((record.readyMs - record.restoreStartMs) / 1000).toFixed(1)}초)`);
  return record;
}

async function main() {
  const { values } = parseArgs({
    options: {
      target: { type: "string" },
      mode: { type: "string", default: "stop" },
      at: { type: "string", default: "0" },
      for: { type: "string", default: "30" },
      out: { type: "string" },
      "dry-run": { type: "boolean", default: false },
    },
  });
  if (!values.target) {
    console.error("사용법: node scripts/chaos/inject.mjs --target <redis|mysql|kafka|...> --mode <stop|kill|pause> --at <초> --for <초> [--out 파일] [--dry-run]");
    process.exit(2);
  }
  const record = await runFault({
    target: values.target,
    mode: values.mode,
    atSec: Number(values.at),
    forSec: Number(values.for),
    dryRun: values["dry-run"],
  });
  const fmt = Object.fromEntries(Object.entries(record).map(([k, v]) => [k, k.endsWith("Ms") ? `${v} (${iso(v)})` : v]));
  console.log(JSON.stringify(fmt, null, 2));
  if (values.out) writeFileSync(values.out, JSON.stringify(record, null, 2));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((e) => {
    console.error(e.message);
    process.exit(1);
  });
}
