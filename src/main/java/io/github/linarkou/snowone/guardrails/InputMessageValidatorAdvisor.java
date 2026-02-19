package io.github.linarkou.snowone.guardrails;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Валидатор входящих сообщений - проверяет что вопросы только по Java-разработке
 */
@Slf4j
@Component
public class InputMessageValidatorAdvisor implements BaseAdvisor {

    public static final Integer ORDER = DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 1;

    private static final String VALIDATION_PROMPT = """
            Ты - валидатор входящих сообщений для AI-ассистента по Java-разработке.

            Твоя задача - определить, относится ли переписка пользователя MESSAGE_HISTORY к Java-разработке.
            Переписка пользователя описана в блоке MESSAGE_HISTORY.

            Разрешенные темы:
            - Программирование на Java
            - Spring Framework (Spring Boot, Spring Data, Spring Security и т.д.)
            - Java библиотеки и фреймворки
            - JVM, GraalVM, настройки производительности
            - Базы данных в контексте Java (JDBC, JPA, Hibernate)
            - Инструменты разработки (Maven, Gradle, IntelliJ IDEA)
            - Паттерны проектирования в Java
            - Тестирование Java приложений
            - Архитектура Java приложений

            Запрещенные темы:
            - Другие языки программирования (Python, JavaScript, C++, и т.д.)
            - Общие вопросы не связанные с программированием
            - Личные вопросы
            - Политика, новости, развлечения
            - Любые темы не связанные с Java-разработкой

            Ответь ТОЛЬКО одним словом:
            - "VALID" - если вопрос относится к Java-разработке
            - "INVALID" - если вопрос не относится к Java-разработке

            MESSAGE_HISTORY:
            %s

            Твой ответ (VALID или INVALID):
            """;

    private final ChatClient validationChatClient;

    public InputMessageValidatorAdvisor(ChatClient.Builder chatClientBuilder) {
        // Создаем отдельный ChatClient для валидации (без advisors)
        this.validationChatClient = chatClientBuilder.build();
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
            String validationPrompt = String.format(VALIDATION_PROMPT, input);

            String validationResponse = validationChatClient.prompt()
                    .user(validationPrompt)
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
        return ORDER;
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
