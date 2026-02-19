package io.github.linarkou.snowone.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Comparator;
import java.util.List;

@Service
public class StackOverflowApi {
    private final RestClient restClient;
    private static final String BASE_URL = "https://api.stackexchange.com/2.3";

    public StackOverflowApi(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.clone()
                .baseUrl(BASE_URL)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * Получение вопросов с высокой оценкой с поддержкой пагинации
     */
    public QuestionResponse getHighVotesQuestions(String tag, int page, int pageSize) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search/advanced")
                        .queryParam("order", "desc")
                        .queryParam("sort", "votes")  // сортировка по оценке
                        .queryParam("answers", 1)  // минимум 1 ответ
                        .queryParam("tagged", tag)  // фильтр по тегу
                        .queryParam("site", "stackoverflow")
//                        .queryParam("fromdate", "1704067200") // начиная с 1 января 2024
                        .queryParam("fromdate", "1577836800") // начиная с 1 января 2020
                        .queryParam("page", page)
                        .queryParam("pagesize", pageSize)
                        .queryParam("filter", "!*236eb_eL9rai)MOSNZ-6D3Q6ZKb0buI*IVotWaTb")  // фильтр с телами ответов
                        .build())
                .retrieve()
                .body(QuestionResponse.class);
    }

    /**
     * Получение самых свежих вопросов с ответами
     */
    public QuestionResponse getLatestQuestions(String tag, int pageSize) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search/advanced")
                        .queryParam("order", "desc")
                        .queryParam("sort", "creation")  // сортировка по дате создания
                        .queryParam("answers", 1)  // минимум 1 ответ
                        .queryParam("tagged", tag)  // фильтр по тегу
                        .queryParam("site", "stackoverflow")
                        .queryParam("pagesize", pageSize)
                        .queryParam("filter", "!*236eb_eL9rai)MOSNZ-6D3Q6ZKb0buI*IVotWaTb")
                        .build())
                .retrieve()
                .body(QuestionResponse.class);
    }
}

// DTO классы для десериализации
record QuestionResponse(
        List<Question> items,
        @JsonProperty("has_more") boolean hasMore,
        @JsonProperty("quota_max") int quotaMax,
        @JsonProperty("quota_remaining") int quotaRemaining
) {}

record Question(
        @JsonProperty("question_id") long questionId,
        String title,
        String link,
        @JsonProperty("is_answered") boolean isAnswered,
        @JsonProperty("view_count") int viewCount,
        @JsonProperty("answer_count") int answerCount,
        @JsonProperty("favorite_count") int favoriteCount,
        int score,
        @JsonProperty("creation_date") long creationDate,
        @JsonProperty("last_activity_date") Long lastActivityDate,  // может отсутствовать
        List<String> tags,
        Owner owner,
        @JsonProperty("body_markdown") String bodyMarkdown,
        List<Answer> answers
) {}

record AnswerResponse(
        List<Answer> items,
        @JsonProperty("has_more") boolean hasMore,
        @JsonProperty("quota_max") int quotaMax,
        @JsonProperty("quota_remaining") int quotaRemaining
) {}

record Answer(
        @JsonProperty("answer_id") long answerId,
        @JsonProperty("question_id") long questionId,
        @JsonProperty("is_accepted") boolean isAccepted,
        int score,
        @JsonProperty("creation_date") long creationDate,
        @JsonProperty("last_activity_date") Long lastActivityDate,  // может отсутствовать
        String body,
        @JsonProperty("body_markdown") String bodyMarkdown,
        String title,  // заголовок вопроса, к которому относится ответ
        Owner owner
) {

    // Компаратор: сначала по accepted (принятые первыми), затем по score (большие первыми)
    public static final Comparator<Answer> BY_ACCEPTED_THEN_SCORE =
            Comparator.comparing(Answer::isAccepted).reversed()
                    .thenComparing(Answer::score, Comparator.reverseOrder());
}

record Owner(
        @JsonProperty("account_id") long accountId,
        @JsonProperty("user_id") long userId,
        @JsonProperty("user_type") String userType,
        @JsonProperty("display_name") String displayName,
        int reputation,
        @JsonProperty("profile_image") String profileImage,
        String link
) {}
