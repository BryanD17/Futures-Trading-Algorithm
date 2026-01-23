package com.topstep.trading.news.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.topstep.trading.news.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Implementation using Trading Economics API.
 * API docs: https://docs.tradingeconomics.com/
 *
 * For live implementation, requires API key.
 * Supports REST polling for calendar events.
 *
 * Note: WebSocket streaming for releases requires separate subscription.
 */
public class TradingEconomicsProvider implements EconomicCalendarProvider {

    private static final Logger log = LoggerFactory.getLogger(TradingEconomicsProvider.class);

    private static final String BASE_URL = "https://api.tradingeconomics.com";
    private static final String CALENDAR_ENDPOINT = "/calendar";

    // Currencies we're interested in
    private static final Set<String> RELEVANT_CURRENCIES = Set.of("USD", "EUR", "JPY", "GBP", "CNY");

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration refreshInterval;
    private final Duration requestTimeout;

    private List<EconomicEvent> cachedEvents = new ArrayList<>();
    private final Map<String, EconomicRelease> releasesById = new HashMap<>();
    private Instant lastRefresh;
    private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private final List<Consumer<EconomicRelease>> releaseCallbacks = new CopyOnWriteArrayList<>();

    private volatile boolean healthy = false;

    /**
     * Create provider with API key and default settings.
     */
    public TradingEconomicsProvider(String apiKey) {
        this(apiKey, Duration.ofMinutes(5), Duration.ofSeconds(30));
    }

