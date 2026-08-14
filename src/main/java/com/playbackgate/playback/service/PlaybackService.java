package com.playbackgate.playback.service;

import com.playbackgate.auth.JwtProvider;
import com.playbackgate.common.config.TimeConfig;
import com.playbackgate.common.exception.BusinessException;
import com.playbackgate.common.exception.ErrorCode;
import com.playbackgate.content.domain.Content;
import com.playbackgate.member.domain.Member;
import com.playbackgate.playback.domain.PlaybackSession;
import com.playbackgate.playback.domain.PlaybackSessionStatus;
import com.playbackgate.playback.dto.PlaybackStartResponse;
import com.playbackgate.playback.repository.PlaybackSessionRepository;
import com.playbackgate.subscription.domain.Subscription;
import com.playbackgate.subscription.domain.SubscriptionStatus;
import com.playbackgate.subscription.repository.SubscriptionRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaybackService {

    private final CachedPlaybackReads cachedPlaybackReads;
    private final SubscriptionRepository subscriptionRepository;
    private final PlaybackSessionRepository playbackSessionRepository;
    private final JwtProvider jwtProvider;
    private final Clock clock;

    public PlaybackService(
            CachedPlaybackReads cachedPlaybackReads,
            SubscriptionRepository subscriptionRepository,
            PlaybackSessionRepository playbackSessionRepository,
            JwtProvider jwtProvider,
            Clock clock
    ) {
        this.cachedPlaybackReads = cachedPlaybackReads;
        this.subscriptionRepository = subscriptionRepository;
        this.playbackSessionRepository = playbackSessionRepository;
        this.jwtProvider = jwtProvider;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PlaybackStartResponse start(Long memberId, Long contentId, String deviceId) {
        LocalDateTime now = LocalDateTime.now(clock);

        Member member = cachedPlaybackReads.findMember(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (!member.isActive()) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_ACTIVE);
        }

        Content content = cachedPlaybackReads.findContent(contentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_NOT_FOUND));
        if (!content.isOpen()) {
            throw new BusinessException(ErrorCode.CONTENT_NOT_AVAILABLE);
        }
        if (!content.isWithinAvailability(now)) {
            throw new BusinessException(ErrorCode.CONTENT_NOT_AVAILABLE);
        }

        Subscription subscription = subscriptionRepository.findLatestByMemberIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        validateSubscription(subscription, now);

        if (!subscription.getPlan().covers(content.getRequiredPlan())) {
            throw new BusinessException(ErrorCode.PLAN_NOT_ALLOWED);
        }

        if (member.ageAt(LocalDate.now(clock)) < content.getAgeRating()) {
            throw new BusinessException(ErrorCode.AGE_RESTRICTED);
        }

        long activeSessionCount = playbackSessionRepository.countByMember_IdAndStatusAndExpiresAtGreaterThanEqual(
                memberId,
                PlaybackSessionStatus.ACTIVE,
                now
        );
        if (activeSessionCount >= subscription.getPlan().getMaxConcurrentPlayback()) {
            throw new BusinessException(ErrorCode.CONCURRENT_PLAYBACK_LIMIT_EXCEEDED);
        }

        PlaybackSession session = playbackSessionRepository.save(
                PlaybackSession.start(member, content, deviceId, now)
        );
        String playbackToken = jwtProvider.createPlaybackToken(
                memberId,
                content.getId(),
                session.getSessionId(),
                deviceId,
                session.getExpiresAt().atZone(TimeConfig.ZONE_ID).toInstant()
        );
        return new PlaybackStartResponse(
                session.getSessionId(),
                playbackToken,
                toOffsetDateTime(session.getExpiresAt())
        );
    }

    @Transactional
    public void end(Long memberId, String sessionId) {
        PlaybackSession session = playbackSessionRepository.findBySessionId(sessionId)
                .filter(found -> found.belongsTo(memberId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAYBACK_SESSION_NOT_FOUND));
        if (session.getStatus() == PlaybackSessionStatus.ENDED) {
            return;
        }
        session.end(LocalDateTime.now(clock));
    }

    private void validateSubscription(Subscription subscription, LocalDateTime now) {
        if (subscription.getStatus() == SubscriptionStatus.SUSPENDED) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_NOT_ACTIVE);
        }
        if (subscription.getStatus() == SubscriptionStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_EXPIRED);
        }
        if (now.isBefore(subscription.getStartedAt())) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_NOT_ACTIVE);
        }
        if (now.isAfter(subscription.getExpiresAt())) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_EXPIRED);
        }
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        return dateTime.atZone(TimeConfig.ZONE_ID).toOffsetDateTime();
    }
}
