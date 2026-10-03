package com.documind.documind.rag;

import com.documind.documind.history.QueryLog;
import com.documind.documind.history.QueryLogRepository;
import com.documind.documind.workspace.WorkspaceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/ask")
@RequiredArgsConstructor
@Slf4j
public class RagController {

    private final WorkspaceRepository workspaceRepository;
    private final RagService ragService;
    private final QueryLogRepository queryLogRepository;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public record AskRequest(@NotBlank String question, List<RagService.ChatTurn> history) {}

    public record AskResult(Long queryId, String answer, boolean answered, List<RagService.Source> sources) {}

    @PostMapping
    public AskResult ask(@PathVariable Long workspaceId,
                         @Valid @RequestBody AskRequest req,
                         Authentication authentication) {
        checkOwner(workspaceId, authentication);
        long start = System.currentTimeMillis();
        RagService.AskResponse r = ragService.ask(workspaceId, req.question(),
                authentication.getName(), req.history());
        Double best = r.sources().isEmpty() ? null : r.sources().get(0).score();
        Long id = saveLog(workspaceId, authentication.getName(), req.question(), r.answer(),
                r.answered(), best, System.currentTimeMillis() - start);
        return new AskResult(id, r.answer(), r.answered(), r.sources());
    }

    @PostMapping("/stream")
    public SseEmitter stream(@PathVariable Long workspaceId,
                             @Valid @RequestBody AskRequest req,
                             Authentication authentication) {
        checkOwner(workspaceId, authentication);
        String email = authentication.getName();
        String question = req.question();
        List<RagService.ChatTurn> history = req.history();
        SseEmitter emitter = new SseEmitter(120_000L);
        long start = System.currentTimeMillis();

        executor.execute(() -> {
            try {
                RagService.Prepared p = ragService.prepare(workspaceId, question, email, history);
                if (p.refused()) {
                    finish(emitter, workspaceId, email, question, RagService.REFUSAL_TEXT,
                            false, List.of(), p.bestScore(), start);
                    return;
                }
                StringBuilder full = new StringBuilder();
                ragService.stream(p).subscribe(
                        chunk -> {
                            full.append(chunk);
                            send(emitter, "token", Map.of("t", chunk));
                        },
                        err -> {
                            log.error("Streaming failed", err);
                            send(emitter, "error", Map.of("message",
                                    "The AI service is busy or unavailable. Please try again in a minute."));
                            emitter.complete();
                        },
                        () -> {
                            String answer = full.toString();
                            if (ragService.isRefusal(answer)) {
                                ragService.logGap(workspaceId, question, email, p.bestScore());
                                finish(emitter, workspaceId, email, question, RagService.REFUSAL_TEXT,
                                        false, List.of(), p.bestScore(), start);
                            } else {
                                finish(emitter, workspaceId, email, question,
                                        ragService.markConflict(answer),
                                        true, p.sources(), p.bestScore(), start);
                            }
                        });
            } catch (Exception e) {
                log.error("Stream setup failed", e);
                send(emitter, "error", Map.of("message", "Something went wrong. Please try again."));
                emitter.complete();
            }
        });
        return emitter;
    }

    private void finish(SseEmitter emitter, Long workspaceId, String email, String question,
                        String answer, boolean answered, List<RagService.Source> sources,
                        Double best, long start) {
        Long id = saveLog(workspaceId, email, question, answer, answered, best,
                System.currentTimeMillis() - start);
        Map<String, Object> meta = new HashMap<>();
        meta.put("queryId", id);
        meta.put("answer", answer);
        meta.put("answered", answered);
        meta.put("sources", sources);
        send(emitter, "meta", meta);
        emitter.complete();
    }

    private Long saveLog(Long workspaceId, String email, String question, String answer,
                         boolean answered, Double best, long latency) {
        return queryLogRepository.save(QueryLog.builder()
                .workspaceId(workspaceId)
                .userEmail(email)
                .question(question)
                .answer(answer)
                .answered(answered)
                .bestScore(best)
                .latencyMs(latency)
                .build()).getId();
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
            // the browser closed the connection
        }
    }

    private void checkOwner(Long workspaceId, Authentication authentication) {
        workspaceRepository.findByIdAndOwnerEmail(workspaceId, authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));
    }
}