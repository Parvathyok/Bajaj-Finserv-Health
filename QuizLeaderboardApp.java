package com.quiz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * Quiz Leaderboard System
 *
 * Flow:
 *  1. Poll /quiz/messages 10 times (poll=0..9) with 5s delay between each
 *  2. Deduplicate events using (roundId + participant) as a unique key
 *  3. Aggregate scores per participant
 *  4. Sort leaderboard by totalScore descending
 *  5. Submit leaderboard once to /quiz/submit
 */
public class QuizLeaderboardApp {

    private static final String BASE_URL     = "https://devapigw.vidalhealthtpa.com/srm-quiz-task";
    private static final String REG_NO       = "RA2311026050020";   // <-- Replace with your registration number
    private static final int    TOTAL_POLLS  = 10;
    private static final long   DELAY_MS     = 5_000L;          // 5 seconds mandatory delay

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER    = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        System.out.println("=== Quiz Leaderboard System ===");
        System.out.println("Registration Number: " + REG_NO);
        System.out.println();

        // Step 1 & 2: Poll API and collect all events (deduplicated)
        // Key: "roundId::participant" -> score
        Map<String, Integer> deduplicatedEvents = new LinkedHashMap<>();

        for (int poll = 0; poll < TOTAL_POLLS; poll++) {
            System.out.println("Polling API [" + poll + "/" + (TOTAL_POLLS - 1) + "]...");

            try {
                JsonNode response = pollMessages(REG_NO, poll);
                System.out.println("  setId=" + response.path("setId").asText()
                        + "  pollIndex=" + response.path("pollIndex").asInt());

                JsonNode events = response.path("events");
                int newEvents  = 0;
                int dupEvents  = 0;

                for (JsonNode event : events) {
                    String roundId     = event.path("roundId").asText();
                    String participant = event.path("participant").asText();
                    int    score       = event.path("score").asInt();

                    String key = roundId + "::" + participant;

                    if (deduplicatedEvents.containsKey(key)) {
                        dupEvents++;
                        System.out.println("    [DUPLICATE] Ignored → roundId=" + roundId
                                + "  participant=" + participant + "  score=" + score);
                    } else {
                        deduplicatedEvents.put(key, score);
                        newEvents++;
                    }
                }

                System.out.println("  → New events: " + newEvents + "  |  Duplicates ignored: " + dupEvents);

            } catch (Exception e) {
                System.err.println("  [ERROR] Poll " + poll + " failed: " + e.getMessage());
            }

            // Mandatory 5-second delay (skip after last poll)
            if (poll < TOTAL_POLLS - 1) {
                System.out.println("  Waiting 5 seconds...");
                Thread.sleep(DELAY_MS);
            }
        }

        // Step 3: Aggregate scores per participant
        Map<String, Integer> scoreMap = new HashMap<>();
        for (Map.Entry<String, Integer> entry : deduplicatedEvents.entrySet()) {
            String participant = entry.getKey().split("::")[1];
            scoreMap.merge(participant, entry.getValue(), Integer::sum);
        }

        // Step 4: Sort leaderboard by totalScore descending
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(scoreMap.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        // Step 5: Print leaderboard summary
        System.out.println("\n=== Leaderboard ===");
        long totalScore = 0;
        for (Map.Entry<String, Integer> entry : sorted) {
            System.out.printf("  %-20s : %d%n", entry.getKey(), entry.getValue());
            totalScore += entry.getValue();
        }
        System.out.println("  ──────────────────────────");
        System.out.println("  Combined Total Score       : " + totalScore);
        System.out.println();

        // Step 6: Build leaderboard JSON
        ArrayNode leaderboard = MAPPER.createArrayNode();
        for (Map.Entry<String, Integer> entry : sorted) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("participant", entry.getKey());
            node.put("totalScore", entry.getValue());
            leaderboard.add(node);
        }

        // Step 7: Submit once
        System.out.println("Submitting leaderboard...");
        submitLeaderboard(REG_NO, leaderboard);
    }

    // ─────────────────────────── API calls ────────────────────────────────

    private static JsonNode pollMessages(String regNo, int poll) throws IOException, InterruptedException {
        String url = BASE_URL + "/quiz/messages?regNo=" + regNo + "&poll=" + poll;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from GET /quiz/messages: " + response.body());
        }

        return MAPPER.readTree(response.body());
    }

    private static void submitLeaderboard(String regNo, ArrayNode leaderboard) throws IOException, InterruptedException {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("regNo", regNo);
        payload.set("leaderboard", leaderboard);

        String body = MAPPER.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/quiz/submit"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("  HTTP Status : " + response.statusCode());
        System.out.println("  Response    : " + response.body());

        JsonNode json = MAPPER.readTree(response.body());
        boolean isCorrect   = json.path("isCorrect").asBoolean();
        boolean isIdempotent = json.path("isIdempotent").asBoolean();

        if (isCorrect) {
            System.out.println("\n✅ SUCCESS! Leaderboard accepted.");
            System.out.println("   Submitted Total : " + json.path("submittedTotal").asLong());
            System.out.println("   Expected  Total : " + json.path("expectedTotal").asLong());
            System.out.println("   Message         : " + json.path("message").asText());
        } else {
            System.out.println("\n❌ INCORRECT submission.");
            System.out.println("   Submitted Total : " + json.path("submittedTotal").asLong());
            System.out.println("   Expected  Total : " + json.path("expectedTotal").asLong());
            System.out.println("   Message         : " + json.path("message").asText());
        }

        if (isIdempotent) {
            System.out.println("   ℹ️  isIdempotent=true → duplicate submission detected by server.");
        }
    }
}
