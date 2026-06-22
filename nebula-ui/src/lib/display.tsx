import { Box, Database, Globe, Server, FileCode } from 'lucide-react';
import type { ComponentState } from './types/stack';

/** Tailwind background colour for a component/stack state dot or badge. */
export function statusColor(state?: ComponentState): string {
  switch (state) {
    case 'Running':
      return 'bg-green-500';
    case 'Starting':
      return 'bg-yellow-500';
    case 'Stopping':
      return 'bg-orange-500';
    case 'Failed':
      return 'bg-red-500';
    case 'Stopped':
    case 'NotStarted':
    default:
      return 'bg-gray-400 dark:bg-gray-600';
  }
}

export function StatusDot({
  state,
  className = '',
}: {
  state?: ComponentState;
  className?: string;
}) {
  const pulse = state === 'Starting' || state === 'Stopping' ? 'animate-pulse' : '';
  return <div className={`size-3 rounded-full ${statusColor(state)} ${pulse} ${className}`} />;
}

/** Icon for a component, chosen by its declared type. */
export function getComponentIcon(type: string, className = 'size-5') {
  switch (type.toLowerCase()) {
    case 'postgres':
    case 'mysql':
    case 'mongo':
    case 'mongodb':
    case 'database':
      return <Database className={className} />;
    case 'http':
    case 'api':
      return <Globe className={className} />;
    case 'kafka':
    case 'hazelcast':
    case 'redis':
    case 's3':
    case 'localstack':
    case 'cache':
      return <Server className={className} />;
    case 'taxi':
      return <FileCode className={className} />;
    default:
      return <Box className={className} />;
  }
}
