import { startAndEndPlayback, textSummary } from "./playback-common.js";

export { startDuration, startErrorRate } from "./playback-common.js";

export const options = {
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
  scenarios: {
    stress: {
      executor: "ramping-arrival-rate",
      startRate: 100,
      timeUnit: "1s",
      preAllocatedVUs: 500,
      maxVUs: 1000,
      stages: [
        { duration: "20s", target: 500 },
        { duration: "45s", target: 500 },
        { duration: "15s", target: 1000 },
        { duration: "45s", target: 1000 },
        { duration: "15s", target: 2000 },
        { duration: "45s", target: 2000 },
        { duration: "15s", target: 3000 },
        { duration: "30s", target: 3000 },
        { duration: "15s", target: 5000 },
        { duration: "30s", target: 5000 },
        { duration: "20s", target: 0 },
      ],
    },
  },
};

export default function () {
  startAndEndPlayback();
}

export function handleSummary(data) {
  return {
    stdout: textSummary("Playback Gate Stress", data),
  };
}
