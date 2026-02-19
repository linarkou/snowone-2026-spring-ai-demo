package io.github.linarkou.snowone.service;

import io.github.linarkou.snowone.guardrails.InputMessageValidatorAdvisor;
import io.github.linarkou.snowone.rag.ParentDocumentRetriever;
import io.github.linarkou.snowone.tool.ThinkingTool;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Сервис для работы с AI чатом
 */
@Service
@Slf4j
public class ChatService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public ChatService(
            ChatClient.Builder chatClientBuilder,
            ThinkingTool thinkingTool,
            List<McpSyncClient> mcpSyncClients,
            VectorStore vectorStore,
            ChatMemory chatMemory,
            InputMessageValidatorAdvisor inputMessageValidatorAdvisor,
            @Value("classpath:prompts/system-prompt.txt") Resource systemPromptResource,
            @Value("classpath:prompts/rag-prompt.txt") Resource ragPromptResource) throws IOException {

        // Загрузка системного промпта из файла
        var systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        log.info("Loaded system prompt from resources ({} characters)", systemPrompt.length());

        // Загрузка промпта для RAG из файла
        var ragPrompt = ragPromptResource.getContentAsString(StandardCharsets.UTF_8);
        log.info("Loaded rag prompt from resources ({} characters)", ragPrompt.length());

        // Создание ParentDocumentRetriever для решения проблемы чанкинга
        ParentDocumentRetriever retriever = new ParentDocumentRetriever(
                vectorStore,
                SearchRequest.builder()
                        .topK(3) // Ищем 3 релевантных вопроса
                        .similarityThreshold(0.6) // порог, относительно которого идет поиск
                        .build()
        );
        // RagAdvisor с кастомными documentRetriever и промптом
        var ragAdvisor = RetrievalAugmentationAdvisor.builder()
                        .documentRetriever(retriever)
                        .queryAugmenter(ContextualQueryAugmenter.builder()
                                .allowEmptyContext(true)
                                .promptTemplate(PromptTemplate.builder().template(ragPrompt).build())
                                .build())
                        .build();

        // Конфигурация ChatClient
        this.chatClient = chatClientBuilder
                .clone()
                .defaultSystem(systemPrompt)
                .defaultTools(thinkingTool)
                .defaultToolCallbacks(SyncMcpToolCallbackProvider.builder()
                        .mcpClients(mcpSyncClients)
                        .toolFilter((info, tool) -> {
                            // Разрешаем только banner_notification и create-gist
                            String toolName = tool.name();
                            return "banner_notification".equals(toolName)
                                || "create-gist".equals(toolName);
                        })
                        .build())
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        inputMessageValidatorAdvisor,
                        ragAdvisor,
                        new SimpleLoggerAdvisor()
                )
                .build();

        this.chatMemory = chatMemory;

        log.info("ChatService initialized with RAG, local and MCP tools, in-memory ChatMemory and input message validator");
    }

    /**
     * Отправить запрос в AI чат
     *
     * @param userMessage сообщение пользователя
     * @return ответ от AI
     * @throws InputMessageValidatorAdvisor.InvalidInputException если сообщение не относится к Java-разработке
     */
    public String chat(String userMessage) {
        log.debug("Processing chat request: {}", userMessage);

        // Отправляем запрос (история управляется через MessageChatMemoryAdvisor)
        String response = chatClient.prompt()
                .user(userMessage)
                .call()
                .content();

        log.debug("Chat response received ({} characters)", response.length());
        return response;
    }

    /**
     * Очистить историю чата
     */
    public void clearHistory() {
        chatMemory.clear(ChatMemory.DEFAULT_CONVERSATION_ID);
        log.info("Cleared chat memory");
    }
}
