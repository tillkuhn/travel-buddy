package com.example.embabel;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.domain.io.UserInput;
import com.example.embabel.agents.JokeAgent;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

@ShellComponent
record DemoShell(AgentPlatform agentPlatform) {

    @ShellMethod("Tell a joke about a topic")
    String joke(@ShellOption(defaultValue = "software developers") String topic) {
        return AgentInvocation
            .create(agentPlatform, String.class)
            .invoke(new UserInput(topic));
    }
}
