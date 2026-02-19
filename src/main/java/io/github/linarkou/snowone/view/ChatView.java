package io.github.linarkou.snowone.view;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.messages.MessageListItem;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.annotation.UIScope;
import io.github.linarkou.snowone.guardrails.InputMessageValidatorAdvisor;
import io.github.linarkou.snowone.service.ChatService;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Vaadin view для AI чата
 */
@Route("")
@PageTitle("Spring AI Assistant")
@UIScope
@Slf4j
public class ChatView extends VerticalLayout {

    private final ChatService chatService;
    private final MessageList messageList;
    private final MessageInput messageInput;
    private final List<MessageListItem> messages = new ArrayList<>();

    public ChatView(ChatService chatService) {
        this.chatService = chatService;

        // Настройка layout
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        // Заголовок
        H2 title = new H2("Spring AI Assistant");
        title.getStyle()
                .set("margin", "0")
                .set("color", "#1976D2");

        // Кнопка очистки истории
        Button clearButton = new Button("Очистить историю");
        clearButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
        clearButton.addClickListener(e -> clearHistory());

        // Header layout
        HorizontalLayout header = new HorizontalLayout(title, clearButton);
        header.setWidthFull();
        header.setJustifyContentMode(JustifyContentMode.BETWEEN);
        header.setAlignItems(Alignment.CENTER);

        // Message list
        messageList = new MessageList();
        messageList.setSizeFull();
        messageList.getStyle()
                .set("flex-grow", "1")
                .set("overflow-y", "auto");

        // Message input
        messageInput = new MessageInput();
        messageInput.setWidthFull();
        messageInput.addSubmitListener(this::handleUserMessage);

        // Добавление приветственного сообщения
        addAssistantMessage("Привет! Я AI ассистент для Java-разработчиков. Чем могу помочь?");

        // Сборка layout
        add(header, messageList, messageInput);

        // Фокус на поле ввода
        messageInput.focus();
    }

    /**
     * Обработка сообщения пользователя
     */
    private void handleUserMessage(MessageInput.SubmitEvent event) {
        String userMessage = event.getValue();

        if (userMessage == null || userMessage.trim().isEmpty()) {
            return;
        }

        log.info("User message: {}", userMessage);

        // Добавляем сообщение пользователя
        addUserMessage(userMessage);

        // Отключаем input на время обработки
        messageInput.setEnabled(false);

        // Обработка в отдельном потоке
        getUI().ifPresent(ui -> {
            new Thread(() -> {
                try {
                    log.info("Sending request to ChatService...");
                    // Отправляем запрос к AI
                    String response = chatService.chat(userMessage);

                    log.info("Received response: {} characters", response != null ? response.length() : 0);

                    // Обновляем UI в UI thread
                    ui.access(() -> {
                        try {
                            if (response != null && !response.isEmpty()) {
                                log.info("Adding assistant message to UI");
                                addAssistantMessage(response);
                            } else {
                                log.warn("Response is empty!");
                                showError("Получен пустой ответ от AI");
                            }
                            messageInput.setEnabled(true);
                            messageInput.focus();
                        } catch (Exception e) {
                            log.error("Error updating UI", e);
                            showError("Ошибка обновления UI: " + e.getMessage());
                        }
                    });
                } catch (InputMessageValidatorAdvisor.InvalidInputException e) {
                    // Ошибка валидации - показываем сообщение пользователю
                    log.warn("Input validation failed: {}", e.getMessage());
                    ui.access(() -> {
                        showValidationError(e.getMessage());
                        messageInput.setEnabled(true);
                        messageInput.focus();
                    });
                } catch (Exception e) {
                    log.error("Error processing chat request", e);
                    ui.access(() -> {
                        showError("Ошибка при обработке запроса: " + e.getMessage());
                        messageInput.setEnabled(true);
                        messageInput.focus();
                    });
                }
            }).start();
        });
    }

    /**
     * Добавить сообщение пользователя
     */
    private void addUserMessage(String text) {
        MessageListItem message = new MessageListItem(
                text,
                Instant.now(),
                "Вы"
        );
        message.setUserColorIndex(1);
        messages.add(message);
        messageList.setItems(messages);
    }

    /**
     * Добавить сообщение ассистента
     */
    private void addAssistantMessage(String text) {
        log.debug("Adding assistant message: {} chars", text != null ? text.length() : 0);
        MessageListItem message = new MessageListItem(
                text,
                Instant.now(),
                "AI Assistant"
        );
        message.setUserColorIndex(2);
        messages.add(message);
        messageList.setItems(messages);
        log.debug("Message list updated, total messages: {}", messages.size());
    }

    /**
     * Очистить историю чата
     */
    private void clearHistory() {
        chatService.clearHistory();
        messages.clear();
        messageList.setItems(messages);

        // Добавляем приветственное сообщение снова
        addAssistantMessage("Привет! Я AI ассистент для Java-разработчиков. Чем могу помочь?");

        Notification.show("История чата очищена")
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }

    /**
     * Показать ошибку
     */
    private void showError(String message) {
        Notification notification = new Notification(message, 5000);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        notification.setPosition(Notification.Position.TOP_CENTER);
        notification.open();
    }

    /**
     * Показать ошибку валидации (более мягкое предупреждение)
     */
    private void showValidationError(String message) {
        Notification notification = new Notification(message, 7000);
        notification.addThemeVariants(NotificationVariant.LUMO_CONTRAST);
        notification.setPosition(Notification.Position.MIDDLE);
        notification.open();
    }
}
