package net.timafe.travel;

import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TravelServiceTest extends EmbabelMockitoIntegrationTest {

    @Autowired
    private TravelService travelService;

    @Test
    void planReturnsLlmSuggestion() {
        whenGenerateText(p -> p.contains("Europe")).thenReturn("Visit Prague!");

        TravelResult result = travelService.plan(new TravelRequest("Europe", List.of("Culture", "Hiking"), ""));

        assertThat(result.suggestion()).isEqualTo("Visit Prague!");
        assertThat(result.region()).isEqualTo("Europe");
        assertThat(result.activities()).containsExactly("Culture", "Hiking");
        verifyGenerateText(p -> p.contains("Europe"));
    }

    @Test
    void planIncludesSeasonInPrompt() {
        // The deterministic buildProfile action enriches the request with seasonal context
        // before the LLM action fires — verify the LLM receives it.
        whenGenerateText(p -> p.contains("Best time to visit")).thenReturn("Visit Queenstown!");

        TravelResult result = travelService.plan(new TravelRequest("Australia", List.of("Hiking"), ""));

        assertThat(result.suggestion()).isEqualTo("Visit Queenstown!");
        assertThat(result.travelSeason()).contains("spring");
        verifyGenerateText(p -> p.contains("Best time to visit") && p.contains("Australia"));
    }

    @Test
    void planBuildsPromptWithAllInputs() {
        whenGenerateText(p -> true).thenReturn("Some destination");

        TravelResult result = travelService.plan(new TravelRequest("Americas", List.of("Skiing"), "ski-in/ski-out resort"));

        assertThat(result.region()).isEqualTo("Americas");
        assertThat(result.activities()).containsExactly("Skiing");
        assertThat(result.additionalWishes()).isEqualTo("ski-in/ski-out resort");
    }

    @Test
    void planHandlesNullActivities() {
        whenGenerateText(p -> p.contains("no specific activities")).thenReturn("Anywhere relaxing");

        TravelResult result = travelService.plan(new TravelRequest("Southeast Asia", null, ""));

        assertThat(result.suggestion()).isEqualTo("Anywhere relaxing");
        assertThat(result.activities()).isEmpty();
        verifyGenerateText(p -> p.contains("no specific activities"));
    }

    @Test
    void planTreatsBlankAdditionalWishesAsNone() {
        whenGenerateText(p -> p.contains("Additional wishes to be considered: none")).thenReturn("Bali");

        TravelResult result = travelService.plan(new TravelRequest("Southeast Asia", List.of("Beaches"), ""));

        assertThat(result.suggestion()).isEqualTo("Bali");
    }
}
