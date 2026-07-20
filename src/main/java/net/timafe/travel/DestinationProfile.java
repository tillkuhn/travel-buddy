package net.timafe.travel;

import java.util.List;
import java.util.Map;

public record DestinationProfile(
        String region,
        List<String> activities,
        String additionalWishes,
        String travelSeason
) {
    // Deterministic season lookup — no LLM needed for this kind of structured knowledge
    private static final Map<String, String> SEASONS = Map.of(
            "Europe",       "spring (Apr–Jun) or fall (Sep–Oct) for mild weather and fewer crowds",
            "Americas",     "varies widely — Patagonia peaks Dec–Feb, Caribbean Nov–Apr",
            "Southeast Asia","November to February for dry season across most of the region",
            "Africa",       "June to October for dry-season wildlife viewing in eastern/southern Africa",
            "Australia",    "March to May (autumn) or September to November (spring)"
    );

    public static DestinationProfile from(String region, List<String> activities, String additionalWishes) {
        String season = SEASONS.getOrDefault(region, "year-round — check local conditions");
        return new DestinationProfile(region, activities, additionalWishes, season);
    }
}
