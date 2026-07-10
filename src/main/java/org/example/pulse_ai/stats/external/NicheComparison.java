package org.example.pulse_ai.stats.external;

import java.util.List;

/**
 * Позиция канала внутри своей ниши (категории TGStat).
 *
 * @param category         категория ниши
 * @param peers            сколько каналов ниши учтено
 * @param medianSubscribers медиана подписчиков в нише
 * @param medianCi         медиана индекса цитирования
 * @param percentile       процент каналов ниши, которые меньше нашего (0..100)
 * @param similar          похожие по размеру каналы
 */
public record NicheComparison(
        String category,
        int peers,
        int medianSubscribers,
        double medianCi,
        int percentile,
        List<Peer> similar
) {
    public record Peer(String title, String username, int subscribers) {
    }
}
