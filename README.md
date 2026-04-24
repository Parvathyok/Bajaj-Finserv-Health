# Quiz Leaderboard System — SRM Internship Assignment

A Java application that polls a quiz API 10 times, deduplicates events, aggregates scores, and submits a correct leaderboard.

---

## Problem Summary

- Poll `GET /quiz/messages` **10 times** (poll index 0–9)
- The API may return **duplicate events** across polls
- Deduplicate using composite key: `roundId + participant`
- Aggregate scores per participant
- Submit sorted leaderboard once via `POST /quiz/submit`

---

## Project Structure

```
quiz-leaderboard/
├── pom.xml
├── README.md
└── src/
    └── main/
        └── java/
            └── com/
                └── quiz/
                    └── QuizLeaderboardApp.java
```

---

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 17 or higher |
| Maven | 3.8+ |

---

## Setup & Run

### 1. Clone the repository

```bash
git clone https://github.com/your-username/quiz-leaderboard.git
cd quiz-leaderboard
```

### 2. Set your Registration Number

Open `src/main/java/com/quiz/QuizLeaderboardApp.java` and update:

```java
private static final String REG_NO = "RA2311026050020";  // e.g., "2024CS101"
```

### 3. Build the project

```bash
mvn clean package
```

This creates a fat JAR at `target/quiz-leaderboard-1.0.0.jar`.

### 4. Run the application

```bash
java -jar target/quiz-leaderboard-1.0.0.jar
```

**Total runtime:** ~50 seconds (10 polls × 5s delay)

---

## How It Works

### Algorithm

```
for poll = 0 to 9:
    response = GET /quiz/messages?regNo=REG_NO&poll=poll
    for each event in response.events:
        key = event.roundId + "::" + event.participant
        if key NOT in seen:
            seen.add(key)
            scoreMap[participant] += score
        else:
            IGNORE (duplicate)
    wait 5 seconds

leaderboard = sort scoreMap by totalScore DESC
POST /quiz/submit  { regNo, leaderboard }
```

### Duplicate Handling

The same `roundId + participant` pair may appear in multiple polls. The app uses a `LinkedHashMap<String, Integer>` keyed by `"roundId::participant"` to track seen events. If a key already exists, the event is silently ignored.

```
Poll 0 → R1::Alice score=10  ✅ Added
Poll 3 → R1::Alice score=10  ❌ Duplicate → Ignored
Final Alice total = 10  ✓ Correct
```

### Score Aggregation

After deduplication, scores are summed per participant:

```java
scoreMap.merge(participant, score, Integer::sum);
```

### Leaderboard Sorting

Participants are sorted by `totalScore` in **descending order**:

```java
sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
```

---

## Sample Output

```
=== Quiz Leaderboard System ===
Registration Number: 2024CS101

Polling API [0/9]...
  setId=SET_1  pollIndex=0
  → New events: 2  |  Duplicates ignored: 0
  Waiting 5 seconds...

...

Polling API [9/9]...
  setId=SET_1  pollIndex=9
    [DUPLICATE] Ignored → roundId=R1  participant=Alice  score=10
  → New events: 1  |  Duplicates ignored: 1

=== Leaderboard ===
  Bob                  : 120
  Alice                : 100
  ──────────────────────────
  Combined Total Score : 220

Submitting leaderboard...
  HTTP Status : 200
  Response    : {"isCorrect":true,"isIdempotent":false,...}

✅ SUCCESS! Leaderboard accepted.
   Submitted Total : 220
   Expected  Total : 220
   Message         : Correct!
```

---

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| `jackson-databind` | 2.16.1 | JSON parsing |
| Java `java.net.http` | JDK 17 built-in | HTTP client |

---

## Key Design Decisions

| Decision | Reason |
|----------|--------|
| `LinkedHashMap` for dedup | Preserves insertion order, O(1) lookup |
| Composite key `roundId::participant` | Matches the spec's dedup requirement exactly |
| 5-second mandatory delay | Required by API spec |
| Single submission | `POST /quiz/submit` called exactly once |
| Fat JAR via maven-shade | Self-contained, no classpath issues |

---

## Author

**Your Name**  
Registration No: `YOUR_REG_NO`  
SRM Institute of Science and Technology
