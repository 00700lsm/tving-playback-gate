package com.playbackgate.playback.repository;

import com.playbackgate.playback.domain.PlaybackSession;
import com.playbackgate.playback.domain.PlaybackSessionStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaybackSessionRepository extends JpaRepository<PlaybackSession, Long> {

    long countByMember_IdAndStatusAndExpiresAtGreaterThanEqual(
            Long memberId,
            PlaybackSessionStatus status,
            LocalDateTime now
    );

    Optional<PlaybackSession> findBySessionId(String sessionId);
}
