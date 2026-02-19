package io.github.linarkou.snowone.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parent Document Retriever для StackOverflow RAG.
 *
 * Решает проблему чанкинга:
 * 1. Выполняет векторный поиск → получает релевантные чанки
 * 2. Извлекает уникальные questionId из найденных чанков
 * 3. Для каждого questionId достает ВСЕ чанки (полный контекст)
 * 4. Возвращает полные документы (вопрос + ответ целиком)
 *
 * Это гарантирует, что LLM получит полный контекст, даже если
 * similaritySearch вернул только один чанк из середины документа.
 */
@Slf4j
@RequiredArgsConstructor
public class ParentDocumentRetriever implements DocumentRetriever {

    private final VectorStore vectorStore;
    private final SearchRequest searchRequest;

    @Override
    public List<Document> retrieve(Query query) {
        // 1. Векторный поиск по чанкам
        List<Document> initialResults = vectorStore.similaritySearch(
                SearchRequest.from(searchRequest).query(query.text()).build()
        );

        log.debug("Initial similarity search returned {} chunks", initialResults.size());

        if (initialResults.isEmpty()) {
            return List.of();
        }

        // 2. Извлекаем уникальные questionId из найденных чанков
        Set<Long> questionIds = initialResults.stream()
                .map(doc -> doc.getMetadata().get("questionId"))
                .filter(Objects::nonNull)
                .map(id -> id instanceof Number ? ((Number) id).longValue() : Long.parseLong(id.toString()))
                .collect(Collectors.toSet());

        log.debug("Found {} unique questions in results", questionIds.size());

        // 3. Для каждого questionId достаем ВСЕ его чанки
        List<Document> fullDocuments = questionIds.stream()
                .flatMap(questionId -> getAllChunksByQuestionId(questionId).stream())
                .toList();

        log.info("Expanded {} initial chunks to {} total chunks from {} questions",
                initialResults.size(), fullDocuments.size(), questionIds.size());

        return fullDocuments;
    }

    /**
     * Получает все чанки для заданного questionId
     */
    private List<Document> getAllChunksByQuestionId(Long questionId) {
        try {
            // Создаем фильтр для поиска по questionId
            Filter.Expression filterExpression = new FilterExpressionBuilder()
                    .eq("questionId", questionId)
                    .build();

            // Ищем все документы с этим questionId
            SearchRequest filterRequest = SearchRequest.builder()
                    .query("") // Пустой query, т.к. фильтруем только по metadata
                    .topK(100) // Достаточно для всех чанков одного вопроса
                    .similarityThreshold(0.0) // Без порога, т.к. используем фильтр
                    .filterExpression(filterExpression)
                    .build();

            List<Document> chunks = vectorStore.similaritySearch(filterRequest);
            log.debug("Retrieved {} chunks for questionId {}", chunks.size(), questionId);

            return chunks;
        } catch (Exception e) {
            log.warn("Failed to retrieve chunks for questionId {}: {}", questionId, e.getMessage());
            // Fallback: возвращаем пустой список
            return List.of();
        }
    }
}
