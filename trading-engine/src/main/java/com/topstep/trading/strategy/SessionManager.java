package com.topstep.trading.strategy;

import java.time.*;
import java.util.*;

/**
 * Manages trading sessions across different global markets.
 * Automatically selects the best instruments to trade based on session.
 *
 * Sessions and their optimal instruments:
 * - Sydney (5 PM - 2 AM ET): AUD pairs, NZD
 * - Tokyo (7 PM - 4 AM ET): Nikkei, Yen, Asian currencies
 * - London (3 AM - 12 PM ET): Gold, Euro, Oil, GBP - HIGHEST VOLATILITY
 * - New York (8 AM - 5 PM ET): NQ, ES, Oil - US Indices
 *
 * Overlap periods have increased volatility:
 * - Tokyo/London overlap: 3 AM - 4 AM ET
 * - London/NY overlap: 8 AM - 12 PM ET (BEST TIME)
 */
public class SessionManager {

    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");
    private static final ZoneId CT_ZONE = ZoneId.of("America/Chicago");

    /**
     * Trading session definitions.
     */
    public enum Session {
        SYDNEY("Sydney", LocalTime.of(17, 0), LocalTime.of(2, 0), true),      // 5 PM - 2 AM ET
        TOKYO("Tokyo", LocalTime.of(19, 0), LocalTime.of(4, 0), true),        // 7 PM - 4 AM ET
        LONDON("London", LocalTime.of(3, 0), LocalTime.of(12, 0), false),     // 3 AM - 12 PM ET
        NEW_YORK("New York", LocalTime.of(8, 0), LocalTime.of(17, 0), false); // 8 AM - 5 PM ET

        private final String name;
        private final LocalTime start;
        private final LocalTime end;
        private final boolean crossesMidnight;

        Session(String name, LocalTime start, LocalTime end, boolean crossesMidnight) {
            this.name = name;
            this.start = start;
            this.end = end;
            this.crossesMidnight = crossesMidnight;
        }

        public String getName() { return name; }
        public LocalTime getStart() { return start; }
        public LocalTime getEnd() { return end; }
        public boolean crossesMidnight() { return crossesMidnight; }
    }

    // Session to instruments mapping
    private final Map<Session, List<InstrumentConfig>> sessionInstruments;

    // Current active session and instruments
    private Session currentSession;
    private List<InstrumentConfig> activeInstruments;

    public SessionManager() {
        this.sessionInstruments = new HashMap<>();
        initializeSessionInstruments();
        this.activeInstruments = new ArrayList<>();
    }

    /**
     * Initialize the mapping of sessions to their optimal instruments.
     */
    private void initializeSessionInstruments() {
        // Sydney session: Quiet, focus on carry trades
        sessionInstruments.put(Session.SYDNEY, Arrays.asList(
            // Minimal trading during Sydney - low volatility
        ));

        // Tokyo session: Yen pairs, Nikkei
        sessionInstruments.put(Session.TOKYO, Arrays.asList(
            InstrumentConfig.japaneseYen()
        ));

        // London session: Gold, Euro, Oil - highest volatility
        sessionInstruments.put(Session.LONDON, Arrays.asList(
            InstrumentConfig.gold(),
            InstrumentConfig.euroFx(),
            InstrumentConfig.crudeOil()
        ));

        // New York session: US indices
        sessionInstruments.put(Session.NEW_YORK, Arrays.asList(
            InstrumentConfig.nq(),
            InstrumentConfig.es()
        ));
    }

    /**
     * Get the current active session(s) based on the given time.
     */
    public List<Session> getActiveSessions(Instant instant) {
        ZonedDateTime etTime = instant.atZone(ET_ZONE);
        LocalTime time = etTime.toLocalTime();
        List<Session> activeSessions = new ArrayList<>();

        for (Session session : Session.values()) {
            if (isInSession(time, session)) {
                activeSessions.add(session);
            }
        }

        return activeSessions;
    }

    /**
     * Check if the given time is within a session.
     */
    private boolean isInSession(LocalTime time, Session session) {
        if (session.crossesMidnight()) {
            // Session spans midnight
            return !time.isBefore(session.getStart()) || time.isBefore(session.getEnd());
        } else {
            return !time.isBefore(session.getStart()) && time.isBefore(session.getEnd());
        }
    }

    /**
     * Get the primary session (highest priority) for the current time.
     */
    public Session getPrimarySession(Instant instant) {
        List<Session> active = getActiveSessions(instant);

        if (active.isEmpty()) {
            return null;
        }

        // Priority: NY > London > Tokyo > Sydney
        if (active.contains(Session.NEW_YORK)) return Session.NEW_YORK;
        if (active.contains(Session.LONDON)) return Session.LONDON;
        if (active.contains(Session.TOKYO)) return Session.TOKYO;
        if (active.contains(Session.SYDNEY)) return Session.SYDNEY;

        return active.get(0);
    }

