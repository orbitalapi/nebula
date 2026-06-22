import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Layout } from '@/components/Layout';
import { SubmitStackDialog } from '@/components/SubmitStackDialog';
import { Button } from '@/components/ui/button';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { StatusDot } from '@/lib/display';
import { fetchStacks, deleteStack, startStack, stopStack } from '@/lib/api';
import { showSuccessToast } from '@/lib/toast';
import { TRANSITION_STATES, toStackSummary, type StackSummary } from '@/lib/types/stack';
import { Boxes, RefreshCw, Square, Play, Loader2, Trash2 } from 'lucide-react';

const POLL_INTERVAL_MS = 2000;

export default function StacksList() {
  const [stacks, setStacks] = useState<StackSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<Record<string, boolean>>({});

  const load = useCallback(async (showSpinner = false) => {
    if (showSpinner) setStacks(null);
    try {
      const events = await fetchStacks();
      setStacks(events.map(toStackSummary).sort((a, b) => a.name.localeCompare(b.name)));
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load stacks');
    }
  }, []);

  useEffect(() => {
    load();
    const id = setInterval(() => load(), POLL_INTERVAL_MS);
    return () => clearInterval(id);
  }, [load]);

  const runAction = async (
    name: string,
    action: (name: string) => Promise<unknown>,
    successMessage: string,
  ) => {
    setBusy((b) => ({ ...b, [name]: true }));
    try {
      await action(name);
      showSuccessToast(successMessage);
      await load();
    } catch {
      /* toast already shown */
    } finally {
      setBusy((b) => ({ ...b, [name]: false }));
    }
  };

  const handleStart = (name: string) => runAction(name, startStack, `Started stack "${name}"`);
  const handleStop = (name: string) => runAction(name, stopStack, `Stopped stack "${name}"`);
  const handleRemove = (name: string) => runAction(name, deleteStack, `Removed stack "${name}"`);

  return (
    <Layout breadcrumbs={[{ label: 'Stacks' }]}>
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Stacks</h1>
          <p className="text-sm text-muted-foreground">
            Stacks submitted to this Nebula server.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="icon" onClick={() => load(true)} title="Refresh">
            <RefreshCw className="size-4" />
          </Button>
          <SubmitStackDialog onSubmitted={() => load()} />
        </div>
      </div>

      {error && (
        <div className="rounded-lg border border-destructive/50 bg-destructive/10 p-4 text-sm">
          <p className="font-medium text-destructive">Couldn’t reach the Nebula server</p>
          <p className="text-muted-foreground">{error}</p>
          <Button variant="outline" size="sm" className="mt-3" onClick={() => load(true)}>
            Retry
          </Button>
        </div>
      )}

      {!error && stacks === null && (
        <div className="flex flex-1 items-center justify-center text-muted-foreground">
          <Loader2 className="mr-2 size-5 animate-spin" /> Loading stacks…
        </div>
      )}

      {!error && stacks !== null && stacks.length === 0 && (
        <div className="flex flex-1 flex-col items-center justify-center gap-3 rounded-lg border border-dashed p-12 text-center">
          <Boxes className="size-10 text-muted-foreground" />
          <div>
            <p className="font-medium">No stacks running</p>
            <p className="text-sm text-muted-foreground">
              Submit a stack to get started, or push one via the CLI / API.
            </p>
          </div>
          <SubmitStackDialog onSubmitted={() => load()} />
        </div>
      )}

      {!error && stacks !== null && stacks.length > 0 && (
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Stack</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Components</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {stacks.map((stack) => (
                <TableRow key={stack.name}>
                  <TableCell>
                    <Link
                      to={`/stacks/${encodeURIComponent(stack.name)}`}
                      className="font-medium hover:underline"
                    >
                      {stack.name}
                    </Link>
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <StatusDot state={stack.overallState} />
                      <span className="text-sm">{stack.overallState}</span>
                    </div>
                  </TableCell>
                  <TableCell className="text-sm text-muted-foreground">
                    {stack.failed
                      ? `${stack.compilationErrors.length} compilation error${
                          stack.compilationErrors.length === 1 ? '' : 's'
                        }`
                      : `${stack.stateCounts.Running ?? 0}/${stack.total} running`}
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="flex justify-end gap-2">
                      {(() => {
                        const isBusy = busy[stack.name];
                        const inTransition = TRANSITION_STATES.includes(stack.overallState);
                        const spinner = <Loader2 className="size-4 animate-spin" />;
                        if (stack.failed) {
                          return (
                            <Button
                              variant="outline"
                              size="sm"
                              onClick={() => handleRemove(stack.name)}
                              disabled={isBusy}
                            >
                              {isBusy ? spinner : <Trash2 className="size-4" />}
                              Remove
                            </Button>
                          );
                        }
                        if (stack.overallState === 'Stopped') {
                          return (
                            <>
                              <Button
                                variant="outline"
                                size="sm"
                                onClick={() => handleStart(stack.name)}
                                disabled={isBusy}
                              >
                                {isBusy ? spinner : <Play className="size-4" />}
                                Start
                              </Button>
                              <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => handleRemove(stack.name)}
                                disabled={isBusy}
                                title="Remove stack"
                              >
                                <Trash2 className="size-4" />
                              </Button>
                            </>
                          );
                        }
                        return (
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => handleStop(stack.name)}
                            disabled={isBusy || inTransition}
                          >
                            {isBusy || inTransition ? spinner : <Square className="size-4" />}
                            Stop
                          </Button>
                        );
                      })()}
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </Layout>
  );
}
