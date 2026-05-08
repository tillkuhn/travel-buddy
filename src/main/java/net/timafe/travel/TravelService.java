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
        List<String> activities = request.activities() == null ? List.of() : request.activities();

        // Pass structured inputs as a delimited string so the agent's first action
        // can unpack them into a DestinationProfile without involving the LLM.
        String payload = "region=%s|activities=%s|wishes=%s".formatted(
                request.region(),
                String.join(",", activities),
                request.additionalWishes()
        );

        String suggestion = AgentInvocation
                .create(agentPlatform, String.class)
                .invoke(new UserInput(payload));

        // Re-derive the profile locally (pure deterministic) to carry travelSeason into the result.
        DestinationProfile profile = DestinationProfile.from(request.region(), activities, request.additionalWishes());
        return new TravelResult(suggestion, request.region(), activities, request.additionalWishes(), profile.travelSeason());
    }
}
