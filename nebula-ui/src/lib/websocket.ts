/**
 * WebSocket helper for the Nebula management UI.
 *
 * Connections are same-origin: in production the Nebula server serves both the
 * UI and the websockets; in dev, Vite proxies them to the server (see
 * vite.config.ts). Includes exponential-backoff retry.
 */

export type WebSocketMessageHandler = (data: unknown) => void;

export interface WebSocketOptions {
  onMessage: WebSocketMessageHandler;
  onOpen?: () => void;
  onError?: (error: Event) => void;
  onClose?: (event: CloseEvent) => void;
}

export interface WebSocketRetryOptions extends WebSocketOptions {
  maxRetries?: number;
  initialDelayMs?: number;
  maxDelayMs?: number;
  onMaxRetriesExceeded?: (path: string) => void;
}

/**
 * Open a WebSocket to a same-origin path (e.g. "/api/stacks/my-stack/logs").
 */
export function createWebSocket(path: string, options: WebSocketOptions): WebSocket {
  const { onMessage, onOpen, onError, onClose } = options;

  const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const wsUrl = `${wsProtocol}//${window.location.host}${path}`;

  const ws = new WebSocket(wsUrl);

  ws.onopen = () => onOpen?.();

  ws.onmessage = (event) => {
    try {
      onMessage(JSON.parse(event.data));
    } catch (error) {
      console.error('Failed to parse WebSocket message:', error);
    }
  };

  ws.onerror = (error) => onError?.(error);
  ws.onclose = (event) => onClose?.(event);

  return ws;
}

/**
 * Open a WebSocket that retries with exponential backoff. Returns a `cancel`
 * function that stops retrying and closes the connection.
 */
export function createWebSocketWithRetry(
  path: string,
  options: WebSocketRetryOptions,
): { cancel: () => void } {
  const {
    maxRetries = 10,
    initialDelayMs = 1000,
    maxDelayMs = 30000,
    onMaxRetriesExceeded,
    ...wsOptions
  } = options;

  let currentWs: WebSocket | null = null;
  let retryCount = 0;
  let retryTimeoutId: number | null = null;
  let cancelled = false;

  const calculateDelay = (attempt: number): number => {
    const delay = Math.min(initialDelayMs * Math.pow(2, attempt), maxDelayMs);
    const jitter = delay * 0.1 * (Math.random() * 2 - 1);
    return Math.floor(delay + jitter);
  };

  const attemptConnection = () => {
    if (cancelled) return;

    currentWs = createWebSocket(path, {
      ...wsOptions,
      onOpen: () => {
        retryCount = 0;
        wsOptions.onOpen?.();
      },
      onError: (error) => wsOptions.onError?.(error),
      onClose: (event) => {
        wsOptions.onClose?.(event);
        if (!cancelled && event.code !== 1000 && retryCount < maxRetries) {
          const delay = calculateDelay(retryCount);
          retryCount++;
          retryTimeoutId = window.setTimeout(attemptConnection, delay);
        } else if (retryCount >= maxRetries) {
          onMaxRetriesExceeded?.(path);
        }
      },
    });
  };

  attemptConnection();

  return {
    cancel: () => {
      cancelled = true;
      if (retryTimeoutId !== null) clearTimeout(retryTimeoutId);
      if (currentWs && currentWs.readyState <= WebSocket.OPEN) currentWs.close();
    },
  };
}
