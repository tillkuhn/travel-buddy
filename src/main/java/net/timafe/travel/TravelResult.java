package net.timafe.travel;

import java.util.List;

public record TravelResult(String suggestion, String region, List<String> activities, String additionalWishes, String travelSeason) {
}
