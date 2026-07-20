package net.timafe.travel.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import net.timafe.travel.DestinationProfile;
import net.timafe.travel.TravelRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Embabel lets you build agentic workflows based on the concept of goals, actions, and conditions.
// The planner sequences actions automatically based on what each action consumes and produces.
@Agent(description = "Suggests a travel destination based on region, activities, and additional wishes")
public class TravelPlannerAgent {

    private static final Logger log = LoggerFactory.getLogger(TravelPlannerAgent.class);

    // Action 1: deterministic — no LLM involved.
    // Parses the raw UserInput payload and enriches it with seasonal context.
    // Producing DestinationProfile onto the blackboard is the only purpose of this action;
    // the planner knows it must run this before suggestDestination can fire.
    @Action
    DestinationProfile buildDestinationProfile(TravelRequest request) {
        DestinationProfile profile = DestinationProfile.from(request.region(), request.activities(), request.additionalWishes());
        log.debug("Built profile for region '{}' with {} activities, best season: {}",
                request.region(), request.activities().size(), profile.travelSeason());
        return profile;
    }

    // Action 2: LLM-powered goal achievement.
    // The planner chains this after buildProfile because it requires DestinationProfile
    // on the blackboard, which only buildProfile can supply.
    @AchievesGoal(description = "Travel destination suggestion returned")
    @Action
    String suggestDestination(DestinationProfile profile, Ai ai) {
        String activitiesList = profile.activities().isEmpty()
                ? "no specific activities"
                : String.join(", ", profile.activities());

        String prompt = """
                You are an expert travel consultant. Based on the following traveler preferences, suggest ONE specific travel destination and explain in 3-5 sentences why it is a great fit.
                If you don't know, say you don't know. Do not guess!

                Region preference: %s
                Best time to visit: %s
                Desired activities: %s
                Additional wishes to be considered: %s

                Provide a concrete destination name and a compelling, personalized recommendation.
                """.formatted(
                profile.region(),
                profile.travelSeason(),
                activitiesList,
                profile.additionalWishes().isBlank() ? "none" : profile.additionalWishes()
        ).trim();

        String content = ai.withAutoLlm().generateText(prompt);
        log.debug("Generated {} chars travel destination suggestion", content.length());
        return content;
    }

}
