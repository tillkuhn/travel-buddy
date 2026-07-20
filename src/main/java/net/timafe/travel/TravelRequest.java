package net.timafe.travel;

import java.util.List;

public record TravelRequest(String region, List<String> activities, String additionalWishes) {
}
