package com.example.embabel;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.domain.io.UserInput;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
public class TravelController {

    private final AgentPlatform agentPlatform;

    public TravelController(AgentPlatform agentPlatform) {
        this.agentPlatform = agentPlatform;
    }

    @GetMapping("/")
    public String form(Model model) {
        model.addAttribute("activities", List.of(
                "Beaches", "Culture", "Cycling", "Diving", "Hiking", "Shopping", "Skiing", "Wildlife"
        ));
        return "index";
    }

    @PostMapping("/plan")
    public String plan(
            @RequestParam String region,
            @RequestParam(required = false) List<String> activities,
            @RequestParam(defaultValue = "") String additionalWishes,
            Model model
    ) {
        String activitiesList = (activities == null || activities.isEmpty())
                ? "no specific activities"
                : String.join(", ", activities);

        String prompt = """
                You are an expert travel consultant. Based on the following traveler preferences, suggest ONE specific travel destination and explain in 3-5 sentences why it is a great fit.

                Region preference: %s
                Desired activities: %s
                Additional wishes: %s

                Provide a concrete destination name and a compelling, personalized recommendation.
                """.formatted(region, activitiesList, additionalWishes.isBlank() ? "none" : additionalWishes).trim();

        String suggestion = AgentInvocation
                .create(agentPlatform, String.class)
                .invoke(new UserInput(prompt));

        model.addAttribute("suggestion", suggestion);
        model.addAttribute("prompt", prompt);
        model.addAttribute("region", region);
        model.addAttribute("activities", activities == null ? List.of() : activities);
        model.addAttribute("additionalWishes", additionalWishes);
        return "result";
    }
}
