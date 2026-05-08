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
        TravelRequest tReq = new TravelRequest(request.region(), activities, request.additionalWishes());

        String suggestion = AgentInvocation
                // Create a new AgentInvocation for the given platform and explicit result type.
                .create(agentPlatform, String.class)
                .invoke(tReq);

        DestinationProfile profile = DestinationProfile.from(tReq.region(), tReq.activities(), tReq.additionalWishes());
        return new TravelResult(suggestion, tReq.region(), tReq.activities(), tReq.additionalWishes(), profile.travelSeason());
    }
}
