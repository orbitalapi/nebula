import { useEffect, useMemo, useRef, useState } from 'react';
import { Panel, PanelGroup, PanelResizeHandle } from 'react-resizable-panels';
import { List } from 'lucide-react';
import { createWebSocketWithRetry } from '@/lib/websocket';
import { showErrorToast } from '@/lib/toast';
import { getComponentIcon } from '@/lib/display';
import { logsWebSocketPath } from '@/lib/api';
import type { ComponentInfoWithState, LogMessage } from '@/lib/types/stack';

interface LogsViewProps {
  stackName: string;
  components: ComponentInfoWithState[];
}

const ALL = '__all__';
const MAX_LOGS = 5000;

export default function LogsView({ stackName, components }: LogsViewProps) {
  const [selected, setSelected] = useState<string>(ALL);
  const [logs, setLogs] = useState<LogMessage[]>([]);
  const logsEndRef = useRef<HTMLDivElement>(null);

  // One websocket per stack, opened once and kept for the life of the view.
  useEffect(() => {
    setLogs([]);
    const connection = createWebSocketWithRetry(logsWebSocketPath(stackName), {
      onMessage: (data) => {
        const log = data as LogMessage;
        setLogs((prev) => {
          const next = prev.length >= MAX_LOGS ? prev.slice(prev.length - MAX_LOGS + 1) : prev;
          return [...next, log];
        });
      },
      onMaxRetriesExceeded: () => {
        showErrorToast('Failed to connect to the log stream after several attempts.');
      },
    });
    return () => connection.cancel();
  }, [stackName]);

  useEffect(() => {
    logsEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs, selected]);

  // A log belongs to a component if its containerName matches the component's
  // name or its docker container name (logs come from both sources).
  const namesFor = useMemo(() => {
    const map: Record<string, Set<string>> = {};
    for (const c of components) {
      const names = new Set<string>([c.name]);
      const containerName = c.componentInfo?.container?.containerName;
      if (containerName) names.add(containerName);
      map[c.id] = names;
    }
    return map;
  }, [components]);

  const visibleLogs = useMemo(() => {
    if (selected === ALL) return logs;
    const names = namesFor[selected];
    if (!names) return [];
    return logs.filter((l) => names.has(l.containerName));
  }, [logs, selected, namesFor]);

  const countFor = (id: string) => {
    const names = namesFor[id];
    return names ? logs.filter((l) => names.has(l.containerName)).length : 0;
  };

  return (
    <div className="flex h-full min-h-0 flex-1 flex-col rounded-lg border bg-card">
      <PanelGroup direction="horizontal" className="flex-1">
        <Panel defaultSize={25} minSize={18}>
          <div className="flex h-full flex-col border-r">
            <div className="border-b bg-muted/30 p-3">
              <h4 className="text-sm font-semibold">Components</h4>
            </div>
            <div className="space-y-1 p-2">
              <button
                onClick={() => setSelected(ALL)}
                className={`flex w-full items-center gap-3 rounded-md p-3 text-left transition-colors ${
                  selected === ALL ? 'bg-primary/10 text-primary' : 'hover:bg-muted/50'
                }`}
              >
                <List className="size-4" />
                <span className="flex-1 text-sm font-medium">All logs</span>
                <span className="rounded-full bg-primary/20 px-2 py-0.5 text-xs text-primary">
                  {logs.length}
                </span>
              </button>
              {components.map((component) => (
                <button
                  key={component.id}
                  onClick={() => setSelected(component.id)}
                  className={`flex w-full items-center gap-3 rounded-md p-3 text-left transition-colors ${
                    selected === component.id ? 'bg-primary/10 text-primary' : 'hover:bg-muted/50'
                  }`}
                >
                  <div className="flex-shrink-0">{getComponentIcon(component.type, 'size-4')}</div>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-medium">{component.name}</p>
                    <p className="truncate text-xs text-muted-foreground">{component.type}</p>
                  </div>
                  <span className="rounded-full bg-primary/20 px-2 py-0.5 text-xs text-primary">
                    {countFor(component.id)}
                  </span>
                </button>
              ))}
            </div>
          </div>
        </Panel>

        <PanelResizeHandle className="w-2 bg-border transition-colors hover:bg-primary/20" />

        <Panel defaultSize={75}>
          <div className="flex h-full flex-col">
            <div className="flex-shrink-0 border-b bg-muted/30 p-3">
              <h4 className="text-sm font-semibold">
                {selected === ALL
                  ? 'All logs'
                  : components.find((c) => c.id === selected)?.name ?? 'Logs'}
              </h4>
            </div>
            <div className="flex-1 overflow-y-auto">
              <div className="h-full p-4">
                {visibleLogs.length === 0 ? (
                  <div className="flex min-h-[200px] items-center justify-center">
                    <p className="text-sm text-muted-foreground">
                      No logs yet. Output will appear here as it is produced.
                    </p>
                  </div>
                ) : (
                  <div className="space-y-1 font-mono text-xs">
                    {visibleLogs.map((log, idx) => (
                      <div key={idx} className="flex gap-3 rounded px-2 py-1 hover:bg-muted/30">
                        <span className="flex-shrink-0 text-muted-foreground">
                          {new Date(log.timestamp).toLocaleTimeString()}
                        </span>
                        {selected === ALL && (
                          <span className="flex-shrink-0 text-muted-foreground/70">
                            {log.containerName}
                          </span>
                        )}
                        <span
                          className={`flex-1 break-all ${
                            log.streamKind === 'STDERR' ? 'text-red-500' : ''
                          }`}
                        >
                          {log.message}
                        </span>
                      </div>
                    ))}
                    <div ref={logsEndRef} />
                  </div>
                )}
              </div>
            </div>
          </div>
        </Panel>
      </PanelGroup>
    </div>
  );
}
