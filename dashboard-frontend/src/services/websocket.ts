export interface AccountUpdate {
  type: string;
  timestamp: number;
  balance: number;
  equity: number;
  realizedPnL: number;
  unrealizedPnL: number;
  dailyPnL: number;
  openPositions: number;
  mode: string;
  running: boolean;
  paused: boolean;
  inGoodStanding: boolean;
  remainingDailyLoss: number;
  remainingDrawdown: number;
}

type UpdateCallback = (update: AccountUpdate) => void;

class TradingWebSocket {
  private ws: WebSocket | null = null;
  private callbacks: Set<UpdateCallback> = new Set();
  private reconnectTimeout: number | null = null;
  private reconnectAttempts = 0;

  constructor() {
    // Kick a reconnect when the tab becomes visible or the network returns —
    // otherwise a backend restart while the tab is backgrounded leaves the
    // dashboard silently stale.
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible' && this.callbacks.size > 0) {
          this.reconnectAttempts = 0;
          this.connect();
        }
      });
    }
    if (typeof window !== 'undefined') {
      window.addEventListener('online', () => {
        if (this.callbacks.size > 0) {
          this.reconnectAttempts = 0;
          this.connect();
        }
      });
    }
  }

  connect() {
    if (this.ws?.readyState === WebSocket.OPEN) {
      return;
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/stream`;

    try {
      this.ws = new WebSocket(wsUrl);

      this.ws.onopen = () => {
        console.log('WebSocket connected');
        this.reconnectAttempts = 0;
      };

      this.ws.onmessage = (event) => {
        try {
          const update: AccountUpdate = JSON.parse(event.data);
          this.callbacks.forEach(cb => cb(update));
        } catch (e) {
          console.error('Failed to parse WebSocket message:', e);
        }
      };

      this.ws.onclose = () => {
        console.log('WebSocket disconnected');
        this.scheduleReconnect();
      };

      this.ws.onerror = (error) => {
        console.error('WebSocket error:', error);
      };

    } catch (e) {
      console.error('Failed to create WebSocket:', e);
      this.scheduleReconnect();
    }
  }

  private scheduleReconnect() {
    // Never give up: exponential backoff capped at 30s. A hard cap on
    // attempts left the dashboard permanently stale after ~10 failures
    // (backend restart, laptop sleep) with no way back except a reload.
    if (this.reconnectTimeout) {
      clearTimeout(this.reconnectTimeout);
    }

    const delay = Math.min(1000 * Math.pow(2, Math.min(this.reconnectAttempts, 5)), 30000);
    this.reconnectAttempts++;

    this.reconnectTimeout = window.setTimeout(() => {
      console.log(`Reconnecting... (attempt ${this.reconnectAttempts})`);
      this.connect();
    }, delay);
  }

  subscribe(callback: UpdateCallback) {
    this.callbacks.add(callback);
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      this.connect();
    }
    return () => this.callbacks.delete(callback);
  }

  disconnect() {
    if (this.reconnectTimeout) {
      clearTimeout(this.reconnectTimeout);
    }
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }
}

export const tradingWebSocket = new TradingWebSocket();
