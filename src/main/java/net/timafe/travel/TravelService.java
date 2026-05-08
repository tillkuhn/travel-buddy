package net.timafe.travel;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TravelService {

    private final AgentPlatform agentPlatform;

    public TravelService(AgentPlatform agentPlatform) {
        this.agentPlatform = agentPlatform;
    }

    public TravelResult plan(TravelRequest request) {
        List<String> activities = request.activities() == null ? List.of() : request.activities();
        TravelRequest normalized = new TravelRequest(request.region(), activities, request.additionalWishes());

        String suggestion = AgentInvocation
                .create(agentPlatform, String.class)
                .invoke(normalized);

        DestinationProfile profile = DestinationProfile.from(normalized.region(), normalized.activities(), normalized.additionalWishes());
        return new TravelResult(suggestion, normalized.region(), normalized.activities(), normalized.additionalWishes(), profile.travelSeason());
    }
}
