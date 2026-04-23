package com.example.embabel.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;

@Agent(description = "Suggests a travel destination based on region, activities, and additional wishes")
public class TravelPlannerAgent {

    @AchievesGoal(description = "Travel destination suggestion returned")
    @Action
    String suggestDestination(UserInput userInput, Ai ai) {
        return ai.withAutoLlm()
                .generateText(userInput.getContent());
    }
}
