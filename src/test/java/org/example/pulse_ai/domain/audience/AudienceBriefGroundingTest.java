package org.example.pulse_ai.domain.audience;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.pulse_ai.persistence.entity.ChannelEntity;
import org.example.pulse_ai.persistence.entity.ChannelPostEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudienceBriefGroundingTest {

    @Test
    void dropsHallucinatedQueryNotInPosts() {
        List<String> evidence = List.of("помада", "тушь", "макияж");
        List<String> queries = AudienceBriefGrounding.groundedQueries(
                evidence, List.of("крипто", "бизнес", "макияж"));
        assertTrue(queries.contains("макияж"));
        assertFalse(queries.contains("крипто"));
        assertFalse(queries.contains("бизнес"));
    }

    @Test
    void doesNotUseCatalogBusinessAsQuery() {
        List<String> evidence = List.of("продвижение", "админ", "черновик");
        List<String> queries = AudienceBriefGrounding.groundedQueries(
                evidence, List.of("бизнес", "продвижение канала"));
        assertFalse(queries.contains("бизнес"));
        assertTrue(queries.stream().anyMatch(q -> q.contains("продвижен") || q.equals("продвижение")));
    }

    @Test
    void makeupPostsDoNotBecomeSmm() {
        ChannelEntity ch = new ChannelEntity();
        ch.setTitle("Макияж каждый день");
        ch.setUsername("makeup_daily");
        ch.setCategory("бизнес");
        List<ChannelPostEntity> posts = List.of(
                post("Новая помада на каждый день: стойкость и цвет"),
                post("Тушь без комочков: как выбрать для себя"),
                post("Разбор макияжа на свидание: стрелки и нюд")
        );
        AudienceEvidence ev = AudienceEvidenceExtractor.extract(ch, posts);
        AudienceBrief brief = AudienceBriefGrounding.fromEvidence(ev, 4000);
        assertTrue(brief.usable());
        String blob = String.join(" ", brief.searchQueries()).toLowerCase();
        assertFalse(blob.contains("бизнес"));
        assertFalse(blob.equals("smm") || blob.startsWith("smm "));
        assertTrue(blob.contains("макияж") || blob.contains("помада") || blob.contains("тушь"));
    }

    @Test
    void diffuserNotesAreNotSearchQueries() {
        ChannelEntity ch = new ChannelEntity();
        ch.setTitle("AromaLar");
        ch.setUsername("AromaLar");
        ch.setCategory("бизнес");
        String about = "Добро пожаловать на AromaLar! Диффузоры созданы для тех, кто ценит истинное качество.";
        List<ChannelPostEntity> posts = List.of(
                post("""
                        БЛАГОРОДНОЕ КРАСНОЕ ВИНО:
                        Верхние ноты: Апельсин. Сердце: Слива. Основа: Высушенное дерево.
                        Этот аромат — олицетворение простого начала.""")
        );
        AudienceEvidence ev = AudienceEvidenceExtractor.extract(ch, posts, about);
        AudienceBrief brief = AudienceBriefGrounding.fromEvidence(ev, 2000);
        assertTrue(brief.usable());
        String blob = String.join(" ", brief.searchQueries()).toLowerCase();
        assertFalse(blob.contains("верхние"));
        assertFalse(blob.contains("ваниль"));
        assertFalse(blob.contains("основа"));
        assertFalse(blob.contains("бизнес"));
        assertTrue(blob.contains("диффузор") || blob.contains("парфюм") || blob.contains("аромат"));
    }

    @Test
    void llmMergeKeepsOnlyGroundedFields() throws Exception {
        AudienceEvidence ev = new AudienceEvidence(
                List.of("админ", "черновик", "продвижение"),
                List.of("Как админу делать черновик без ступора"),
                "Инструмент для каналов",
                "tool_channel",
                "",
                "бизнес",
                8
        );
        var node = new ObjectMapper().readTree("""
                {"theme":"продвижение","buyer_role":"админы каналов","job":"черновики",
                 "search_queries":["крипта","продвижение","бизнес"],
                 "parse_venues":["чаты админов каналов"],"parse_method":"GROUP_MEMBERS"}
                """);
        AudienceBrief brief = AudienceBriefGrounding.mergeLlm(ev, node, 3000);
        assertFalse(brief.searchQueries().contains("крипта"));
        assertFalse(brief.searchQueries().contains("бизнес"));
        assertTrue(brief.searchQueries().contains("продвижение"));
        assertTrue(brief.parseVenues().get(0).contains("админ"));
        assertTrue(brief.parseMethod().equals("GROUP_MEMBERS"));
    }

    private static ChannelPostEntity post(String text) {
        ChannelPostEntity p = new ChannelPostEntity();
        p.setFullText(text);
        p.setTextPreview(text);
        p.setForwarded(false);
        return p;
    }
}
