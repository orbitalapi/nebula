/**
 * Type definitions mirroring the Nebula server's API models
 * (com.orbitalhq.nebula.core.*).
 */

export interface ContainerInfo {
  containerId: string;
  imageName: string;
  containerName: string;
  host: string;
}

export interface ComponentConfig {
  [key: string]: string | number | boolean;
}

export interface ComponentInfo<T = ComponentConfig> {
  container: ContainerInfo | null;
  componentConfig: T;
  type: string;
  name: string;
  id: string;
}

export type ComponentState =
  | 'NotStarted'
  | 'Starting'
  | 'Running'
  | 'Stopping'
  | 'Stopped'
  | 'Failed';

/** The states that represent an in-progress transition (show a spinner). */
export const TRANSITION_STATES: ComponentState[] = ['Starting', 'Stopping'];

/** Polymorphic ComponentLifecycleEvent (`@type` discriminator). */
export interface ComponentLifecycleEvent {
  '@type': string;
  state: ComponentState;
  message?: string;
}

export interface ComponentInfoWithState {
  name: string;
  type: string;
  id: string;
  state: ComponentLifecycleEvent;
  componentInfo: ComponentInfo | null;
}

/** Matches StackStateEvent — returned by GET /api/stacks and the events websocket. */
export interface StackStateEvent {
  stackName: string;
  stateCounts: Partial<Record<ComponentState, number>>;
  stackState: Record<string, ComponentInfoWithState[]>;
}

/** A single compilation diagnostic for a failed submission. */
export interface CompilationError {
  message: string;
  line: number | null;
  column: number | null;
  severity: string;
}

/**
 * Unified admin view of a submitted stack — returned by GET /api/stacks.
 * Either a compiled stack (with `stackState`) or a failed submission
 * (with non-empty `compilationErrors`). `source` is the submitted script.
 */
export interface AdminStackView {
  name: string;
  stackState: StackStateEvent | null;
  source: string;
  compilationErrors: CompilationError[];
}

/** A single log line — see com.orbitalhq.nebula.logging.LogMessage. */
export type StreamKind = 'STDOUT' | 'STDERR';

export interface LogMessage {
  containerName: string;
  streamKind: StreamKind;
  message: string;
  timestamp: string;
}

/**
 * A flattened, UI-friendly view of a stack derived from a StackStateEvent:
 * its components (with their lifecycle state) and a roll-up status.
 */
export interface StackSummary {
  name: string;
  components: ComponentInfoWithState[];
  stateCounts: Partial<Record<ComponentState, number>>;
  total: number;
  overallState: ComponentState;
  /** True when the submitted script failed to compile. */
  failed: boolean;
  compilationErrors: CompilationError[];
  /** The script the stack was submitted with. */
  source: string;
}

/**
 * Collapses a stack's per-component states into one headline status.
 * Failed > any transition > Running (if all running) > otherwise Stopped.
 */
export function deriveOverallState(
  components: ComponentInfoWithState[],
): ComponentState {
  if (components.length === 0) return 'Stopped';
  const states = components.map((c) => c.state.state);
  if (states.includes('Failed')) return 'Failed';
  if (states.some((s) => TRANSITION_STATES.includes(s))) return 'Starting';
  if (states.every((s) => s === 'Running')) return 'Running';
  if (states.every((s) => s === 'Stopped' || s === 'NotStarted')) return 'Stopped';
  return 'Running';
}

export function toStackSummary(view: AdminStackView): StackSummary {
  const failed = view.compilationErrors.length > 0 || view.stackState === null;
  const components = view.stackState?.stackState[view.name] ?? [];
  return {
    name: view.name,
    components,
    stateCounts: view.stackState?.stateCounts ?? {},
    total: components.length,
    overallState: failed ? 'Failed' : deriveOverallState(components),
    failed,
    compilationErrors: view.compilationErrors,
    source: view.source,
  };
}
