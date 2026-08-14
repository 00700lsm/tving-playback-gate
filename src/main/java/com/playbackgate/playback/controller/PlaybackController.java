package com.playbackgate.playback.controller;

import com.playbackgate.playback.dto.PlaybackStartRequest;
import com.playbackgate.playback.dto.PlaybackStartResponse;
import com.playbackgate.playback.service.PlaybackService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/playback/sessions")
public class PlaybackController {

    private final PlaybackService playbackService;

    public PlaybackController(PlaybackService playbackService) {
        this.playbackService = playbackService;
    }

    @PostMapping
    public PlaybackStartResponse start(
            @AuthenticationPrincipal Long memberId,
            @Valid @RequestBody PlaybackStartRequest request
    ) {
        return playbackService.start(memberId, request.contentId(), request.deviceId());
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void end(
            @AuthenticationPrincipal Long memberId,
            @PathVariable String sessionId
    ) {
        playbackService.end(memberId, sessionId);
    }
}
