package io.github.linarkou.snowone.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
@ConditionalOnProperty(prefix = "stackoverflow.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VectorStoreInitializer implements ApplicationRunner {

    private final VectorStore vectorStore;
    private final StackOverflowApi stackOverflowApi;
    private final TextSplitter textSplitter;
    private final String tag;
    private final int initialLoadCount;
    private final int dailyFreshCount;
    private final int pageSize;

    public VectorStoreInitializer(
            VectorStore vectorStore,
            StackOverflowApi stackOverflowApi,
            @Value("${stackoverflow.rag.tag:java}") String tag,
            @Value("${stackoverflow.rag.initial-load-count:1000}") int initialLoadCount,
            @Value("${stackoverflow.rag.daily-fresh-count:20}") int dailyFreshCount,
            @Value("${stackoverflow.rag.page-size:100}") int pageSize,
            @Value("${stackoverflow.rag.chunk-size:1800}") int chunkSize) {
        this.vectorStore = vectorStore;
        this.stackOverflowApi = stackOverflowApi;
        this.tag = tag;
        this.initialLoadCount = initialLoadCount;
        this.dailyFreshCount = dailyFreshCount;
        this.pageSize = pageSize;
        this.textSplitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .build();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Проверяем, инициализирована ли база
        boolean isInitialized = isVectorStoreInitialized();
//        boolean isInitialized = false;

        if (!isInitialized) {
            // Первичная инициализация - загружаем топовые вопросы
            log.info("Vector store not initialized, starting initial load with {} top-voted questions (tag: {}, page size: {})",
                    initialLoadCount, tag, pageSize);
            loadTopQuestions();
            return;
        }

        // База уже инициализирована - проверяем, загружали ли обновление сегодня
        if (hasTodayRecords()) {
            log.info("Vector store already has records for today, skipping daily update");
            return;
        }

        // Загружаем свежие вопросы за сегодня
        log.info("No records for today found, loading {} fresh questions (tag: {})", dailyFreshCount, tag);
        loadDailyFreshQuestions();
    }

    /**
     * Проверяет, содержит ли векторное хранилище данные
     */
    private boolean isVectorStoreInitialized() {
        try {
            // Пробуем выполнить поиск с очень низким порогом
            var results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("test")
                            .topK(1)
                            .similarityThreshold(0.0)
                            .build()
            );
            return !results.isEmpty();
        } catch (Exception e) {
            log.debug("Error checking vector store status, assuming empty: {}", e.getMessage());
            return false;
        }
    }

    private void loadTopQuestions() {
        // Постраничная загрузка топовых вопросов
        List<Question> allQuestions = loadQuestionsWithPagination(initialLoadCount);
        log.info("Downloaded {} StackOverflow questions with answers", allQuestions.size());

        List<Document> documents = allQuestions.stream()
                .filter(q -> !q.answers().isEmpty()) // Только вопросы с ответами
                .flatMap(q -> {
                    List<Document> chunks = questionToDocuments(q);
                    return chunks.stream();
                })
                .toList();

        vectorStore.add(documents);
        log.info("Vector store initialized: {} questions -> {} document chunks",
                allQuestions.size(), documents.size());
    }

    /**
     * Загружает вопросы с пагинацией
     */
    private List<Question> loadQuestionsWithPagination(int totalCount) {
        List<Question> allQuestions = new java.util.ArrayList<>();
        int currentPage = 1;
        int loadedCount = 0;

        log.info("Starting paginated load: target {} questions, page size {}", totalCount, pageSize);

        while (loadedCount < totalCount) {
            try {
                QuestionResponse response = stackOverflowApi.getHighVotesQuestions(tag, currentPage, pageSize);

                if (response == null || response.items() == null || response.items().isEmpty()) {
                    log.warn("No more questions available at page {}", currentPage);
                    break;
                }

                List<Question> pageQuestions = response.items();
                allQuestions.addAll(pageQuestions);
                loadedCount += pageQuestions.size();

                log.info("Loaded page {}: {} questions (total: {}/{})",
                        currentPage, pageQuestions.size(), loadedCount, totalCount);

                // Проверяем, есть ли еще страницы
                if (!response.hasMore()) {
                    log.info("Reached last page of results");
                    break;
                }

                currentPage++;

                // Rate limiting: StackOverflow API имеет лимиты
                if (currentPage > 1) {
                    Thread.sleep(100); // 100ms между запросами
                }

            } catch (Exception e) {
                log.error("Error loading page {}: {}", currentPage, e.getMessage(), e);
                break;
            }
        }

        log.info("Pagination complete: loaded {} questions from {} pages", loadedCount, currentPage);
        return allQuestions;
    }

    /**
     * Проверяет наличие документов за сегодняшний день в векторной БД
     */
    private boolean hasTodayRecords() {
        try {
            String today = LocalDate.now().toString();

            // Создаем фильтр для поиска по indexedDate
            Filter.Expression filterExpression = new FilterExpressionBuilder()
                    .eq("indexedDate", today)
                    .build();

            // Ищем документы с сегодняшней датой
            SearchRequest searchRequest = SearchRequest.builder()
                    .query("") // Пустой query, т.к. фильтруем только по metadata
                    .topK(1)   // Достаточно одного документа для проверки
                    .similarityThreshold(0.0)
                    .filterExpression(filterExpression)
                    .build();

            List<Document> results = vectorStore.similaritySearch(searchRequest);
            boolean hasRecords = !results.isEmpty();

            log.debug("Checked for today's records ({}): {}", today, hasRecords ? "found" : "not found");
            return hasRecords;

        } catch (Exception e) {
            log.warn("Error checking for today's records: {}, assuming no records", e.getMessage());
            return false; // В случае ошибки считаем, что записей нет и пытаемся загрузить
        }
    }

    /**
     * Загружает свежие вопросы за сегодня
     */
    private void loadDailyFreshQuestions() {
        try {
            // Загружаем свежие вопросы
            QuestionResponse latestQuestions = stackOverflowApi.getLatestQuestions(tag, dailyFreshCount);

            if (latestQuestions == null || latestQuestions.items() == null || latestQuestions.items().isEmpty()) {
                log.warn("No fresh questions received from StackOverflow API");
                return;
            }

            List<Question> questions = latestQuestions.items();
            log.info("Downloaded {} fresh StackOverflow questions", questions.size());

            // Обрабатываем вопросы (форматирование + чанкинг)
            List<Document> documents = questions.stream()
                    .filter(q -> !q.answers().isEmpty())
                    .flatMap(q -> {
                        try {
                            var chunks = questionToDocuments(q);
                            return chunks.stream();
                        } catch (Exception e) {
                            log.warn("Failed to process fresh question {}: {}", q.questionId(), e.getMessage());
                            return java.util.stream.Stream.empty();
                        }
                    })
                    .toList();

            if (!documents.isEmpty()) {
                vectorStore.add(documents);
                log.info("Successfully added {} fresh questions ({} document chunks) to vector store",
                        questions.size(), documents.size());
            } else {
                log.warn("No fresh documents to add to vector store");
            }

        } catch (Exception e) {
            log.error("Error loading daily fresh questions: {}", e.getMessage(), e);
        }
    }

    private List<Document> questionToDocuments(Question q) {
        // Берем лучший ответ
        Answer bestAnswer = q.answers().stream()
                .sorted(Answer.BY_ACCEPTED_THEN_SCORE)
                .findFirst()
                .orElseThrow();

        // Формируем документ
        String fullContent = QuestionFormatter.formatQuestionAnswer(q, bestAnswer);

        // Метаданные
        Map<String, Object> baseMetadata = Map.of(
                "questionId", q.questionId(),
                "title", q.title(),
                "link", q.link(),
                "tags", String.join(", ", q.tags()),
                "score", q.score(),
                "answerScore", bestAnswer.score(),
                "isAccepted", bestAnswer.isAccepted(),
                "indexedDate", LocalDate.now().toString()
        );

        // Чанкинг
        Document parentDocument = new Document(fullContent, baseMetadata);
        List<Document> chunks = textSplitter.split(List.of(parentDocument));

        log.debug("Question {} split into {} chunks ({} chars total)",
                q.questionId(), chunks.size(), fullContent.length());

        return chunks;
    }

}
