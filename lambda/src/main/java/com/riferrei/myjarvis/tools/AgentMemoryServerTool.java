package com.riferrei.myjarvis.tools;

import dev.langchain4j.agent.tool.Tool;
import com.riferrei.myjarvis.services.MemoryService;

public class AgentMemoryServerTool {

    private final MemoryService memoryService;

    public AgentMemoryServerTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Tool("Check the agent memory server health")
    public boolean checkAgentMemoryServerHealth() {
        return memoryService.checkHealth();
    }
}
