import { useState, type MouseEvent } from 'react';
import {
  ChevronDown,
  ChevronRight,
  Copy,
  Check,
  Play,
  Square,
  Loader2,
  Wrench,
  ExternalLink,
  AlertCircle,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { showSuccessToast } from '@/lib/toast';
import { getComponentIcon, StatusDot } from '@/lib/display';
import { launchTool, startComponent, stopComponent, stopTool, toolUrl } from '@/lib/api';
import {
  TRANSITION_STATES,
  stateLabel,
  type ComponentInfoWithState,
  type ToolView,
} from '@/lib/types/stack';

interface ComponentsViewProps {
  stackName: string;
  components: ComponentInfoWithState[];
  /** Tools offered by each running component, keyed by component id. */
  tools: Record<string, ToolView[]>;
  onChanged: () => void;
}

/** Launch / stop actions for a component's tools, shared by the menu and the panel. */
function useToolActions(stackName: string, componentId: string, onChanged: () => void) {
  const [pending, setPending] = useState<string | null>(null);

  const launch = async (tool: ToolView) => {
    setPending(tool.id);
    try {
      await launchTool(stackName, componentId, tool.id);
      showSuccessToast(`Launching ${tool.displayName} — it'll be ready to open shortly`);
      onChanged();
    } catch {
      /* toast already shown */
    } finally {
      setPending(null);
    }
  };

  const stop = async (tool: ToolView) => {
    setPending(tool.id);
    try {
      await stopTool(stackName, componentId, tool.id);
      showSuccessToast(`Stopped ${tool.displayName}`);
      onChanged();
    } catch {
      /* toast already shown */
    } finally {
      setPending(null);
    }
  };

  return { pending, launch, stop };
}

function ToolsMenu({
  tools,
  actions,
}: {
  tools: ToolView[];
  actions: ReturnType<typeof useToolActions>;
}) {
  const anyStarting = tools.some((t) => t.state === 'Starting');
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="sm" className="flex-shrink-0">
          {anyStarting ? <Loader2 className="size-4 animate-spin" /> : <Wrench className="size-4" />}
          Tools
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-64">
        {tools.map((tool, index) => {
          const url = toolUrl(tool);
          const busy = actions.pending === tool.id;
          return (
            <div key={tool.id}>
              {index > 0 && <DropdownMenuSeparator />}
              <DropdownMenuLabel className="flex flex-col gap-0.5">
                <span>{tool.displayName}</span>
                <span className="text-xs font-normal text-muted-foreground">{tool.description}</span>
              </DropdownMenuLabel>
              {tool.state === 'Running' && url ? (
                <>
                  <DropdownMenuItem onSelect={() => window.open(url, '_blank', 'noopener')}>
                    <ExternalLink className="size-4" /> Open
                  </DropdownMenuItem>
                  <DropdownMenuItem disabled={busy} onSelect={() => actions.stop(tool)}>
                    <Square className="size-4" /> Stop
                  </DropdownMenuItem>
                </>
              ) : tool.state === 'Starting' ? (
                <DropdownMenuItem disabled>
                  <Loader2 className="size-4 animate-spin" /> Starting…
                </DropdownMenuItem>
              ) : (
                <DropdownMenuItem disabled={busy} onSelect={() => actions.launch(tool)}>
                  <Play className="size-4" /> {tool.state === 'Failed' ? 'Retry launch' : 'Launch'}
                </DropdownMenuItem>
              )}
            </div>
          );
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function ToolsPanel({
  tools,
  actions,
}: {
  tools: ToolView[];
  actions: ReturnType<typeof useToolActions>;
}) {
  return (
    <div>
      <h5 className="mb-3 text-sm font-semibold">Tools</h5>
      <div className="grid gap-2">
        {tools.map((tool) => {
          const url = toolUrl(tool);
          const busy = actions.pending === tool.id;
          return (
            <div
              key={tool.id}
              className="flex items-center justify-between gap-4 rounded bg-muted/50 px-3 py-2"
            >
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <span className="text-sm font-medium">{tool.displayName}</span>
                <span className="text-xs text-muted-foreground">{tool.description}</span>
                {tool.state === 'Running' && url && (
                  <a
                    href={url}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="break-all font-mono text-sm text-primary hover:underline"
                  >
                    {url}
                  </a>
                )}
                {tool.state === 'Failed' && (
                  <span className="flex items-center gap-1 text-xs text-destructive">
                    <AlertCircle className="size-3" /> {tool.message ?? 'Failed to start'}
                  </span>
                )}
              </div>
              <div className="flex flex-shrink-0 items-center gap-2">
                {tool.state === 'Running' && url ? (
                  <>
                    <Button variant="outline" size="sm" asChild>
                      <a href={url} target="_blank" rel="noopener noreferrer">
                        <ExternalLink className="size-4" /> Open
                      </a>
                    </Button>
                    <Button variant="ghost" size="sm" disabled={busy} onClick={() => actions.stop(tool)}>
                      {busy ? <Loader2 className="size-4 animate-spin" /> : <Square className="size-4" />}
                      Stop
                    </Button>
                  </>
                ) : tool.state === 'Starting' ? (
                  <Button variant="outline" size="sm" disabled>
                    <Loader2 className="size-4 animate-spin" /> Starting…
                  </Button>
                ) : (
                  <Button variant="outline" size="sm" disabled={busy} onClick={() => actions.launch(tool)}>
                    {busy ? <Loader2 className="size-4 animate-spin" /> : <Play className="size-4" />}
                    {tool.state === 'Failed' ? 'Retry' : 'Launch'}
                  </Button>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function CopyableValue({ label, value }: { label: string; value: string | number | boolean }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(String(value));
      setCopied(true);
      showSuccessToast('Copied to clipboard');
      setTimeout(() => setCopied(false), 2000);
    } catch (error) {
      console.error('Failed to copy:', error);
    }
  };

  return (
    <div className="group flex items-center justify-between rounded bg-muted/50 px-3 py-2 transition-colors hover:bg-muted">
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <span className="text-xs font-medium text-muted-foreground">{label}</span>
        <span className="break-all font-mono text-sm">{String(value)}</span>
      </div>
      <Button
        variant="ghost"
        size="sm"
        className="ml-2 opacity-0 transition-opacity group-hover:opacity-100"
        onClick={handleCopy}
      >
        {copied ? <Check className="size-4 text-green-600" /> : <Copy className="size-4" />}
      </Button>
    </div>
  );
}

function ComponentRow({
  stackName,
  component,
  tools,
  onChanged,
}: {
  stackName: string;
  component: ComponentInfoWithState;
  tools: ToolView[];
  onChanged: () => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [busy, setBusy] = useState(false);
  const toolActions = useToolActions(stackName, component.id, onChanged);

  const state = component.state.state;
  const isRunning = state === 'Running';
  const inTransition = TRANSITION_STATES.includes(state);
  const config = component.componentInfo?.componentConfig ?? {};
  const container = component.componentInfo?.container ?? null;

  const handleAction = async (e: MouseEvent) => {
    e.stopPropagation();
    setBusy(true);
    try {
      if (isRunning) {
        await stopComponent(stackName, component.id);
        showSuccessToast(`Stopped ${component.name}`);
      } else {
        await startComponent(stackName, component.id);
        showSuccessToast(`Started ${component.name}`);
      }
      onChanged();
    } catch {
      /* toast already shown */
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="overflow-hidden rounded-lg border">
      <div className="flex items-center gap-4 p-4 transition-colors hover:bg-muted/50">
        <button
          onClick={() => setExpanded(!expanded)}
          className="flex flex-1 items-center gap-4 text-left"
        >
          <div className="flex-shrink-0">
            {expanded ? <ChevronDown className="size-5" /> : <ChevronRight className="size-5" />}
          </div>
          <div className="flex size-10 flex-shrink-0 items-center justify-center rounded-lg bg-primary/10">
            {getComponentIcon(component.type)}
          </div>
          <div className="min-w-0 flex-1">
            <h4 className="truncate font-semibold">{component.name}</h4>
            <p className="text-sm text-muted-foreground">{component.type}</p>
          </div>
          <div className="hidden flex-shrink-0 flex-col items-end md:flex">
            {container ? (
              <>
                <span className="text-xs text-muted-foreground">Container</span>
                <span className="font-mono text-sm">{container.imageName}</span>
              </>
            ) : (
              <span className="text-xs italic text-muted-foreground">No container</span>
            )}
          </div>
          <div className="flex flex-shrink-0 items-center gap-2">
            <StatusDot state={state} />
            <span className="min-w-[70px] text-sm font-medium">{stateLabel(state)}</span>
          </div>
        </button>
        {tools.length > 0 && <ToolsMenu tools={tools} actions={toolActions} />}
        <Button
          variant="outline"
          size="sm"
          className="flex-shrink-0"
          onClick={handleAction}
          disabled={busy || inTransition}
        >
          {busy || inTransition ? (
            <Loader2 className="size-4 animate-spin" />
          ) : isRunning ? (
            <Square className="size-4" />
          ) : (
            <Play className="size-4" />
          )}
          {isRunning ? 'Stop' : 'Start'}
        </Button>
      </div>

      {expanded && (
        <div className="space-y-6 border-t bg-muted/20 p-6">
          {component.state.message && (
            <div className="rounded bg-muted/50 px-3 py-2 text-sm">
              <span className="text-xs font-medium text-muted-foreground">Status message</span>
              <p className="font-mono">{component.state.message}</p>
            </div>
          )}
          {container && (
            <div>
              <h5 className="mb-3 text-sm font-semibold">Container Details</h5>
              <div className="grid gap-2">
                <CopyableValue label="Container ID" value={container.containerId} />
                <CopyableValue label="Image" value={container.imageName} />
                <CopyableValue label="Name" value={container.containerName} />
                <CopyableValue label="Host" value={container.host} />
              </div>
            </div>
          )}
          {Object.keys(config).length > 0 && (
            <div>
              <h5 className="mb-3 text-sm font-semibold">Connection Configuration</h5>
              <div className="grid gap-2">
                {Object.entries(config).map(([key, value]) => (
                  <CopyableValue key={key} label={key} value={value as string | number | boolean} />
                ))}
              </div>
            </div>
          )}
          {tools.length > 0 && <ToolsPanel tools={tools} actions={toolActions} />}
          <div>
            <h5 className="mb-3 text-sm font-semibold">Metadata</h5>
            <div className="grid gap-2">
              <CopyableValue label="Component ID" value={component.id} />
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default function ComponentsView({
  stackName,
  components,
  tools,
  onChanged,
}: ComponentsViewProps) {
  if (components.length === 0) {
    return (
      <div className="rounded-lg border bg-card p-6">
        <p className="text-muted-foreground">This stack has no components.</p>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {components.map((component) => (
        <ComponentRow
          key={component.id}
          stackName={stackName}
          component={component}
          tools={tools[component.id] ?? []}
          onChanged={onChanged}
        />
      ))}
    </div>
  );
}
