import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Layout } from '@/components/Layout';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { StatusDot } from '@/lib/display';
import { fetchStacks, deleteStack, startStack, stopStack } from '@/lib/api';
import { showSuccessToast } from '@/lib/toast';
import { TRANSITION_STATES, toStackSummary, type StackSummary } from '@/lib/types/stack';
import { Loader2, Square, Play, Trash2 } from 'lucide-react';
import ComponentsView from '@/pages/stack-detail/ComponentsView';
import LogsView from '@/pages/stack-detail/LogsView';
import CompilationErrorsView from '@/pages/stack-detail/CompilationErrorsView';

const POLL_INTERVAL_MS = 2000;

export default function StackDetail() {
  const { name = '' } = useParams();
  const navigate = useNavigate();
  const [stack, setStack] = useState<StackSummary | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const views = await fetchStacks();
      const match = views.find((v) => v.name === name);
      setStack(match ? toStackSummary(match) : null);
    } catch {
      /* toast already shown */
    } finally {
      setLoaded(true);
    }
  }, [name]);

  useEffect(() => {
    load();
    const id = setInterval(load, POLL_INTERVAL_MS);
    return () => clearInterval(id);
  }, [load]);

  const handleStart = async () => {
    setBusy(true);
    try {
      await startStack(name);
      showSuccessToast(`Started stack "${name}"`);
      await load();
    } catch {
      /* toast already shown */
    } finally {
      setBusy(false);
    }
  };

  const handleStop = async () => {
    setBusy(true);
    try {
      await stopStack(name);
      showSuccessToast(`Stopped stack "${name}"`);
      await load();
    } catch {
      /* toast already shown */
    } finally {
      setBusy(false);
    }
  };

  const handleRemove = async () => {
    setBusy(true);
    try {
      await deleteStack(name);
      showSuccessToast(`Removed stack "${name}"`);
      navigate('/');
    } catch {
      setBusy(false);
    }
  };

  return (
    <Layout breadcrumbs={[{ label: 'Stacks', href: '/' }, { label: name }]}>
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <StatusDot state={stack?.overallState} />
          <div>
            <h1 className="text-2xl font-semibold">{name}</h1>
            <p className="text-sm text-muted-foreground">
              {stack
                ? stack.failed
                  ? 'Compilation failed'
                  : `${stack.stateCounts.Running ?? 0}/${stack.total} components running`
                : loaded
                  ? 'Stack not found'
                  : 'Loading…'}
            </p>
          </div>
        </div>
        {stack && (
          <div className="flex items-center gap-2">
            {stack.failed ? (
              <Button variant="outline" onClick={handleRemove} disabled={busy}>
                {busy ? <Loader2 className="size-4 animate-spin" /> : <Trash2 className="size-4" />}
                Remove
              </Button>
            ) : stack.overallState === 'Stopped' ? (
              <>
                <Button variant="outline" onClick={handleStart} disabled={busy}>
                  {busy ? <Loader2 className="size-4 animate-spin" /> : <Play className="size-4" />}
                  Start stack
                </Button>
                <Button variant="ghost" onClick={handleRemove} disabled={busy} title="Remove stack">
                  <Trash2 className="size-4" />
                </Button>
              </>
            ) : (
              <Button
                variant="outline"
                onClick={handleStop}
                disabled={busy || TRANSITION_STATES.includes(stack.overallState)}
              >
                {busy || TRANSITION_STATES.includes(stack.overallState) ? (
                  <Loader2 className="size-4 animate-spin" />
                ) : (
                  <Square className="size-4" />
                )}
                Stop stack
              </Button>
            )}
          </div>
        )}
      </div>

      {!loaded && (
        <div className="flex flex-1 items-center justify-center text-muted-foreground">
          <Loader2 className="mr-2 size-5 animate-spin" /> Loading…
        </div>
      )}

      {loaded && !stack && (
        <div className="flex flex-1 flex-col items-center justify-center gap-3 rounded-lg border border-dashed p-12 text-center">
          <p className="font-medium">This stack is no longer running</p>
          <Button variant="outline" onClick={() => navigate('/')}>
            Back to stacks
          </Button>
        </div>
      )}

      {stack && stack.failed && (
        <CompilationErrorsView stack={stack} onChanged={load} />
      )}

      {stack && !stack.failed && (
        <Tabs defaultValue="components" className="flex flex-1 flex-col">
          <TabsList>
            <TabsTrigger value="components">Components</TabsTrigger>
            <TabsTrigger value="logs">Logs</TabsTrigger>
          </TabsList>
          <TabsContent value="components" className="flex-1">
            <ComponentsView
              stackName={name}
              components={stack.components}
              onChanged={load}
            />
          </TabsContent>
          <TabsContent value="logs" className="flex flex-1 flex-col">
            <LogsView stackName={name} components={stack.components} />
          </TabsContent>
        </Tabs>
      )}
    </Layout>
  );
}
