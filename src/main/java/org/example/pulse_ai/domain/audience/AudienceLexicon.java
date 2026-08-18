package org.example.pulse_ai.domain.audience;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Общие стоп-слова и слишком широкие ярлыки. Не про конкретный канал.
 */
public final class AudienceLexicon {

    /** Ярлык ниши, который ничего не говорит (категория TGStat «бизнес»). */
    private static final Set<String> TOO_BROAD_LABEL = Set.of(
            "бизнес", "business", "новости", "news", "разное", "другое", "общее",
            "медиа", "media", "люди", "человек", "аудитория", "официальный", "official"
    );

    /** Одно слово в поиск TGStat даёт помойку, даже если оно есть в постах. */
    private static final Set<String> TOO_BROAD_ALONE_QUERY = Set.of(
            "бизнес", "business", "новости", "news", "разное", "другое", "общее",
            "медиа", "канал", "каналы", "телеграм", "telegram", "контент",
            "подписчик", "аудитория", "реклама"
    );

    private static final Set<String> STOP = Set.of(
            "этот", "этого", "эта", "это", "как", "что", "чтобы", "когда", "если",
            "или", "для", "при", "без", "над", "под", "про", "вас", "вам", "наш",
            "наши", "ваш", "ваши", "они", "она", "есть", "будет", "можно",
            "нужно", "просто", "очень", "более", "самый", "сегодня", "завтра",
            "сейчас", "здесь", "там", "после", "перед", "также", "ещё", "еще",
            "уже", "только", "даже", "всего", "между", "через", "потом", "почему",
            "какой", "какая", "какие", "который", "которая", "которые", "свой",
            "своя", "свои", "весь", "вся", "все", "один", "одна", "одни", "два",
            "три", "новый", "новая", "новые", "первый", "первая", "хороший",
            "делать", "сделать", "писать", "пост", "посты", "публикация",
            "подписывайся", "подписаться", "ссылка", "скидка", "бесплатно",
            "реклама", "прайс", "размещение", "канал", "каналы", "подписчик"
    );

    private static final Set<String> RECIPE_JARGON = Set.of(
            "нота", "ноты", "верхние", "верхняя", "сердце", "основа", "аккорд", "пирамида",
            "выдержка", "цедра", "бархат", "бокал", "закат", "вспышка", "олицетворение",
            "фиалка", "слива", "клубника", "береза", "апельсин", "ваниль", "изабелла",
            "рубин", "шелк", "скатерть", "капель", "капля", "бархатный", "благородное",
            "красное", "виноград", "бочка", "бочек", "шепот", "пыль", "слеза"
    );

    private static final Set<String> WEAK_SEARCH = Set.of(
            "качество", "роскошь", "люкс", "luxury", "истинное", "посредственн", "добро",
            "пожаловать", "здесь", "встречается", "созданы", "ценит", "готово"
    );

    /**
     * Если в канале есть любой корень семьи — в поиск можно все канонические запросы семьи.
     * Это ниша, не слова из карточки товара.
     */
    private static final List<List<String>> THEME_FAMILIES = List.of(
            List.of("диффузор", "парфюм", "парфюмерия", "аромат", "ароматы", "духи", "запах",
                    "свеча", "свечи", "perfume", "fragrance", "aroma"),
            List.of("макияж", "помада", "тушь", "косметика", "брови", "тоналка", "makeup"),
            List.of("сауна", "баня", "спа", "массаж", "релакс"),
            List.of("авто", "машина", "автомобил", "шины", "детэйлинг"),
            List.of("недвижим", "квартир", "ипотек", "риелтор"),
            List.of("фитнес", "трениров", "похуд", "зож", "йога"),
            List.of("юрист", "закон", "договор", "суд"),
            List.of("психолог", "тревог", "отношен", "терап")
    );

    public static String norm(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
    }

    public static boolean tooBroadLabel(String token) {
        return matchesSet(token, TOO_BROAD_LABEL);
    }

    public static boolean tooBroadAloneQuery(String query) {
        String n = norm(query);
        if (n.isBlank()) {
            return true;
        }
        String[] parts = n.split("\\s+");
        int content = 0;
        for (String p : parts) {
            if (p.length() < 4 || STOP.contains(p)) {
                continue;
            }
            if (!matchesSet(p, TOO_BROAD_ALONE_QUERY)) {
                content++;
            }
        }
        return content == 0;
    }

