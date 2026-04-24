package com.example.embabel;

import java.util.List;

public record TravelRequest(String region, List<String> activities, String additionalWishes) {
}
