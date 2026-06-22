import { AlertTriangle, Pencil } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { SubmitStackDialog } from '@/components/SubmitStackDialog';
import type { StackSummary } from '@/lib/types/stack';

interface CompilationErrorsViewProps {
  stack: StackSummary;
  onChanged: () => void;
}

export default function CompilationErrorsView({ stack, onChanged }: CompilationErrorsViewProps) {
  return (
    <div className="flex flex-1 flex-col gap-4">
      <div className="rounded-lg border border-destructive/50 bg-destructive/10 p-4">
        <div className="mb-3 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <AlertTriangle className="size-5 text-destructive" />
            <h3 className="font-semibold text-destructive">
              {stack.compilationErrors.length} compilation{' '}
              {stack.compilationErrors.length === 1 ? 'error' : 'errors'}
            </h3>
          </div>
          <SubmitStackDialog
            onSubmitted={onChanged}
            initialName={stack.name}
            initialScript={stack.source}
            trigger={
              <Button size="sm">
                <Pencil className="size-4" />
                Edit &amp; resubmit
              </Button>
            }
          />
        </div>
        <ul className="space-y-2">
          {stack.compilationErrors.map((err, idx) => (
            <li key={idx} className="rounded bg-background/60 px-3 py-2 text-sm">
              <span className="mr-2 font-mono text-xs font-semibold text-destructive">
                {err.severity}
                {err.line != null && `  ${err.line}:${err.column ?? 0}`}
              </span>
              <span className="font-mono">{err.message}</span>
            </li>
          ))}
        </ul>
      </div>

      <div className="flex min-h-0 flex-1 flex-col rounded-lg border bg-card">
        <div className="border-b bg-muted/30 p-3">
          <h4 className="text-sm font-semibold">Submitted script</h4>
        </div>
        <pre className="flex-1 overflow-auto p-4 font-mono text-xs leading-relaxed">
          {stack.source || '(no source captured)'}
        </pre>
      </div>
    </div>
  );
}