    public static boolean stop(String token) {
        String n = norm(token);
        return n.length() < 4 || STOP.contains(n) || tooBroadLabel(n);
    }

    private static boolean matchesSet(String token, Set<String> set) {
        String n = norm(token);
        if (n.isBlank()) {
            return true;
        }
        String st = stem(n);
        for (String b : set) {
            if (n.equals(b) || st.equals(stem(b))) {
                return true;
            }
        }
        return false;
    }

    /** Грубая основа слова, без морфологии: достаточно, чтобы «продвижение» ≈ «продвижен». */
    public static String stem(String token) {
        String n = norm(token);
        if (n.length() >= 8) {
            return n.substring(0, 6);
        }
        if (n.length() >= 6) {
            return n.substring(0, 5);
        }
        return n;
    }

    public static boolean recipeJargon(String token) {
        String n = norm(token);
        if (n.isBlank()) {
            return false;
        }
        if (RECIPE_JARGON.contains(n) || WEAK_SEARCH.contains(n)) {
            return true;
        }
        for (String j : RECIPE_JARGON) {
            if (sameStem(n, j)) {
                return true;
            }
        }
        return false;
    }

    public static boolean badSearchQuery(String query) {
        if (tooBroadAloneQuery(query) || query == null) {
            return true;
        }
        String n = norm(query);
        String[] parts = n.split("\\s+");
        int content = 0;
        int jargon = 0;
        for (String p : parts) {
            if (p.length() < 4 || STOP.contains(p)) {
                continue;
            }
            if (recipeJargon(p)) {
                jargon++;
            } else {
                content++;
            }
        }
        return content == 0 || jargon > content;
    }

    public static boolean queriesLookLikeRecipe(List<String> queries) {
        if (queries == null || queries.isEmpty()) {
            return false;
        }
        int bad = 0;
        for (String q : queries) {
            if (badSearchQuery(q) || recipeJargon(q)) {
                bad++;
            }
        }
        return bad * 2 >= queries.size();
    }

    /** Канонические запросы ниши, если в тексте виден хотя бы один корень семьи. */
    public static List<String> familyQueries(String blob) {
        String n = norm(blob);
        if (n.isBlank()) {
            return List.of();
        }
        for (List<String> family : THEME_FAMILIES) {
            boolean hit = false;
            for (String root : family) {
                if (n.contains(root) || containsStem(n, root)) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                return family.stream()
                        .filter(t -> t.length() >= 5 && !t.equals("perfume") && !t.equals("fragrance")
                                && !t.equals("aroma") && !t.equals("makeup"))
                        .limit(4)
                        .toList();
            }
        }
        return List.of();
    }

    public static boolean inBlob(String query, String blob) {
        if (query == null || blob == null) {
            return false;
        }
        String b = norm(blob);
        for (String p : norm(query).split("\\s+")) {
            if (p.length() < 4 || stop(p) || recipeJargon(p)) {
                continue;
            }
            if (b.contains(p) || containsStem(b, p)) {
                return true;
            }
        }
        return false;
    }

    public static boolean inDetectedFamily(String query, String blob) {
        List<String> fam = familyQueries(blob);
        if (fam.isEmpty() || query == null) {
            return false;
        }
        String n = norm(query);
        for (String f : fam) {
            if (n.contains(f) || sameStem(n, f)) {
                return true;
            }
        }
        // полный список семьи, не только канон из 4
        for (List<String> family : THEME_FAMILIES) {
            boolean blobIn = false;
            for (String root : family) {
                if (norm(blob).contains(root)) {
                    blobIn = true;
                    break;
                }
            }
            if (!blobIn) {
                continue;
            }
            for (String root : family) {
                if (n.contains(root) || sameStem(n, root)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsStem(String blob, String token) {
        String st = stem(token);
        return st.length() >= 4 && blob.contains(st);
    }

    public static boolean sameStem(String a, String b) {
        String sa = stem(a);
        String sb = stem(b);
        return !sa.isBlank() && !sb.isBlank() && (sa.equals(sb) || sa.startsWith(sb) || sb.startsWith(sa));
    }
}
