package io.github.linarkou.snowone.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ThinkingTool {

    /**
     * Источник: <a href="https://www.anthropic.com/engineering/claude-think-tool">Think tool</a>
     */
    @Tool(description = """
            Use the tool to think about something.
            It will not obtain new information or change the database,
            but just append the thought to the log.
            Use it when complex reasoning or some cache memory is needed.
            """)
    public String think(@ToolParam(description = "A thought to think about.") String thought) {
        log.info("ThinkingTool thought='{}'", thought);
        return thought;
    }
}
