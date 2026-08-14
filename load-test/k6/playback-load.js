import { startAndEndPlayback, textSummary } from "./playback-common.js";

export { startDuration, startErrorRate } from "./playback-common.js";

export const options = {
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
  scenarios: {
    load: {
      executor: "constant-arrival-rate",
      rate: 500,
      timeUnit: "1s",
      duration: "2m",
      preAllocatedVUs: 250,
      maxVUs: 400,
    },
  },
};

export default function () {
  startAndEndPlayback();
}

export function handleSummary(data) {
  return {
    stdout: textSummary("Playback Gate Load 500 RPS", data),
  };
}
