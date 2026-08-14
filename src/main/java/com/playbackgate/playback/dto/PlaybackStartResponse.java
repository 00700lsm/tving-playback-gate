package com.playbackgate.playback.dto;

import java.time.OffsetDateTime;

public record PlaybackStartResponse(
        String sessionId,
        String playbackToken,
        OffsetDateTime expiresAt
) {
}
