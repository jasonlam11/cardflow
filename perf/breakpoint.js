// Breaking point: raise the arrival rate step by step until p95 > 200 ms or errors > 1%,
// then stop. The last rate that held is the maximum sustainable throughput on this machine.
import exec from "k6/execution";
import { charge, createCards } from "./lib.js";

const MAX = Number(__ENV.MAX_RATE || 600);

export const options = {
  setupTimeout: "5m",
  scenarios: {
    ramp: {
      executor: "ramping-arrival-rate",
      startRate: 50,
      timeUnit: "1s",
      preAllocatedVUs: 100,
      maxVUs: 1000,
      stages: [
        { duration: "30s", target: 100 },
        { duration: "30s", target: 200 },
        { duration: "30s", target: 300 },
        { duration: "30s", target: 400 },
        { duration: "30s", target: MAX },
        { duration: "30s", target: MAX },
      ],
    },
  },
  thresholds: {
    "http_req_duration{name:authorize}": [{ threshold: "p(95)<200", abortOnFail: true, delayAbortEval: "15s" }],
    "http_req_failed{name:authorize}": [{ threshold: "rate<0.01", abortOnFail: true, delayAbortEval: "15s" }],
  },
  summaryTrendStats: ["avg", "med", "p(95)", "p(99)", "max"],
};

export function setup() {
  return { cards: createCards(), runId: `bp${Date.now()}` };
}

export default function (data) {
  charge(data.cards, exec.scenario.iterationInTest, data.runId);
}
