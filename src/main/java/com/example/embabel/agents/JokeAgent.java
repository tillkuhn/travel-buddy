package com.example.embabel.agents;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;

@Agent(description = "Generates a joke on any topic and explains why it is funny")
public class JokeAgent {

    public record Joke(String setup, String punchline) {}

    @Action
    Joke generateJoke(UserInput userInput, Ai ai) {
        return ai.withAutoLlm()
            .creating(Joke.class)
            .fromPrompt("""
                Tell a short, clever joke about: %s
                Respond with a JSON object with fields "setup" and "punchline".
                """.formatted(userInput.getContent()).trim());
    }

    @AchievesGoal(description = "Joke delivered with explanation of why it is funny")
    @Action
    String explainJoke(Joke joke, Ai ai) {
        return ai.withAutoLlm()
            .generateText("""
                Here is a joke:
                Setup: %s
                Punchline: %s

                In one sentence, explain why this joke is funny.
                """.formatted(joke.setup(), joke.punchline()).trim());
    }
}
