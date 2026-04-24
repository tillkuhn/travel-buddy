package net.timafe.travel;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.domain.io.UserInput;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TravelService {

    private final AgentPlatform agentPlatform;

    public TravelService(AgentPlatform agentPlatform) {
        this.agentPlatform = agentPlatform;
    }

    public TravelResult plan(TravelRequest request) {
        String activitiesList = (request.activities() == null || request.activities().isEmpty())
                ? "no specific activities"
                : String.join(", ", request.activities());

        String prompt = """
                You are an expert travel consultant. Based on the following traveler preferences, suggest ONE specific travel destination and explain in 3-5 sentences why it is a great fit.
                If you don't know, say you don't know. Do not guess!
                
                Region preference: %s
                Desired activities: %s
                Additional wishes: %s
                
                Provide a concrete destination name and a compelling, personalized recommendation.
                """.formatted(request.region(), activitiesList,
                request.additionalWishes().isBlank() ? "none" : request.additionalWishes()).trim();

        String suggestion = AgentInvocation
                .create(agentPlatform, String.class)
                .invoke(new UserInput(prompt));

        List<String> activities = request.activities() == null ? List.of() : request.activities();
        return new TravelResult(suggestion, prompt, request.region(), activities, request.additionalWishes());
    }
}
