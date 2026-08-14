package com.playbackgate.playback.service;

import com.playbackgate.common.config.CacheConfig;
import com.playbackgate.content.domain.Content;
import com.playbackgate.content.repository.ContentRepository;
import com.playbackgate.member.domain.Member;
import com.playbackgate.member.repository.MemberRepository;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

@Component
public class CachedPlaybackReads {

    private final MemberRepository memberRepository;
    private final ContentRepository contentRepository;

    public CachedPlaybackReads(
            MemberRepository memberRepository,
            ContentRepository contentRepository
    ) {
        this.memberRepository = memberRepository;
        this.contentRepository = contentRepository;
    }

    @Cacheable(cacheNames = CacheConfig.MEMBERS, key = "#memberId")
    public Optional<Member> findMember(Long memberId) {
        return memberRepository.findById(memberId);
    }

    @Cacheable(cacheNames = CacheConfig.CONTENTS, key = "#contentId")
    public Optional<Content> findContent(Long contentId) {
        return contentRepository.findById(contentId);
    }
}
