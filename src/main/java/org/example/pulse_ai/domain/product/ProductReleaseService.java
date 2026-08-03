package org.example.pulse_ai.domain.product;

import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.persistence.entity.ProductReleaseEntity;
import org.example.pulse_ai.persistence.repository.ProductReleaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ProductReleaseService {

    private final ProductReleaseRepository repository;

    public Optional<ProductReleaseEntity> findById(long id) {
        return repository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<ProductReleaseEntity> recent(int limit) {
        return repository.findTop20ByOrderByReleasedAtDesc().stream().limit(limit).toList();
    }

    @Transactional(readOnly = true)
    public List<ProductReleaseEntity> readyToPost() {
        return repository.findByStatusOrderByReleasedAtDesc("READY");
    }

    @Transactional(readOnly = true)
    public Optional<ProductReleaseEntity> findByVersion(String version) {
        return repository.findByVersion(version);
    }

    @Transactional
    public ProductReleaseEntity upsert(String version, String title, String bullets, String category, String status) {
        String ver = normalizeVersion(version);
        ProductReleaseEntity e = repository.findByVersion(ver).orElseGet(ProductReleaseEntity::new);
        e.setVersion(ver);
        e.setTitle(title != null ? title.trim() : ver);
        e.setBullets(normalizeBullets(bullets));
        e.setCategory(category != null ? category.trim().toUpperCase(Locale.ROOT) : "UPDATE");
        e.setStatus(status != null ? status.trim().toUpperCase(Locale.ROOT) : "READY");
        if (e.getReleasedAt() == null) {
            e.setReleasedAt(Instant.now());
        }
        return repository.save(e);
    }

    /**
     * Быстрый ввод из бота:
     * строка1 = версия или версия | заголовок
     * дальше буллеты
     */
    @Transactional
    public ProductReleaseEntity addFromRawText(String raw) {
        String[] lines = raw.replace("\r\n", "\n").split("\n");
        List<String> nonEmpty = new ArrayList<>();
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                nonEmpty.add(line.trim());
            }
        }
        if (nonEmpty.isEmpty()) {
            throw new IllegalArgumentException("Пустой текст релиза");
        }
        String head = nonEmpty.get(0);
        String version;
        String title;
        if (head.contains("|")) {
            String[] parts = head.split("\\|", 2);
            version = parts[0].trim();
            title = parts[1].trim();
        } else if (head.matches("(?i)v?\\d+(\\.\\d+)+.*")) {
            String[] parts = head.split("\\s+", 2);
            version = parts[0].replaceFirst("(?i)^v", "");
            title = parts.length > 1 ? parts[1] : "Обновление " + version;
        } else {
            version = "0." + (repository.count() + 1) + ".0";
            title = head;
        }
        String bullets = String.join("\n", nonEmpty.subList(1, nonEmpty.size()));
        if (bullets.isBlank()) {
            bullets = "▪️" + title;
        }
        return upsert(version, title, bullets, "UPDATE", "READY");
    }

    /** Текст фактов для LLM / промпта. */
    @Transactional(readOnly = true)
    public String factsBlock(int maxReleases) {
        List<ProductReleaseEntity> list = repository.findByStatusInOrderByReleasedAtDesc(List.of("READY", "POSTED"));
        if (list.isEmpty()) {
            return "Релизов в реестре пока нет — не выдумывай фичи.";
        }
        StringBuilder sb = new StringBuilder("ФАКТЫ ИЗ РЕЕСТРА РЕЛИЗОВ (пиши только из этого списка):\n");
        int n = 0;
        for (ProductReleaseEntity r : list) {
            if (n++ >= maxReleases) {
                break;
            }
            sb.append("\n### ").append(r.getVersion()).append(" — ").append(r.getTitle())
                    .append(" [").append(r.getCategory()).append("/").append(r.getStatus()).append("]\n")
                    .append(r.getBullets()).append('\n');
        }
        return sb.toString().trim();
    }

    /** Готовый патчноут для канала — польза + интрига, без схем. */
    @Transactional(readOnly = true)
    public String composePatchNote(ProductReleaseEntity r) {
        String icon = switch (String.valueOf(r.getCategory()).toUpperCase(Locale.ROOT)) {
            case "FIX" -> "🛠";
            case "TEST" -> "🧪";
            case "INSIGHT" -> "🧠";
            case "FEATURE" -> "⚡";
            default -> "🛠";
        };
        return icon + "Обновление " + r.getVersion() + "\n"
                + r.getTitle() + "\n\n"
                + veilBulletsForChannel(normalizeBullets(r.getBullets()))
                + "\n\nПопробовать: https://t.me/Pulsse_AI_bot";
    }

    /** Убираем/смягчаем технические утечки для публичного канала. */
    static String veilBulletsForChannel(String bullets) {
        String[] lines = bullets.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            String lower = t.toLowerCase(Locale.ROOT);
            if (lower.contains("telethon") || lower.contains("sidecar") || lower.contains("parser")
                    || lower.contains("observer") || lower.contains("spambot") || lower.contains("socks")
                    || lower.contains("прокси") || lower.contains("proxy") || lower.contains("crm")
                    || lower.contains("outreach") || lower.contains("flood")) {
                // заменяем слишком прямые строки на мягкий намёк
                t = "▪️Под капотом стало тише и умнее — детали оставим себе.";
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t);
        }
        return sb.length() == 0 ? "▪️Свежие улучшения в продукте — заходите проверить." : sb.toString();
    }

    @Transactional(readOnly = true)
    public Optional<String> composeLatestReadyPatchNote() {
        return readyToPost().stream().findFirst().map(this::composePatchNote);
    }

    @Transactional
    public void markPostedFromPatchNote(String patchText, Long channelPostId) {
        if (patchText == null) {
            markLatestReadyPosted(channelPostId);
            return;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("Обновление\\s+(v?[\\d.]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(patchText);
        if (m.find()) {
            String ver = normalizeVersion(m.group(1));
            Optional<ProductReleaseEntity> opt = repository.findByVersion(ver);
            if (opt.isPresent()) {
                markPosted(opt.get().getId(), channelPostId);
                return;
            }
        }
        markLatestReadyPosted(channelPostId);
    }

    @Transactional
    public void markPosted(Long releaseId, Long channelPostId) {
        repository.findById(releaseId).ifPresent(r -> {
            r.setStatus("POSTED");
            r.setPostedAt(Instant.now());
            r.setChannelPostId(channelPostId);
            repository.save(r);
        });
    }

    @Transactional
    public void markLatestReadyPosted(Long channelPostId) {
        readyToPost().stream().findFirst().ifPresent(r -> markPosted(r.getId(), channelPostId));
    }

    public static String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return "0.0.1";
        }
        return version.trim().replaceFirst("(?i)^v", "");
    }

    public static String normalizeBullets(String bullets) {
        if (bullets == null || bullets.isBlank()) {
            return "▪️Обновление";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : bullets.replace("\r\n", "\n").split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (!t.startsWith("▪️") && !t.startsWith("•") && !t.startsWith("-")) {
                t = "▪️" + t;
            } else if (t.startsWith("•") || t.startsWith("- ")) {
                t = "▪️" + t.replaceFirst("^[•\\-]\\s*", "");
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t);
        }
        return sb.toString();
    }
}
