package com.playbackgate.playback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playbackgate.auth.JwtProvider;
import com.playbackgate.common.config.TimeConfig;
import com.playbackgate.common.exception.BusinessException;
import com.playbackgate.common.exception.ErrorCode;
import com.playbackgate.content.domain.Content;
import com.playbackgate.content.domain.ContentStatus;
import com.playbackgate.content.repository.ContentRepository;
import com.playbackgate.member.domain.Member;
import com.playbackgate.member.domain.MemberStatus;
import com.playbackgate.member.repository.MemberRepository;
import com.playbackgate.playback.domain.PlaybackSession;
import com.playbackgate.playback.domain.PlaybackSessionStatus;
import com.playbackgate.playback.dto.PlaybackStartResponse;
import com.playbackgate.playback.repository.PlaybackSessionRepository;
import com.playbackgate.subscription.domain.Subscription;
import com.playbackgate.subscription.domain.SubscriptionPlan;
import com.playbackgate.subscription.domain.SubscriptionStatus;
import com.playbackgate.subscription.repository.SubscriptionRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PlaybackServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long CONTENT_ID = 100L;
    private static final String DEVICE_ID = "iphone-001";

    @Mock
    private MemberRepository memberRepository;
    @Mock
    private ContentRepository contentRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private PlaybackSessionRepository playbackSessionRepository;

    private PlaybackService playbackService;
    private Clock clock;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(
                LocalDateTime.of(2026, 8, 13, 12, 0).atZone(TimeConfig.ZONE_ID).toInstant(),
                TimeConfig.ZONE_ID
        );
        now = LocalDateTime.now(clock);
        JwtProvider jwtProvider = new JwtProvider("playback-gate-local-secret-key-32bytes-min", 86400);
        CachedPlaybackReads cachedPlaybackReads = new CachedPlaybackReads(
                memberRepository,
                contentRepository
        );
        playbackService = new PlaybackService(
                cachedPlaybackReads,
                subscriptionRepository,
                playbackSessionRepository,
                jwtProvider,
                clock
        );
    }

    @Test
    void 정상_요청은_세션과_토큰을_발급한다() {
        stubPlayableMember(adult(MemberStatus.ACTIVE), basicOpenContent(), activeSubscription(SubscriptionPlan.PREMIUM));
        when(playbackSessionRepository.countByMember_IdAndStatusAndExpiresAtGreaterThanEqual(
                MEMBER_ID, PlaybackSessionStatus.ACTIVE, now
        )).thenReturn(0L);
        when(playbackSessionRepository.save(any(PlaybackSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PlaybackStartResponse response = playbackService.start(MEMBER_ID, CONTENT_ID, DEVICE_ID);

        assertThat(response.sessionId()).isNotBlank();
        assertThat(response.playbackToken()).isNotBlank();
        assertThat(response.expiresAt()).isEqualTo(now.plusHours(2).atZone(TimeConfig.ZONE_ID).toOffsetDateTime());

        ArgumentCaptor<PlaybackSession> captor = ArgumentCaptor.forClass(PlaybackSession.class);
        verify(playbackSessionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PlaybackSessionStatus.ACTIVE);
        assertThat(captor.getValue().getExpiresAt()).isEqualTo(now.plusHours(2));
    }

    @Test
    void BLOCKED_회원은_재생할_수_없다() {
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(adult(MemberStatus.BLOCKED)));

        assertThatThrownBy(() -> playbackService.start(MEMBER_ID, CONTENT_ID, DEVICE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.MEMBER_NOT_ACTIVE);
    }

    @Test
    void WITHDRAWN_회원은_재생할_수_없다() {
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(adult(MemberStatus.WITHDRAWN)));

        assertThatThrownBy(() -> playbackService.start(MEMBER_ID, CONTENT_ID, DEVICE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.MEMBER_NOT_ACTIVE);
    }

    @Test
    void 존재하지_않는_회원은_재생할_수_없다() {
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> playbackService.start(MEMBER_ID, CONTENT_ID, DEVICE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    void 이용권이_없으면_재생할_수_없다() {
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(adult(MemberStatus.ACTIVE)));
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(basicOpenContent()));
        when(subscriptionRepository.findLatestByMemberIdForUpdate(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> playbackService.start(MEMBER_ID, CONTENT_ID, DEVICE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SUBSCRIPTION_NOT_FOUND);
    }

    @Test
    void 정지된_이용권으로는_재생할_수_없다() {
        stubPlayableMember(
                adult(MemberStatus.ACTIVE),
                basicOpenContent(),
                subscription(SubscriptionPlan.PREMIUM, SubscriptionStatus.SUSPENDED, now.minusDays(1), now.plusDays(30))
        );

        assertThatThrownBy(() -> playbackService.start(MEMBER_ID, CONTENT_ID, DEVICE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SUBSCRIPTION_NOT_ACTIVE);
    }

    @Test
    void 만료된_이용권으로는_재생할_수_없다() {
        stubPlayableMember(
                adult(MemberStatus.ACTIVE),
                basicOpenContent(),
                subscription(SubscriptionPlan.PREMIUM, SubscriptionStatus.EXPIRED, now.minusDays(60), now.minusDays(1))
        );

        assertThatThrownBy(() -> playbackService.start(MEMBER_ID, CONTENT_ID, DEVICE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SUBSCRIPTION_EXPIRED);
    }

    @Test
    void 기간이_지난_ACTIVE_이용권은_만료로_거절한다() {
        stubPlayableMember(
                adult(MemberStatus.ACTIVE),
                basicOpenContent(),
                subscription(SubscriptionPlan.PREMIUM, SubscriptionStatus.ACTIVE, now.minusDays(60), now.minusMinutes(1))
        );

        assertThatThrownBy(() -> playbackService.start(MEMBER_ID, CONTENT_ID, DEVICE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SUBSCRIPTION_EXPIRED);
    }

    @Test
    void BASIC_사용자는_STANDARD_콘텐츠를_재생할_수_없다() {
        stubPlayableMember(adult(MemberStatus.ACTIVE), standardOpenContent(), activeSubscription(SubscriptionPlan.BASIC));

        assertThatThrownBy(() -> playbackService.start(MEMBER_ID, CONTENT_ID, DEVICE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PLAN_NOT_ALLOWED);
    }

    @Test
    void 열일곱살은_18세_콘텐츠를_재생할_수_없다() {
        Member young = member(MEMBER_ID, MemberStatus.ACTIVE, LocalDate.of(2009, 1, 1));
        stubPlayableMember(young, adultOpenContent(), activeSubscription(SubscriptionPlan.PREMIUM));

        assertThatThrownBy(() -> playbackService.start(MEMBER_ID, CONTENT_ID, DEVICE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGE_RESTRICTED);
    }

    @Test
    void CLOSED_콘텐츠는_재생할_수_없다() {
        Content closed = content(CONTENT_ID, ContentStatus.CLOSED, 0, SubscriptionPlan.BASIC, now.minusDays(1), now.plusDays(1));
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(adult(MemberStatus.ACTIVE)));
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> playbackService.start(MEMBER_ID, CONTENT_ID, DEVICE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONTENT_NOT_AVAILABLE);
    }

    @Test
    void 공개_기간이_아니면_재생할_수_없다() {
        Content upcoming = content(
                CONTENT_ID,
                ContentStatus.OPEN,
                0,
                SubscriptionPlan.BASIC,
                now.plusDays(1),
                now.plusDays(10)
        );
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(adult(MemberStatus.ACTIVE)));
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(upcoming));

        assertThatThrownBy(() -> playbackService.start(MEMBER_ID, CONTENT_ID, DEVICE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONTENT_NOT_AVAILABLE);
    }

    @Test
    void BASIC_사용자는_Active_Session이_있으면_추가_재생할_수_없다() {
        stubPlayableMember(adult(MemberStatus.ACTIVE), basicOpenContent(), activeSubscription(SubscriptionPlan.BASIC));
        when(playbackSessionRepository.countByMember_IdAndStatusAndExpiresAtGreaterThanEqual(
                MEMBER_ID, PlaybackSessionStatus.ACTIVE, now
        )).thenReturn(1L);

        assertThatThrownBy(() -> playbackService.start(MEMBER_ID, CONTENT_ID, DEVICE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONCURRENT_PLAYBACK_LIMIT_EXCEEDED);
    }

    @Test
    void 만료된_세션은_동시_재생_수_조회_시_현재시간_이후만_센다() {
        stubPlayableMember(adult(MemberStatus.ACTIVE), basicOpenContent(), activeSubscription(SubscriptionPlan.BASIC));
        when(playbackSessionRepository.countByMember_IdAndStatusAndExpiresAtGreaterThanEqual(
                eq(MEMBER_ID), eq(PlaybackSessionStatus.ACTIVE), eq(now)
        )).thenReturn(0L);
        when(playbackSessionRepository.save(any(PlaybackSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PlaybackStartResponse response = playbackService.start(MEMBER_ID, CONTENT_ID, DEVICE_ID);

        assertThat(response.sessionId()).isNotBlank();
        verify(playbackSessionRepository).countByMember_IdAndStatusAndExpiresAtGreaterThanEqual(
                MEMBER_ID, PlaybackSessionStatus.ACTIVE, now
        );
    }

    @Test
    void 존재하지_않는_세션은_종료할_수_없다() {
        when(playbackSessionRepository.findBySessionId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> playbackService.end(MEMBER_ID, "missing"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PLAYBACK_SESSION_NOT_FOUND);
    }

    @Test
    void 타인_세션은_종료할_수_없다() {
        Member owner = adult(MemberStatus.ACTIVE);
        ReflectionTestUtils.setField(owner, "id", 99L);
        PlaybackSession session = PlaybackSession.start(owner, basicOpenContent(), DEVICE_ID, now);
        when(playbackSessionRepository.findBySessionId(session.getSessionId())).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> playbackService.end(MEMBER_ID, session.getSessionId()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PLAYBACK_SESSION_NOT_FOUND);
    }

    @Test
    void 본인_세션은_ENDED로_종료한다() {
        Member owner = adult(MemberStatus.ACTIVE);
        PlaybackSession session = PlaybackSession.start(owner, basicOpenContent(), DEVICE_ID, now);
        when(playbackSessionRepository.findBySessionId(session.getSessionId())).thenReturn(Optional.of(session));

        playbackService.end(MEMBER_ID, session.getSessionId());

        assertThat(session.getStatus()).isEqualTo(PlaybackSessionStatus.ENDED);
        assertThat(session.getEndedAt()).isEqualTo(now);
    }

    private void stubPlayableMember(Member member, Content content, Subscription subscription) {
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(content));
        when(subscriptionRepository.findLatestByMemberIdForUpdate(MEMBER_ID)).thenReturn(Optional.of(subscription));
    }

    private Member adult(MemberStatus status) {
        return member(MEMBER_ID, status, LocalDate.of(1990, 1, 1));
    }

    private Member member(Long id, MemberStatus status, LocalDate birthDate) {
        Member member = new Member("user@example.com", birthDate, status, now);
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    private Content basicOpenContent() {
        return content(CONTENT_ID, ContentStatus.OPEN, 0, SubscriptionPlan.BASIC, now.minusDays(1), now.plusDays(1));
    }

    private Content standardOpenContent() {
        return content(CONTENT_ID, ContentStatus.OPEN, 15, SubscriptionPlan.STANDARD, now.minusDays(1), now.plusDays(1));
    }

    private Content adultOpenContent() {
        return content(CONTENT_ID, ContentStatus.OPEN, 18, SubscriptionPlan.PREMIUM, now.minusDays(1), now.plusDays(1));
    }

    private Content content(
            Long id,
            ContentStatus status,
            int ageRating,
            SubscriptionPlan requiredPlan,
            LocalDateTime availableFrom,
            LocalDateTime availableUntil
    ) {
        Content content = new Content("sample", status, ageRating, requiredPlan, availableFrom, availableUntil, now);
        ReflectionTestUtils.setField(content, "id", id);
        return content;
    }

    private Subscription activeSubscription(SubscriptionPlan plan) {
        return subscription(plan, SubscriptionStatus.ACTIVE, now.minusDays(1), now.plusDays(30));
    }

    private Subscription subscription(
            SubscriptionPlan plan,
            SubscriptionStatus status,
            LocalDateTime startedAt,
            LocalDateTime expiresAt
    ) {
        return new Subscription(adult(MemberStatus.ACTIVE), plan, status, startedAt, expiresAt);
    }
}
