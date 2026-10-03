package com.documind.documind.history;

import com.documind.documind.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final WorkspaceRepository workspaceRepository;
    private final QueryLogRepository queryLogRepository;

    public record DayCount(String date, long count) {}

    public record TopQuestion(String question, long count) {}

    public record Analytics(long total, long answered, long refused, double refusalRate,
                            long avgLatencyMs, long thumbsUp, long thumbsDown,
                            List<TopQuestion> topQuestions, List<DayCount> perDay) {}

    @GetMapping
    public Analytics get(@PathVariable Long workspaceId, Authentication authentication) {
        workspaceRepository.findByIdAndOwnerEmail(workspaceId, authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));

        List<QueryLog> logs = queryLogRepository.findByWorkspaceId(workspaceId);

        long total = logs.size();
        long answered = logs.stream().filter(QueryLog::isAnswered).count();
        long refused = total - answered;
        double refusalRate = total == 0 ? 0 : Math.round(refused * 1000.0 / total) / 10.0;
        long avgLatency = (long) logs.stream()
                .filter(l -> l.getLatencyMs() != null)
                .mapToLong(QueryLog::getLatencyMs)
                .average().orElse(0);
        long up = logs.stream().filter(l -> Integer.valueOf(1).equals(l.getFeedback())).count();
        long down = logs.stream().filter(l -> Integer.valueOf(-1).equals(l.getFeedback())).count();

        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, String> display = new HashMap<>();
        for (QueryLog l : logs) {
            if (l.getQuestion() == null || l.getQuestion().isBlank()) {
                continue;
            }
            String key = l.getQuestion().trim().toLowerCase();
            counts.merge(key, 1L, Long::sum);
            display.putIfAbsent(key, l.getQuestion().trim());
        }
        List<TopQuestion> top = counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(e -> new TopQuestion(display.get(e.getKey()), e.getValue()))
                .toList();

        ZoneId zone = ZoneId.systemDefault();
        Map<LocalDate, Long> byDay = new HashMap<>();
        for (QueryLog l : logs) {
            if (l.getCreatedAt() != null) {
                byDay.merge(l.getCreatedAt().atZone(zone).toLocalDate(), 1L, Long::sum);
            }
        }
        List<DayCount> perDay = new ArrayList<>();
        LocalDate today = LocalDate.now(zone);
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            perDay.add(new DayCount(day.toString(), byDay.getOrDefault(day, 0L)));
        }

        return new Analytics(total, answered, refused, refusalRate, avgLatency, up, down, top, perDay);
    }
}