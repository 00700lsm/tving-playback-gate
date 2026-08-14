package com.playbackgate.common.config;

import com.playbackgate.content.domain.Content;
import com.playbackgate.content.domain.ContentStatus;
import com.playbackgate.content.repository.ContentRepository;
import com.playbackgate.member.domain.Member;
import com.playbackgate.member.domain.MemberStatus;
import com.playbackgate.member.repository.MemberRepository;
import com.playbackgate.subscription.domain.Subscription;
import com.playbackgate.subscription.domain.SubscriptionPlan;
import com.playbackgate.subscription.domain.SubscriptionStatus;
import com.playbackgate.subscription.repository.SubscriptionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("load-test")
@Order(3)
public class LoadTestDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LoadTestDataSeeder.class);
    private static final String EMAIL_PREFIX = "loadtest-";

    private final MemberRepository memberRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ContentRepository contentRepository;
    private final int memberCount;

    public LoadTestDataSeeder(
            MemberRepository memberRepository,
            SubscriptionRepository subscriptionRepository,
            ContentRepository contentRepository,
            @Value("${playback-gate.load-test.member-count}") int memberCount
    ) {
        this.memberRepository = memberRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.contentRepository = contentRepository;
        this.memberCount = memberCount;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime expires = LocalDateTime.of(2026, 12, 31, 23, 59, 59);

        Content content = contentRepository.findAll().stream()
                .filter(item -> item.isOpen())
                .findFirst()
                .orElseGet(() -> contentRepository.save(new Content(
                        "Load Test Movie",
                        ContentStatus.OPEN,
                        0,
                        SubscriptionPlan.BASIC,
                        now,
                        expires,
                        now
                )));

        if (memberRepository.findByEmail(EMAIL_PREFIX + "1@example.com").isPresent()) {
            Member first = memberRepository.findByEmail(EMAIL_PREFIX + "1@example.com").orElseThrow();
            Member last = memberRepository.findByEmail(EMAIL_PREFIX + memberCount + "@example.com").orElse(first);
            log.info("load-test 시드가 이미 있습니다. memberId={}..{} contentId={}",
                    first.getId(), last.getId(), content.getId());
            return;
        }

        Member first = null;
        Member last = null;
        for (int i = 1; i <= memberCount; i++) {
            Member member = memberRepository.save(new Member(
                    EMAIL_PREFIX + i + "@example.com",
                    LocalDate.of(1990, 1, 1),
                    MemberStatus.ACTIVE,
                    now
            ));
            subscriptionRepository.save(new Subscription(
                    member,
                    SubscriptionPlan.PREMIUM,
                    SubscriptionStatus.ACTIVE,
                    now,
                    expires
            ));
            if (first == null) {
                first = member;
            }
            last = member;
        }

        log.info("load-test 시드 완료. memberId={}..{} contentId={} count={}",
                first.getId(), last.getId(), content.getId(), memberCount);
    }
}
