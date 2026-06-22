import { handleApiError } from './api-error';
import type { AdminStackView, CompilationError, StackStateEvent } from './types/stack';

/**
 * Client for the Nebula server's admin API. All requests are same-origin:
 * in production the Nebula server serves this UI; in dev, Vite proxies /api
 * to the server (see vite.config.ts).
 */

/** Unified snapshot: compiled stacks (with live state) + failed submissions. */
export async function fetchStacks(): Promise<AdminStackView[]> {
  const response = await fetch('/api/stacks');
  if (!response.ok) {
    await handleApiError(response, 'Failed to load stacks');
  }
  return response.json();
}

export type SubmitResult =
  | { ok: true }
  | { ok: false; compilationErrors: CompilationError[] };

/**
 * Submit a Nebula script as a named stack. Compiles and starts it, replacing
 * any existing stack with the same name. A compilation failure is returned as
 * `{ ok: false }` (the failed submission is then visible in the snapshot) rather
 * than thrown — only transport/other errors throw.
 */
export async function submitStack(name: string, script: string): Promise<SubmitResult> {
  const response = await fetch(`/api/stacks/${encodeURIComponent(name)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'text/plain' },
    body: script,
  });
  if (response.status === 422) {
    const body = await response.json();
    return { ok: false, compilationErrors: body.compilationErrors ?? [] };
  }
  if (!response.ok) {
    await handleApiError(response, 'Failed to submit stack');
  }
  return { ok: true };
}

/** Start a whole stack that was previously stopped. */
export async function startStack(name: string): Promise<AdminStackView[]> {
  return stackAction(name, 'start', 'Failed to start stack');
}

/** Stop a whole stack (it stays listed as Stopped, and can be started again). */
export async function stopStack(name: string): Promise<AdminStackView[]> {
  return stackAction(name, 'stop', 'Failed to stop stack');
}

async function stackAction(
  name: string,
  action: 'start' | 'stop',
  errorMessage: string,
): Promise<AdminStackView[]> {
  const response = await fetch(`/api/stacks/${encodeURIComponent(name)}/${action}`, {
    method: 'POST',
  });
  if (!response.ok) {
    await handleApiError(response, errorMessage);
  }
  return response.json();
}

/** Remove an entire stack (stopping it first), or clear a failed submission. */
export async function deleteStack(name: string): Promise<AdminStackView[]> {
  const response = await fetch(`/api/stacks/${encodeURIComponent(name)}`, {
    method: 'DELETE',
  });
  if (!response.ok) {
    await handleApiError(response, 'Failed to remove stack');
  }
  return response.json();
}

/** Stop a single component within a stack. */
export async function stopComponent(
  stackName: string,
  componentId: string,
): Promise<StackStateEvent> {
  return componentAction(stackName, componentId, 'stop');
}

/** (Re)start a single component within a stack. */
export async function startComponent(
  stackName: string,
  componentId: string,
): Promise<StackStateEvent> {
  return componentAction(stackName, componentId, 'start');
}

async function componentAction(
  stackName: string,
  componentId: string,
  action: 'start' | 'stop',
): Promise<StackStateEvent> {
  const response = await fetch(
    `/api/stacks/${encodeURIComponent(stackName)}/components/${encodeURIComponent(
      componentId,
    )}/${action}`,
    { method: 'POST' },
  );
  if (!response.ok) {
    await handleApiError(response, `Failed to ${action} component`);
  }
  return response.json();
}

/** Path for the per-stack log websocket. */
export function logsWebSocketPath(stackName: string): string {
  return `/api/stacks/${encodeURIComponent(stackName)}/logs`;
}
