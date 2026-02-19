package io.github.linarkou.snowone.controller;

import io.github.linarkou.snowone.service.ChatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST контроллер для работы с AI чатом
 */
@RestController
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * Тестовый эндпоинт с фиксированным запросом
     */
    @PostMapping("/test")
    public String test() {
        return chatService.chat("""
                Напиши пример Java-кода с использованием Spring Boot и Spring AI.
                Так напиши вспомогательные конфигурационные и другие файлы, если требуется.
                Сохрани результат в github gist.
                """);
    }

    /**
     * Эндпоинт для произвольных запросов
     */
    @PostMapping("/chat")
    public String chat(@RequestBody String userMessage) {
        return chatService.chat(userMessage);
    }

    /**
     * Эндпоинт для очистки истории переписки
     */
    @PostMapping("/clear")
    public void clear() {
        chatService.clearHistory();
    }
}
