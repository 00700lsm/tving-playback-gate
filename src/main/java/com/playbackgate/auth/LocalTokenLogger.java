package com.playbackgate.auth;

import com.playbackgate.member.domain.Member;
import com.playbackgate.member.repository.MemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
@Order(2)
public class LocalTokenLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalTokenLogger.class);

    private final MemberRepository memberRepository;
    private final JwtProvider jwtProvider;

    public LocalTokenLogger(MemberRepository memberRepository, JwtProvider jwtProvider) {
        this.memberRepository = memberRepository;
        this.jwtProvider = jwtProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("로컬 Auth JWT (로그인 UI 없음). Authorization: Bearer <token>");
        for (Member member : memberRepository.findAll()) {
            log.info("memberId={} email={} status={} token={}",
                    member.getId(),
                    member.getEmail(),
                    member.getStatus(),
                    jwtProvider.createAuthToken(member.getId()));
        }
    }
}
