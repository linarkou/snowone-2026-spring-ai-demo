package io.github.linarkou.snowone.guardrails;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Валидатор входящих сообщений - проверяет что вопросы только по Java-разработке
 */
@Slf4j
@Component
public class InputMessageValidatorAdvisor implements BaseAdvisor {

    private final ChatClient validationChatClient;
    private final String validationPrompt;

    public InputMessageValidatorAdvisor(ChatClient.Builder chatClientBuilder,
                                        @Value("classpath:prompts/validation-prompt.txt") Resource promptResource) throws IOException {
        // Создаем отдельный ChatClient для валидации (без advisors)
        this.validationChatClient = chatClientBuilder.build();
        this.validationPrompt = promptResource.getContentAsString(StandardCharsets.UTF_8);
        log.info("InputMessageValidator initialized");
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        String messageHistory = chatClientRequest.prompt().getInstructions()
                .stream()
                .filter(msg -> msg.getMessageType().equals(MessageType.USER)
                        || msg.getMessageType().equals(MessageType.ASSISTANT))
                .map(msg -> "- " + msg.getMessageType().getValue() + ": `" + msg.getText() + "`")
                .collect(Collectors.joining("\n\n"));

        if (messageHistory.trim().isEmpty()) {
            return chatClientRequest; // Пустые сообщения пропускаем
        }

        // Валидация входящего сообщения
        boolean isValid = isValid(messageHistory);
        if (!isValid) {
            throw new InputMessageValidatorAdvisor.InvalidInputException(
                    "Извините, я могу отвечать только на вопросы по Java-разработке. " +
                            "Пожалуйста, задайте вопрос связанный с Java, Spring Framework или связанными технологиями."
            );
        }

        return chatClientRequest;
    }

    private boolean isValid(String input) {
        log.debug("Validating user message");

        try {
            String prompt = String.format(this.validationPrompt, input);

            String validationResponse = validationChatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            log.debug("Validation response: {}", validationResponse);

            // Парсим ответ - ищем VALID или INVALID
            String normalizedResponse = validationResponse.trim().toUpperCase();

            boolean isValid;
            if (normalizedResponse.contains("VALID") && !normalizedResponse.contains("INVALID")) {
                isValid = true;
            } else if (normalizedResponse.contains("INVALID")) {
                isValid = false;
            } else {
                // Если ответ неоднозначный - по умолчанию разрешаем (fail-open)
                log.warn("Ambiguous validation response: {}, defaulting to VALID", validationResponse);
                isValid = true;
            }

            log.info("Message validation result: {}", isValid ? "VALID" : "INVALID");
            return isValid;

        } catch (Exception e) {
            log.error("Error during validation, defaulting to VALID (fail-open)", e);
            // Fail-open: если валидация не работает, пропускаем сообщение
            return true;
        }
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        return DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 1;
    }

    /**
     * Исключение для невалидного ввода
     */
    public static class InvalidInputException extends RuntimeException {
        public InvalidInputException(String message) {
            super(message);
        }
    }
}
