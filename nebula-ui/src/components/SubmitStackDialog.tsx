import { useState, type ReactNode } from 'react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Plus, Loader2 } from 'lucide-react';
import { submitStack } from '@/lib/api';
import { showSuccessToast, showWarningToast } from '@/lib/toast';

const PLACEHOLDER = `stack {
  http {
    get("/hello") { it.respondText("Hello from nebula") }
  }
}`;

interface SubmitStackDialogProps {
  onSubmitted: () => void;
  /** Prefill the stack name (and lock it — used when fixing a failed stack). */
  initialName?: string;
  /** Prefill the script body. */
  initialScript?: string;
  /** Custom trigger; defaults to a "Submit stack" button. */
  trigger?: ReactNode;
}

export function SubmitStackDialog({
  onSubmitted,
  initialName,
  initialScript,
  trigger,
}: SubmitStackDialogProps) {
  const lockName = initialName !== undefined;
  const [open, setOpen] = useState(false);
  const [name, setName] = useState(initialName ?? '');
  const [script, setScript] = useState(initialScript ?? '');
  const [submitting, setSubmitting] = useState(false);

  const reset = () => {
    setName(initialName ?? '');
    setScript(initialScript ?? '');
    setSubmitting(false);
  };

  const handleSubmit = async () => {
    if (!name.trim() || !script.trim()) return;
    setSubmitting(true);
    try {
      const result = await submitStack(name.trim(), script);
      if (result.ok) {
        showSuccessToast(`Submitted stack "${name.trim()}"`);
      } else {
        showWarningToast(
          `Stack "${name.trim()}" has compilation errors — see its page for details.`,
        );
      }
      setOpen(false);
      reset();
      onSubmitted();
    } catch {
      // handleApiError already surfaced a toast for transport-level failures
      setSubmitting(false);
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) reset();
      }}
    >
      <DialogTrigger asChild>
        {trigger ?? (
          <Button>
            <Plus className="size-4" />
            Submit stack
          </Button>
        )}
      </DialogTrigger>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{lockName ? `Edit “${initialName}”` : 'Submit a stack'}</DialogTitle>
          <DialogDescription>
            {lockName
              ? 'Fix the script and resubmit. A successful compile replaces the failed stack.'
              : 'Give the stack a name and paste a Nebula script. It will be compiled and started immediately.'}
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-4">
          <div className="grid gap-2">
            <Label htmlFor="stack-name">Stack name</Label>
            <Input
              id="stack-name"
              placeholder="my-stack"
              value={name}
              onChange={(e) => setName(e.target.value)}
              autoComplete="off"
              disabled={lockName}
            />
          </div>
          <div className="grid gap-2">
            <Label htmlFor="stack-script">Script</Label>
            <Textarea
              id="stack-script"
              placeholder={PLACEHOLDER}
              value={script}
              onChange={(e) => setScript(e.target.value)}
              className="min-h-64 font-mono text-sm"
              spellCheck={false}
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)} disabled={submitting}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} disabled={submitting || !name.trim() || !script.trim()}>
            {submitting && <Loader2 className="size-4 animate-spin" />}
            Submit &amp; start
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