    /**
     * Get instruments that should be actively traded now.
     */
    public List<InstrumentConfig> getActiveInstruments(Instant instant) {
        List<Session> activeSessions = getActiveSessions(instant);
        Set<InstrumentConfig> instruments = new LinkedHashSet<>();

        // Add instruments from all active sessions
        for (Session session : activeSessions) {
            List<InstrumentConfig> sessionInsts = sessionInstruments.get(session);
            if (sessionInsts != null) {
                instruments.addAll(sessionInsts);
            }
        }

        return new ArrayList<>(instruments);
    }

    /**
     * Get the best single instrument to trade right now.
     * Considers session, volatility expectations, and overlap periods.
     */
    public InstrumentConfig getBestInstrument(Instant instant) {
        List<InstrumentConfig> instruments = getActiveInstruments(instant);

        if (instruments.isEmpty()) {
            // Default to NQ if nothing else available
            return InstrumentConfig.nq();
        }

        // During London/NY overlap, prefer NQ for highest liquidity
        List<Session> sessions = getActiveSessions(instant);
        if (sessions.contains(Session.LONDON) && sessions.contains(Session.NEW_YORK)) {
            // London/NY overlap - US indices preferred
            return instruments.stream()
                    .filter(i -> i.getSymbol().equals("NQ") || i.getSymbol().equals("ES"))
                    .findFirst()
                    .orElse(instruments.get(0));
        }

        // During London only, prefer Gold
        if (sessions.contains(Session.LONDON) && !sessions.contains(Session.NEW_YORK)) {
            return instruments.stream()
                    .filter(i -> i.getSymbol().equals("GC"))
                    .findFirst()
                    .orElse(instruments.get(0));
        }

        return instruments.get(0);
    }

    /**
     * Check if we're in a session overlap (high volatility period).
     */
    public boolean isSessionOverlap(Instant instant) {
        List<Session> sessions = getActiveSessions(instant);
        return sessions.size() >= 2;
    }

    /**
     * Get session overlap type.
     */
    public String getOverlapType(Instant instant) {
        List<Session> sessions = getActiveSessions(instant);

        if (sessions.contains(Session.LONDON) && sessions.contains(Session.NEW_YORK)) {
            return "LONDON_NY_OVERLAP";
        } else if (sessions.contains(Session.TOKYO) && sessions.contains(Session.LONDON)) {
            return "TOKYO_LONDON_OVERLAP";
        } else if (sessions.size() >= 2) {
            return "SESSION_OVERLAP";
        }

        return "SINGLE_SESSION";
    }

    /**
     * Get volatility multiplier based on session.
     * Used for position sizing adjustments.
     */
    public double getVolatilityMultiplier(Instant instant) {
        List<Session> sessions = getActiveSessions(instant);

        // London/NY overlap has highest volatility
        if (sessions.contains(Session.LONDON) && sessions.contains(Session.NEW_YORK)) {
            return 1.5;
        }

        // London session alone is high volatility
        if (sessions.contains(Session.LONDON)) {
            return 1.3;
        }

        // NY session
        if (sessions.contains(Session.NEW_YORK)) {
            return 1.2;
        }

        // Tokyo session
        if (sessions.contains(Session.TOKYO)) {
            return 0.8;
        }

        // Sydney session is lowest
        if (sessions.contains(Session.SYDNEY)) {
            return 0.5;
        }

        return 1.0;
    }

    /**
     * Check if it's a good time to trade (any major session active).
     */
    public boolean isGoodTradingTime(Instant instant) {
        List<Session> sessions = getActiveSessions(instant);

        // Avoid Sydney-only periods (low volatility)
        if (sessions.size() == 1 && sessions.contains(Session.SYDNEY)) {
            return false;
        }

        // At least one major session should be active
        return sessions.contains(Session.LONDON) ||
               sessions.contains(Session.NEW_YORK) ||
               sessions.contains(Session.TOKYO);
    }

    /**
     * Get a formatted string of current session info.
     */
    public String getSessionInfo(Instant instant) {
        Session primary = getPrimarySession(instant);
        List<Session> all = getActiveSessions(instant);
        boolean overlap = isSessionOverlap(instant);

        if (primary == null) {
            return "No active session";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Session: ").append(primary.getName());

        if (overlap) {
            sb.append(" [").append(getOverlapType(instant)).append("]");
        }

        sb.append(" | Instruments: ");
        List<InstrumentConfig> instruments = getActiveInstruments(instant);
        for (int i = 0; i < instruments.size(); i++) {
            sb.append(instruments.get(i).getSymbol());
            if (i < instruments.size() - 1) sb.append(", ");
        }

        return sb.toString();
    }

    // Getters
    public Session getCurrentSession() { return currentSession; }
    public List<InstrumentConfig> getCurrentInstruments() { return activeInstruments; }
}
