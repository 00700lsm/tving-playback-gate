package com.playbackgate.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playbackgate.auth.JwtProvider;
import com.playbackgate.content.domain.Content;
import com.playbackgate.content.domain.ContentStatus;
import com.playbackgate.content.repository.ContentRepository;
import com.playbackgate.member.domain.Member;
import com.playbackgate.member.domain.MemberStatus;
import com.playbackgate.member.repository.MemberRepository;
import com.playbackgate.playback.domain.PlaybackSession;
import com.playbackgate.playback.domain.PlaybackSessionStatus;
import com.playbackgate.playback.dto.PlaybackStartRequest;
import com.playbackgate.playback.dto.PlaybackStartResponse;
import com.playbackgate.playback.repository.PlaybackSessionRepository;
import com.playbackgate.subscription.domain.Subscription;
import com.playbackgate.subscription.domain.SubscriptionPlan;
import com.playbackgate.subscription.domain.SubscriptionStatus;
import com.playbackgate.subscription.repository.SubscriptionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Transactional
class PlaybackIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JwtProvider jwtProvider;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    ContentRepository contentRepository;
    @Autowired
    SubscriptionRepository subscriptionRepository;
    @Autowired
    PlaybackSessionRepository playbackSessionRepository;

    private Member premiumMember;
    private Member basicMember;
    private Member blockedMember;
    private Member youngMember;
    private Content basicContent;
    private Content standardContent;
    private Content adultContent;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime started = now.minusDays(1);
        LocalDateTime expires = now.plusDays(30);

        premiumMember = memberRepository.save(new Member("premium@test.com", LocalDate.of(1990, 1, 1), MemberStatus.ACTIVE, now));
        basicMember = memberRepository.save(new Member("basic@test.com", LocalDate.of(1990, 1, 1), MemberStatus.ACTIVE, now));
        blockedMember = memberRepository.save(new Member("blocked@test.com", LocalDate.of(1990, 1, 1), MemberStatus.BLOCKED, now));
        youngMember = memberRepository.save(new Member("young@test.com", LocalDate.of(2009, 1, 1), MemberStatus.ACTIVE, now));

        subscriptionRepository.save(new Subscription(premiumMember, SubscriptionPlan.PREMIUM, SubscriptionStatus.ACTIVE, started, expires));
        subscriptionRepository.save(new Subscription(basicMember, SubscriptionPlan.BASIC, SubscriptionStatus.ACTIVE, started, expires));
        subscriptionRepository.save(new Subscription(blockedMember, SubscriptionPlan.PREMIUM, SubscriptionStatus.ACTIVE, started, expires));
        subscriptionRepository.save(new Subscription(youngMember, SubscriptionPlan.PREMIUM, SubscriptionStatus.ACTIVE, started, expires));

        basicContent = contentRepository.save(new Content(
                "Basic Movie", ContentStatus.OPEN, 0, SubscriptionPlan.BASIC,
                now.minusDays(1), now.plusDays(30), now
        ));
        standardContent = contentRepository.save(new Content(
                "Standard Show", ContentStatus.OPEN, 15, SubscriptionPlan.STANDARD,
                now.minusDays(1), now.plusDays(30), now
        ));
        adultContent = contentRepository.save(new Content(
                "Premium Adult", ContentStatus.OPEN, 18, SubscriptionPlan.PREMIUM,
                now.minusDays(1), now.plusDays(30), now
        ));
    }

    @Test
    void 인증_없이_재생하면_401() throws Exception {
        mockMvc.perform(post("/api/v1/playback/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PlaybackStartRequest(basicContent.getId(), "iphone-001"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 정상_재생_요청은_세션을_저장하고_토큰을_반환한다() throws Exception {
        MvcResult result = mockMvc.perform(start(premiumMember, basicContent.getId(), "iphone-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andExpect(jsonPath("$.playbackToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();

        PlaybackStartResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                PlaybackStartResponse.class
        );
        PlaybackSession saved = playbackSessionRepository.findBySessionId(response.sessionId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PlaybackSessionStatus.ACTIVE);
        assertThat(saved.getMember().getId()).isEqualTo(premiumMember.getId());
        assertThat(saved.getContent().getId()).isEqualTo(basicContent.getId());
    }

    @Test
    void 재생_종료_후_다시_시작할_수_있다() throws Exception {
        MvcResult started = mockMvc.perform(start(basicMember, basicContent.getId(), "iphone-001"))
                .andExpect(status().isOk())
                .andReturn();
        PlaybackStartResponse response = objectMapper.readValue(
                started.getResponse().getContentAsString(),
                PlaybackStartResponse.class
        );

        mockMvc.perform(delete("/api/v1/playback/sessions/" + response.sessionId())
                        .header("Authorization", bearer(basicMember)))
                .andExpect(status().isNoContent());

        PlaybackSession ended = playbackSessionRepository.findBySessionId(response.sessionId()).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(PlaybackSessionStatus.ENDED);
        assertThat(ended.getEndedAt()).isNotNull();

        mockMvc.perform(start(basicMember, basicContent.getId(), "iphone-002"))
                .andExpect(status().isOk());
    }

    @Test
    void BLOCKED_회원은_재생이_거절된다() throws Exception {
        mockMvc.perform(start(blockedMember, basicContent.getId(), "iphone-001"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_ACTIVE"));
    }

    @Test
    void BASIC_회원은_STANDARD_콘텐츠를_재생할_수_없다() throws Exception {
        mockMvc.perform(start(basicMember, standardContent.getId(), "iphone-001"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_NOT_ALLOWED"));
    }

    @Test
    void BASIC_회원은_동시_재생_제한을_초과하면_거절된다() throws Exception {
        mockMvc.perform(start(basicMember, basicContent.getId(), "iphone-001"))
                .andExpect(status().isOk());

        mockMvc.perform(start(basicMember, basicContent.getId(), "iphone-002"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_PLAYBACK_LIMIT_EXCEEDED"));
    }

    @Test
    void 연령_제한에_걸리면_재생이_거절된다() throws Exception {
        mockMvc.perform(start(youngMember, adultContent.getId(), "iphone-001"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AGE_RESTRICTED"));
    }

    @Test
    void 만료된_세션은_동시_재생_수에서_제외된다() throws Exception {
        PlaybackSession expired = PlaybackSession.start(basicMember, basicContent, "old-device", LocalDateTime.now());
        ReflectionTestUtils.setField(expired, "expiresAt", LocalDateTime.now().minusMinutes(1));
        playbackSessionRepository.save(expired);

        mockMvc.perform(start(basicMember, basicContent.getId(), "iphone-001"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder start(
            Member member,
            Long contentId,
            String deviceId
    ) throws Exception {
        return post("/api/v1/playback/sessions")
                .header("Authorization", bearer(member))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PlaybackStartRequest(contentId, deviceId)));
    }

    private String bearer(Member member) {
        return "Bearer " + jwtProvider.createAuthToken(member.getId());
    }
}