    /**
     * Create provider with custom settings.
     */
    public TradingEconomicsProvider(String apiKey, Duration refreshInterval, Duration requestTimeout) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey cannot be null");
        this.refreshInterval = refreshInterval;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(requestTimeout)
            .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<EconomicEvent> getUpcomingEvents(Instant from, Instant to) {
        refreshIfNeeded();

        cacheLock.readLock().lock();
        try {
            return cachedEvents.stream()
                .filter(e -> !e.getScheduledTime().isBefore(from) && e.getScheduledTime().isBefore(to))
                .sorted(Comparator.comparing(EconomicEvent::getScheduledTime))
                .collect(Collectors.toList());
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    @Override
    public Optional<EconomicRelease> getRelease(String eventId) {
        // Try to fetch from API if not cached
        cacheLock.readLock().lock();
        try {
            if (releasesById.containsKey(eventId)) {
                return Optional.of(releasesById.get(eventId));
            }
        } finally {
            cacheLock.readLock().unlock();
        }

        // Could implement specific release fetching here
        // For now, releases are populated during calendar refresh
        return Optional.empty();
    }

    @Override
    public void subscribeToReleases(Consumer<EconomicRelease> callback) {
        releaseCallbacks.add(callback);
        // Note: Real-time streaming would require WebSocket implementation
        log.info("Subscribed to releases. Note: Using polling, not real-time streaming.");
    }

    @Override
    public void unsubscribeFromReleases(Consumer<EconomicRelease> callback) {
        releaseCallbacks.remove(callback);
    }

    @Override
    public void refresh() {
        log.debug("Refreshing calendar data from Trading Economics");

        try {
            // Get events for next 7 days
            LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
            LocalDate endDate = today.plusDays(7);

            String url = buildCalendarUrl(today, endDate);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(requestTimeout)
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                List<EconomicEvent> events = parseCalendarResponse(response.body());

                cacheLock.writeLock().lock();
                try {
                    // Check for new releases (events that now have actual values)
                    detectNewReleases(events);
                    cachedEvents = events;
                    lastRefresh = Instant.now();
                    healthy = true;
                } finally {
                    cacheLock.writeLock().unlock();
                }

                log.info("Refreshed calendar: {} events loaded", events.size());
            } else {
                log.error("Calendar API returned status {}: {}", response.statusCode(), response.body());
                healthy = false;
            }
        } catch (IOException | InterruptedException e) {
            log.error("Failed to refresh calendar", e);
            healthy = false;
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isHealthy() {
        return healthy && lastRefresh != null &&
               Duration.between(lastRefresh, Instant.now()).compareTo(refreshInterval.multipliedBy(3)) < 0;
    }

    @Override
    public String getProviderName() {
        return "TradingEconomics";
    }

    @Override
    public Optional<Instant> getLastRefreshTime() {
        return Optional.ofNullable(lastRefresh);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private Helper Methods
    // ═══════════════════════════════════════════════════════════════════════

    private void refreshIfNeeded() {
        if (lastRefresh == null ||
            Duration.between(lastRefresh, Instant.now()).compareTo(refreshInterval) > 0) {
            refresh();
        }
    }

    private String buildCalendarUrl(LocalDate startDate, LocalDate endDate) {
        // Format: /calendar?c=USD,EUR,JPY&importance=2,3&d1=2024-01-01&d2=2024-01-07
        String currencies = String.join(",", RELEVANT_CURRENCIES);
        String start = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String end = endDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

        return String.format("%s%s?c=%s&importance=2,3&d1=%s&d2=%s&client=%s",
            BASE_URL, CALENDAR_ENDPOINT, currencies, start, end, apiKey);
    }

    private List<EconomicEvent> parseCalendarResponse(String json) {
        List<EconomicEvent> events = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    EconomicEvent event = parseEvent(node);
                    if (event != null) {
                        events.add(event);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse calendar response", e);
        }

        return events;
    }

    private EconomicEvent parseEvent(JsonNode node) {
        try {
            String calendarId = node.has("CalendarId") ? node.get("CalendarId").asText() : null;
            String event = node.has("Event") ? node.get("Event").asText() : null;
            String country = node.has("Country") ? node.get("Country").asText() : null;
            String category = node.has("Category") ? node.get("Category").asText() : null;
            String dateStr = node.has("Date") ? node.get("Date").asText() : null;
            int importance = node.has("Importance") ? node.get("Importance").asInt() : 1;

            Double forecast = node.has("Forecast") && !node.get("Forecast").isNull()
                ? node.get("Forecast").asDouble() : null;
            Double previous = node.has("Previous") && !node.get("Previous").isNull()
                ? node.get("Previous").asDouble() : null;
            Double actual = node.has("Actual") && !node.get("Actual").isNull()
                ? node.get("Actual").asDouble() : null;
            String unit = node.has("Unit") ? node.get("Unit").asText() : "%";

            if (calendarId == null || event == null || dateStr == null) {
                return null;
            }

            // Parse date/time
            Instant scheduledTime = parseDateTime(dateStr);
            if (scheduledTime == null) {
                return null;
            }

            // Map country to currency
            Currency currency = mapCountryToCurrency(country);

            // Map importance to impact
            EventImpact impact = switch (importance) {
                case 3 -> EventImpact.HIGH;
                case 2 -> EventImpact.MEDIUM;
                default -> EventImpact.LOW;
            };

            // Map category string to enum
            EventCategory eventCategory = inferCategory(event, category);

            EconomicEvent economicEvent = EconomicEvent.builder()
                .id(calendarId)
                .name(event)
                .currency(currency)
                .impact(impact)
                .category(eventCategory)
                .scheduledTime(scheduledTime)
                .forecast(forecast)
                .previous(previous)
                .unit(unit)
                .build();

            // If we have an actual value, also store the release
            if (actual != null) {
                EconomicRelease release = EconomicRelease.builder()
                    .event(economicEvent)
                    .actual(actual)
                    .releaseTime(scheduledTime)
                    .build();
                releasesById.put(calendarId, release);
            }

            return economicEvent;
        } catch (Exception e) {
            log.debug("Failed to parse event node: {}", e.getMessage());
            return null;
        }
    }

    private Instant parseDateTime(String dateStr) {
        try {
            // Trading Economics uses ISO-8601 format
            return Instant.parse(dateStr);
        } catch (Exception e) {
            try {
                // Fallback: try parsing as local datetime with NY timezone
                LocalDateTime ldt = LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return ldt.atZone(ZoneId.of("America/New_York")).toInstant();
            } catch (Exception e2) {
                log.debug("Failed to parse date: {}", dateStr);
                return null;
            }
        }
    }

    private Currency mapCountryToCurrency(String country) {
        if (country == null) return Currency.USD;

        return switch (country.toLowerCase()) {
            case "united states", "usa", "us" -> Currency.USD;
            case "euro area", "eurozone", "european union", "germany", "france", "italy", "spain" ->
                Currency.EUR;
            case "japan" -> Currency.JPY;
            case "united kingdom", "uk" -> Currency.GBP;
            case "china" -> Currency.CNY;
            case "canada" -> Currency.CAD;
            case "australia" -> Currency.AUD;
            case "new zealand" -> Currency.NZD;
            case "switzerland" -> Currency.CHF;
            default -> Currency.USD;
        };
    }

    private EventCategory inferCategory(String eventName, String categoryStr) {
        String combined = (eventName + " " + (categoryStr != null ? categoryStr : "")).toLowerCase();

        if (combined.contains("fed") || combined.contains("fomc") || combined.contains("rate decision") ||
            combined.contains("ecb") || combined.contains("boj") || combined.contains("boe") ||
            combined.contains("central bank")) {
            return EventCategory.CENTRAL_BANK;
        }
        if (combined.contains("cpi") || combined.contains("ppi") || combined.contains("pce") ||
            combined.contains("inflation")) {
            return EventCategory.INFLATION;
        }
        if (combined.contains("nonfarm") || combined.contains("payroll") || combined.contains("employment") ||
            combined.contains("jobless") || combined.contains("unemployment") || combined.contains("adp")) {
            return EventCategory.EMPLOYMENT;
        }
        if (combined.contains("gdp") || combined.contains("growth")) {
            return EventCategory.GDP_GROWTH;
        }
        if (combined.contains("pmi") || combined.contains("ism")) {
            return EventCategory.PMI_SURVEYS;
        }
        if (combined.contains("retail") || combined.contains("consumer") || combined.contains("sentiment") ||
            combined.contains("confidence")) {
            return EventCategory.CONSUMER;
        }
        if (combined.contains("housing") || combined.contains("home sale") || combined.contains("building permit")) {
            return EventCategory.HOUSING;
        }
        if (combined.contains("trade balance") || combined.contains("exports") || combined.contains("imports")) {
            return EventCategory.TRADE;
        }
        if (combined.contains("eia") || combined.contains("crude") || combined.contains("oil inventory")) {
            return EventCategory.ENERGY;
        }

        return EventCategory.OTHER;
    }

    private void detectNewReleases(List<EconomicEvent> newEvents) {
        // Compare with cached events to detect new releases
        Map<String, EconomicEvent> oldEventMap = cachedEvents.stream()
            .collect(Collectors.toMap(EconomicEvent::getId, e -> e));

        for (EconomicEvent newEvent : newEvents) {
            EconomicRelease newRelease = releasesById.get(newEvent.getId());
            if (newRelease != null) {
                // Check if this is a newly appeared release
                EconomicEvent oldEvent = oldEventMap.get(newEvent.getId());
                if (oldEvent != null) {
                    EconomicRelease oldRelease = releasesById.get(oldEvent.getId());
                    if (oldRelease == null || oldRelease.getActual() == null) {
                        // This is a new release - notify callbacks
                        notifyReleaseCallbacks(newRelease);
                    }
                }
            }
        }
    }

    private void notifyReleaseCallbacks(EconomicRelease release) {
        log.info("New release detected: {} = {}", release.getEvent().getName(), release.getActual());
        for (Consumer<EconomicRelease> callback : releaseCallbacks) {
            try {
                callback.accept(release);
            } catch (Exception e) {
                log.error("Error in release callback", e);
            }
        }
    }
}
