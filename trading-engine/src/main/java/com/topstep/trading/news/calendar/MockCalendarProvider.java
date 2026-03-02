package com.topstep.trading.news.calendar;

import com.topstep.trading.news.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Mock calendar provider for backtesting and testing.
 * Loads historical economic calendar data from CSV files.
 */
public class MockCalendarProvider implements EconomicCalendarProvider {

    private static final Logger log = LoggerFactory.getLogger(MockCalendarProvider.class);

    private final Map<LocalDate, List<EconomicEvent>> eventsByDate = new HashMap<>();
    private final Map<String, EconomicRelease> releasesById = new HashMap<>();
    private final List<Consumer<EconomicRelease>> releaseCallbacks = new CopyOnWriteArrayList<>();

    private Instant simulatedTime = Instant.now();
    private Instant lastRefresh;
    private final ZoneId defaultZone = ZoneId.of("America/New_York");

    /**
     * Load historical data from CSV file.
     * Expected format: date,time,name,currency,impact,category,forecast,actual,previous,unit
     *
     * @param csvPath Path to the CSV file
     * @throws IOException if file cannot be read
     */
    public void loadFromCsv(Path csvPath) throws IOException {
        log.info("Loading economic calendar from: {}", csvPath);
        int eventsLoaded = 0;
        int releasesLoaded = 0;

        try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
            String line;
            boolean isHeader = true;

            while ((line = reader.readLine()) != null) {
                if (isHeader) {
                    isHeader = false;
                    continue;
                }

                try {
                    ParsedEventLine parsed = parseCsvLine(line);
                    if (parsed != null) {
                        EconomicEvent event = parsed.event;
                        eventsByDate.computeIfAbsent(
                            event.getScheduledTime().atZone(defaultZone).toLocalDate(),
                            k -> new ArrayList<>()
                        ).add(event);
                        eventsLoaded++;

                        if (parsed.release != null) {
                            releasesById.put(event.getId(), parsed.release);
                            releasesLoaded++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse line: {}", line, e);
                }
            }
        }

        lastRefresh = Instant.now();
        log.info("Loaded {} events and {} releases from CSV", eventsLoaded, releasesLoaded);
    }

    /**
     * Add a single event programmatically (useful for tests).
     */
    public void addEvent(EconomicEvent event) {
        LocalDate date = event.getScheduledTime().atZone(defaultZone).toLocalDate();
        eventsByDate.computeIfAbsent(date, k -> new ArrayList<>()).add(event);
    }

    /**
     * Add a single event with its release data (useful for tests).
     */
    public void addEvent(EconomicEvent event, EconomicRelease release) {
        addEvent(event);
        if (release != null) {
            releasesById.put(event.getId(), release);
        }
    }

    /**
     * Set the simulated current time (for backtest replay).
     *
     * @param time The simulated current time
     */
    public void setSimulatedTime(Instant time) {
        this.simulatedTime = time;
    }

    /**
     * Get the current simulated time.
     */
    public Instant getSimulatedTime() {
        return simulatedTime;
    }

    /**
     * Simulate a release occurring (triggers callbacks).
     * Used in backtesting to simulate release timing.
     *
     * @param eventId The event ID for which release occurred
     */
    public void simulateRelease(String eventId) {
        EconomicRelease release = releasesById.get(eventId);
        if (release != null) {
            for (Consumer<EconomicRelease> callback : releaseCallbacks) {
                try {
                    callback.accept(release);
                } catch (Exception e) {
                    log.error("Error in release callback", e);
                }
            }
        }
    }

    @Override
    public List<EconomicEvent> getUpcomingEvents(Instant from, Instant to) {
        List<EconomicEvent> result = new ArrayList<>();

        LocalDate startDate = from.atZone(defaultZone).toLocalDate();
        LocalDate endDate = to.atZone(defaultZone).toLocalDate();

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            List<EconomicEvent> dayEvents = eventsByDate.getOrDefault(date, Collections.emptyList());
            for (EconomicEvent event : dayEvents) {
                Instant eventTime = event.getScheduledTime();
                if (!eventTime.isBefore(from) && eventTime.isBefore(to)) {
                    result.add(event);
                }
            }
        }

        return result.stream()
            .sorted(Comparator.comparing(EconomicEvent::getScheduledTime))
            .collect(Collectors.toList());
    }

    @Override
    public List<EconomicEvent> getUpcomingEvents(int hoursAhead) {
        return getUpcomingEvents(simulatedTime, simulatedTime.plus(Duration.ofHours(hoursAhead)));
    }

    @Override
    public Optional<EconomicRelease> getRelease(String eventId) {
        return Optional.ofNullable(releasesById.get(eventId));
    }

    @Override
    public void subscribeToReleases(Consumer<EconomicRelease> callback) {
        releaseCallbacks.add(callback);
    }

    @Override
    public void unsubscribeFromReleases(Consumer<EconomicRelease> callback) {
        releaseCallbacks.remove(callback);
    }

