package com.playbackgate.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playbackgate.auth.JwtProvider;
import com.playbackgate.content.domain.Content;
import com.playbackgate.content.domain.ContentStatus;
import com.playbackgate.content.repository.ContentRepository;
import com.playbackgate.member.domain.Member;
import com.playbackgate.member.domain.MemberStatus;
import com.playbackgate.member.repository.MemberRepository;
import com.playbackgate.playback.domain.PlaybackSessionStatus;
import com.playbackgate.playback.dto.PlaybackStartRequest;
import com.playbackgate.playback.repository.PlaybackSessionRepository;
import com.playbackgate.subscription.domain.Subscription;
import com.playbackgate.subscription.domain.SubscriptionPlan;
import com.playbackgate.subscription.domain.SubscriptionStatus;
import com.playbackgate.subscription.repository.SubscriptionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class ConcurrentPlaybackTest {

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

    private Member basicMember;
    private Content basicContent;

    @BeforeEach
    void setUp() {
        playbackSessionRepository.deleteAll();
        LocalDateTime now = LocalDateTime.now();
        basicMember = memberRepository.save(
                new Member("race-basic@test.com", LocalDate.of(1990, 1, 1), MemberStatus.ACTIVE, now)
        );
        subscriptionRepository.save(new Subscription(
                basicMember,
                SubscriptionPlan.BASIC,
                SubscriptionStatus.ACTIVE,
                now.minusDays(1),
                now.plusDays(30)
        ));
        basicContent = contentRepository.save(new Content(
                "Race Movie",
                ContentStatus.OPEN,
                0,
                SubscriptionPlan.BASIC,
                now.minusDays(1),
                now.plusDays(30),
                now
        ));
    }

    @Test
    void BASIC_회원_동시_요청은_ACTIVE_세션이_1을_넘지_않는다() throws Exception {
        int n = 40;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < n; i++) {
            int device = i;
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    var result = mockMvc.perform(post("/api/v1/playback/sessions")
                            .header("Authorization", "Bearer " + jwtProvider.createAuthToken(basicMember.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new PlaybackStartRequest(basicContent.getId(), "race-" + device)
                            )));
                    if (result.andReturn().getResponse().getStatus() == 200) {
                        success.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 동시 요청 중 예외는 실패로 취급하지 않고 성공 건수만 본다.
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        long active = playbackSessionRepository.countByMember_IdAndStatusAndExpiresAtGreaterThanEqual(
                basicMember.getId(),
                PlaybackSessionStatus.ACTIVE,
                LocalDateTime.now()
        );
        assertThat(success.get()).isEqualTo(1);
        assertThat(active).isEqualTo(1);
    }
}
