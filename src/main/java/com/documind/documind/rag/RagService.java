package com.documind.documind.rag;

import com.documind.documind.gaps.UnansweredQuestion;
import com.documind.documind.gaps.UnansweredQuestionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class RagService {

    public static final String REFUSAL_TEXT = "I couldn't find this in your documents.";

    private static final String SYSTEM_PROMPT = """
            You are a careful assistant that answers questions using ONLY the provided context.
            Rules:
            - If the context does not contain the answer, reply with exactly one word: NO_ANSWER
            - Never use outside knowledge and never guess.
            - Write your answer in the same language as the question, even if the context is in another language.
            - Cite the sources you used with their numbers in square brackets, like [1] or [2].
            - If sources from different files give different values for the same fact, start your answer with the word CONFLICT: and then state each value with its source number and file name. Do not pick one of them.
            - Keep the answer short and clear.
            """;

    private static final String REWRITE_PROMPT = """
            Rewrite the user's last question as a single standalone question that makes sense without the conversation.
            Keep the same language. Do not answer it. Return only the rewritten question.
            If it is already standalone, return it unchanged.
            """;

    public record Source(int ref, String fileName, Object chunkIndex, double score, String snippet) {}

    public record AskResponse(String answer, boolean answered, List<Source> sources) {}

    public record ChatTurn(String role, String text) {}

    public record Prepared(boolean refused, String question, String context,
                           List<Source> sources, Double bestScore) {}

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final UnansweredQuestionRepository gapsRepository;
    private final double minScore;

    public RagService(VectorStore vectorStore,
                      ChatClient.Builder chatClientBuilder,
                      UnansweredQuestionRepository gapsRepository,
                      @Value("${app.rag.min-score:0.55}") double minScore) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
        this.gapsRepository = gapsRepository;
        this.minScore = minScore;
    }

    public AskResponse ask(Long workspaceId, String question, String userEmail) {
        return ask(workspaceId, question, userEmail, List.of());
    }

    public AskResponse ask(Long workspaceId, String question, String userEmail, List<ChatTurn> history) {
        Prepared p = prepare(workspaceId, question, userEmail, history);
        if (p.refused()) {
            return new AskResponse(REFUSAL_TEXT, false, List.of());
        }

        String answer;
        try {
            answer = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(u -> u.text("Context:\n{context}\n\nQuestion: {question}")
                            .param("context", p.context())
                            .param("question", p.question()))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Model call failed", e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The AI service is busy or unavailable. Please try again in a minute.");
        }

        if (isRefusal(answer)) {
            logGap(workspaceId, question, userEmail, p.bestScore());
            return new AskResponse(REFUSAL_TEXT, false, List.of());
        }
        return new AskResponse(markConflict(answer), true, p.sources());
    }

    /** Rewrites follow-ups, searches the workspace, and builds the context. Logs a gap if nothing is relevant. */
    public Prepared prepare(Long workspaceId, String question, String userEmail, List<ChatTurn> history) {
        String standalone = rewrite(question, history);

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(standalone)
                        .topK(5)
                        .filterExpression("workspaceId == " + workspaceId)
                        .build());

        List<Document> relevant = results.stream()
                .filter(d -> d.getScore() != null && d.getScore() >= minScore)
                .toList();

        if (relevant.isEmpty()) {
            Double best = results.isEmpty() ? null : results.get(0).getScore();
            logGap(workspaceId, question, userEmail, best);
            return new Prepared(true, standalone, "", List.of(), best);
        }

        StringBuilder context = new StringBuilder();
        List<Source> sources = new ArrayList<>();
        int ref = 1;
        for (Document d : relevant) {
            String text = d.getText() == null ? "" : d.getText();
            String fileName = String.valueOf(d.getMetadata().get("fileName"));
            context.append("[").append(ref).append("] (").append(fileName).append(")\n")
                    .append(text).append("\n\n");
            String snippet = text.length() > 200 ? text.substring(0, 200) + "..." : text;
            sources.add(new Source(ref, fileName, d.getMetadata().get("chunkIndex"), d.getScore(), snippet));
            ref++;
        }
        return new Prepared(false, standalone, context.toString(), sources, relevant.get(0).getScore());
    }

    public Flux<String> stream(Prepared p) {
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(u -> u.text("Context:\n{context}\n\nQuestion: {question}")
                        .param("context", p.context())
                        .param("question", p.question()))
                .stream()
                .content();
    }

    public boolean isRefusal(String answer) {
        String normalized = answer == null ? "" : answer.replace('\u2019', '\'').toLowerCase();
        return normalized.isBlank()
                || normalized.contains("no_answer")
                || normalized.contains("couldn't find this in your documents");
    }

    /** Turns the model's CONFLICT: marker into a visible warning for the user. */
    public String markConflict(String answer) {
        if (answer == null) {
            return "";
        }
        String t = answer.stripLeading();
        if (t.regionMatches(true, 0, "CONFLICT:", 0, 9)) {
            return "\u26A0\uFE0F Sources disagree. " + t.substring(9).stripLeading();
        }
        return answer;
    }

    public void logGap(Long workspaceId, String question, String userEmail, Double bestScore) {
        gapsRepository.save(UnansweredQuestion.builder()
                .workspaceId(workspaceId)
                .question(question)
                .bestScore(bestScore)
                .askedBy(userEmail)
                .build());
    }

    private String rewrite(String question, List<ChatTurn> history) {
        if (history == null || history.isEmpty()) {
            return question;
        }
        int from = Math.max(0, history.size() - 6);
        StringBuilder convo = new StringBuilder();
        for (ChatTurn t : history.subList(from, history.size())) {
            String text = t.text() == null ? "" : t.text();
            if (text.length() > 500) {
                text = text.substring(0, 500);
            }
            convo.append("user".equals(t.role()) ? "User: " : "Assistant: ").append(text).append("\n");
        }
        try {
            String rewritten = chatClient.prompt()
                    .system(REWRITE_PROMPT)
                    .user(u -> u.text("Conversation:\n{convo}\n\nLast question: {question}")
                            .param("convo", convo.toString())
                            .param("question", question))
                    .call()
                    .content();
            return (rewritten == null || rewritten.isBlank()) ? question : rewritten.trim();
        } catch (Exception e) {
            log.warn("Question rewrite failed, using original question", e);
            return question;
        }
    }
}