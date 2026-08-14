package com.playbackgate.playback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PlaybackStartRequest(
        @NotNull Long contentId,
        @NotBlank String deviceId
) {
}
