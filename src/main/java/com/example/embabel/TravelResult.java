package com.example.embabel;

import java.util.List;

public record TravelResult(String suggestion, String prompt, String region, List<String> activities, String additionalWishes) {
}
