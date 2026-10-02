// Steady load: a fixed arrival rate, regardless of how fast responses come back.
//   RATE (req/s, default 100), DURATION (default 2m), CARDS (default 500)
import exec from "k6/execution";
import { charge, createCards } from "./lib.js";

export const options = {
  setupTimeout: "5m",
  scenarios: {
    steady: {
      executor: "constant-arrival-rate",
      rate: Number(__ENV.RATE || 100),
      timeUnit: "1s",
      duration: __ENV.DURATION || "2m",
      preAllocatedVUs: 50,
      maxVUs: 400,
    },
  },
  thresholds: {
    "http_req_duration{name:authorize}": ["p(95)<200"],
    "http_req_failed{name:authorize}": ["rate<0.01"],
    checks: ["rate>0.99"],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

export function setup() {
  return { cards: createCards(), runId: `${Date.now()}` };
}

export default function (data) {
  charge(data.cards, exec.scenario.iterationInTest, data.runId);
}
