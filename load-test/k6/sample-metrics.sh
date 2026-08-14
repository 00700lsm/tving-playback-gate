#!/usr/bin/env bash
set -euo pipefail
OUT="${1:?usage: sample-metrics.sh outfile}"
INTERVAL="${2:-5}"
{
  echo "# ts cpu sys hikari_active hikari_pending hikari_acquire_count hikari_acquire_sum gc_count heap_old cache_content_hit cache_member_hit"
  while true; do
    ts=$(date +%s)
    m=$(curl -s http://localhost:8080/actuator/prometheus)
    cpu=$(echo "$m" | awk '/^process_cpu_usage\{/{print $2}')
    sys=$(echo "$m" | awk '/^system_cpu_usage\{/{print $2}')
    active=$(echo "$m" | awk '/^hikaricp_connections_active\{/{print $2}')
    pending=$(echo "$m" | awk '/^hikaricp_connections_pending\{/{print $2}')
    acq_c=$(echo "$m" | awk '/^hikaricp_connections_acquire_seconds_count\{/{print $2}')
    acq_s=$(echo "$m" | awk '/^hikaricp_connections_acquire_seconds_sum\{/{print $2}')
    gc=$(echo "$m" | awk '/^jvm_gc_pause_seconds_count\{/{s+=$2} END{print s+0}')
    heap=$(echo "$m" | awk '/^jvm_memory_used_bytes\{.*id="G1 Old Gen"/{print $2}')
    ch=$(echo "$m" | awk '/cache="contents".*result="hit"/{print $2}')
    mh=$(echo "$m" | awk '/cache="members".*result="hit"/{print $2}')
    echo "$ts $cpu $sys $active $pending $acq_c $acq_s $gc $heap $ch $mh"
    sleep "$INTERVAL"
  done
} >> "$OUT"
