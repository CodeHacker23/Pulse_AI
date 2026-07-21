package org.example.pulse_ai.domain.analysis;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.persistence.entity.ContentIdeaEntity;
import org.example.pulse_ai.persistence.entity.GeneratedPostEntity;
import org.example.pulse_ai.persistence.repository.GeneratedPostRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class GeneratedPostService {

    private final GeneratedPostRepository repository;

    @Transactional
    public GeneratedPostEntity saveDraft(long requestId, ContentIdeaEntity idea, String text) {
        GeneratedPostEntity post = repository.findByRequestIdAndIdeaId(requestId, idea.getId())
                .orElseGet(GeneratedPostEntity::new);
        post.setRequestId(requestId);
        post.setIdeaId(idea.getId());
        post.setSortOrder(idea.getSortOrder());
        if (post.getVariantA() == null || post.getVariantA().isBlank()) {
            post.setVariantA(text);
        } else {
            post.setVariantB(text);
        }
        return repository.save(post);
    }

    @Transactional
    public GeneratedPostEntity saveRegeneratedDraft(long requestId, ContentIdeaEntity idea, String text) {
        GeneratedPostEntity post = repository.findByRequestIdAndIdeaId(requestId, idea.getId())
                .orElseGet(GeneratedPostEntity::new);
        post.setRequestId(requestId);
        post.setIdeaId(idea.getId());
        post.setSortOrder(idea.getSortOrder());
        post.setVariantA(text);
        post.setContentType("TEXT");
        post.setPollOptions(null);
        return repository.save(post);
    }

    @Transactional
    public GeneratedPostEntity savePollDraft(
            long requestId,
            ContentIdeaEntity idea,
            String question,
            List<String> options,
            boolean anonymous
    ) {
        GeneratedPostEntity post = repository.findByRequestIdAndIdeaId(requestId, idea.getId())
                .orElseGet(GeneratedPostEntity::new);
        post.setRequestId(requestId);
        post.setIdeaId(idea.getId());
        post.setSortOrder(idea.getSortOrder());
        post.setContentType("POLL");
        post.setVariantA(question);
        post.setPollOptions(joinOptions(options));
        post.setPollAnonymous(anonymous);
        post.setImageUrl(null);
        return repository.save(post);
    }

    @Transactional
    public void updatePollOptions(long postId, List<String> options) {
        repository.findById(postId).ifPresent(post -> {
            post.setPollOptions(joinOptions(options));
            repository.save(post);
        });
    }

    @Transactional
    public void setPollAnonymous(long postId, boolean anonymous) {
        repository.findById(postId).ifPresent(post -> {
            post.setPollAnonymous(anonymous);
            repository.save(post);
        });
    }

    public static boolean isPoll(GeneratedPostEntity post) {
        return post != null && "POLL".equalsIgnoreCase(post.getContentType());
    }

    public static List<String> splitOptions(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String s = raw.trim();
        if (s.startsWith("[")) {
            try {
                com.fasterxml.jackson.databind.JsonNode arr =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
                List<String> out = new java.util.ArrayList<>();
                if (arr.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                        String v = n.asText("").trim();
                        if (!v.isBlank()) {
                            out.add(v);
                        }
                    }
                }
                return out;
            } catch (Exception ignored) {
                // fall through to line split
            }
        }
        return java.util.Arrays.stream(s.split("\\R|\\|"))
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .toList();
    }

    private static String joinOptions(List<String> options) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(options);
        } catch (Exception ex) {
            return String.join("\n", options);
        }
    }

    @Transactional(readOnly = true)
    public Optional<GeneratedPostEntity> findById(long postId) {
        return repository.findById(postId);
    }

    @Transactional
    public void setImageUrl(long postId, String imageUrl) {
        repository.findById(postId).ifPresent(post -> {
            post.setImageUrl(imageUrl);
            repository.save(post);
        });
    }

    @Transactional(readOnly = true)
    public Optional<GeneratedPostEntity> findByRequestAndIdea(long requestId, long ideaId) {
        return repository.findByRequestIdAndIdeaId(requestId, ideaId);
    }

    public String latestText(GeneratedPostEntity post) {
        if (post.getVariantB() != null && !post.getVariantB().isBlank()) {
            return post.getVariantB();
        }
        return post.getVariantA();
    }
}
