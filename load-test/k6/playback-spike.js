import { startAndEndPlayback, textSummary } from "./playback-common.js";

export { startDuration, startErrorRate } from "./playback-common.js";

export const options = {
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
  scenarios: {
    spike: {
      executor: "ramping-arrival-rate",
      startRate: 100,
      timeUnit: "1s",
      preAllocatedVUs: 600,
      maxVUs: 1200,
      stages: [
        { duration: "20s", target: 500 },
        { duration: "1m", target: 500 },
        { duration: "10s", target: 5000 },
        { duration: "30s", target: 5000 },
        { duration: "10s", target: 500 },
        { duration: "1m", target: 500 },
        { duration: "15s", target: 0 },
      ],
    },
  },
};

export default function () {
  startAndEndPlayback();
}

export function handleSummary(data) {
  return {
    stdout: textSummary("Playback Gate Spike", data),
  };
}
