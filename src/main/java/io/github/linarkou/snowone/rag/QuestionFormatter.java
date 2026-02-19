package io.github.linarkou.snowone.rag;

/**
 * Утилитный класс для форматирования вопросов и ответов StackOverflow
 */
public class QuestionFormatter {

    /**
     * Форматирует вопрос и ответ в структурированный Markdown текст
     */
    public static String formatQuestionAnswer(Question question, Answer answer) {
        return String.format("""
                # %s

                **Question (Score: %d):**
                %s

                **Tags:** %s

                **Answer (Score: %d%s):**
                %s

                **Link:** %s
                """,
                question.title(),
                question.score(),
                question.bodyMarkdown(),
                String.join(", ", question.tags()),
                answer.score(),
                answer.isAccepted() ? ", Accepted" : "",
                answer.bodyMarkdown(),
                question.link()
        );
    }

    private QuestionFormatter() {
        // Utility class - prevent instantiation
    }
}