    @Override
    public void refresh() {
        // No-op for mock provider - data is pre-loaded
        lastRefresh = Instant.now();
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    @Override
    public String getProviderName() {
        return "MockCalendarProvider";
    }

    @Override
    public Optional<Instant> getLastRefreshTime() {
        return Optional.ofNullable(lastRefresh);
    }

    /**
     * Clear all loaded data.
     */
    public void clear() {
        eventsByDate.clear();
        releasesById.clear();
        lastRefresh = null;
    }

    /**
     * Get total number of events loaded.
     */
    public int getEventCount() {
        return eventsByDate.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Get total number of releases loaded.
     */
    public int getReleaseCount() {
        return releasesById.size();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CSV Parsing Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private static class ParsedEventLine {
        EconomicEvent event;
        EconomicRelease release;
    }

    private ParsedEventLine parseCsvLine(String line) {
        String[] parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
        if (parts.length < 5) {
            return null;
        }

        try {
            // date,time,name,currency,impact,category,forecast,actual,previous,unit
            String dateStr = parts[0].trim();
            String timeStr = parts.length > 1 ? parts[1].trim() : "00:00";
            String name = parts.length > 2 ? parts[2].trim().replace("\"", "") : "";
            String currencyStr = parts.length > 3 ? parts[3].trim().toUpperCase() : "USD";
            String impactStr = parts.length > 4 ? parts[4].trim().toUpperCase() : "LOW";
            String categoryStr = parts.length > 5 ? parts[5].trim().toUpperCase() : "OTHER";
            Double forecast = parts.length > 6 ? parseDouble(parts[6]) : null;
            Double actual = parts.length > 7 ? parseDouble(parts[7]) : null;
            Double previous = parts.length > 8 ? parseDouble(parts[8]) : null;
            String unit = parts.length > 9 ? parts[9].trim() : "%";

            // Parse date and time
            LocalDate date = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
            LocalTime time = parseTime(timeStr);
            Instant scheduledTime = ZonedDateTime.of(date, time, defaultZone).toInstant();

            // Parse enums
            Currency currency = parseCurrency(currencyStr);
            EventImpact impact = parseImpact(impactStr);
            EventCategory category = parseCategory(categoryStr);

            // Generate unique ID
            String id = String.format("%s_%s_%s", dateStr, timeStr.replace(":", ""), name.hashCode());

            EconomicEvent event = EconomicEvent.builder()
                .id(id)
                .name(name)
                .currency(currency)
                .impact(impact)
                .category(category)
                .scheduledTime(scheduledTime)
                .forecast(forecast)
                .previous(previous)
                .unit(unit)
                .build();

            ParsedEventLine result = new ParsedEventLine();
            result.event = event;

            // If actual value exists, create release
            if (actual != null) {
                result.release = EconomicRelease.builder()
                    .event(event)
                    .actual(actual)
                    .releaseTime(scheduledTime)
                    .build();
            }

            return result;
        } catch (Exception e) {
            log.debug("Failed to parse CSV line: {}", e.getMessage());
            return null;
        }
    }

    private Double parseDouble(String value) {
        if (value == null || value.trim().isEmpty() || value.trim().equals("-")) {
            return null;
        }
        try {
            // Remove any % or K suffixes
            String cleaned = value.trim()
                .replace("%", "")
                .replace("K", "")
                .replace("M", "")
                .replace("B", "")
                .replace(",", "");
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalTime parseTime(String timeStr) {
        try {
            if (timeStr.contains(":")) {
                String[] parts = timeStr.split(":");
                int hour = Integer.parseInt(parts[0]);
                int minute = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                return LocalTime.of(hour, minute);
            }
            return LocalTime.MIDNIGHT;
        } catch (Exception e) {
            return LocalTime.MIDNIGHT;
        }
    }

    private Currency parseCurrency(String value) {
        try {
            return Currency.valueOf(value.toUpperCase());
        } catch (Exception e) {
            return Currency.USD;
        }
    }

    private EventImpact parseImpact(String value) {
        return switch (value.toUpperCase()) {
            case "HIGH", "3", "H" -> EventImpact.HIGH;
            case "MEDIUM", "2", "M" -> EventImpact.MEDIUM;
            default -> EventImpact.LOW;
        };
    }

    private EventCategory parseCategory(String value) {
        try {
            return EventCategory.valueOf(value.toUpperCase().replace(" ", "_"));
        } catch (Exception e) {
            // Try to infer from string
            String upper = value.toUpperCase();
            if (upper.contains("FED") || upper.contains("FOMC") || upper.contains("RATE")) {
                return EventCategory.CENTRAL_BANK;
            }
            if (upper.contains("CPI") || upper.contains("PPI") || upper.contains("INFLATION") ||
                upper.contains("PCE")) {
                return EventCategory.INFLATION;
            }
            if (upper.contains("NFP") || upper.contains("PAYROLL") || upper.contains("EMPLOY") ||
                upper.contains("JOBLESS") || upper.contains("UNEMPLOYMENT")) {
                return EventCategory.EMPLOYMENT;
            }
            if (upper.contains("GDP") || upper.contains("GROWTH")) {
                return EventCategory.GDP_GROWTH;
            }
            if (upper.contains("PMI") || upper.contains("ISM")) {
                return EventCategory.PMI_SURVEYS;
            }
            if (upper.contains("RETAIL") || upper.contains("CONSUMER") || upper.contains("SENTIMENT")) {
                return EventCategory.CONSUMER;
            }
            if (upper.contains("HOUSING") || upper.contains("HOME")) {
                return EventCategory.HOUSING;
            }
            if (upper.contains("TRADE") || upper.contains("BALANCE")) {
                return EventCategory.TRADE;
            }
            return EventCategory.OTHER;
        }
    }
}
