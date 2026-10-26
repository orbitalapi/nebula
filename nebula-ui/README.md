# nebula-ui

A lightweight management UI for a Nebula server. It lets you see which stacks are
submitted and running, inspect and control individual components, and stream logs.

It talks to the HTTP API exposed when Nebula is started with an HTTP port:

```bash
nebula --http=8099   # 8099 is the default
```

Start it with `--stack-start=manual` (or `NEBULA_STACK_START=manual`) and submitted
stacks wait as **Not started** until you start them from the UI. By default they start
as soon as they're submitted.

## Tech

React 19 + Vite + TypeScript + Tailwind 4 + shadcn/ui.

## Development

```bash
npm install
npm run dev          # http://localhost:5173
```

The dev server proxies `/api` and `/health` (including websockets) to the Nebula
server. By default it targets `http://localhost:8099`; override with:

```bash
NEBULA_SERVER=http://localhost:9000 npm run dev
```

## Production

You normally don't build this by hand. The `nebula-runtime` Maven module builds
this app (via `frontend-maven-plugin`) and bundles `dist/` into its jar under
`/web`, where `NebulaServer` serves it at the HTTP port. So `mvn install` from the
repo root produces a Nebula build that serves this UI at `http://localhost:8099/`.

To build manually:

```bash
npm run build        # outputs to dist/
```

## API surface used

- `GET /api/stacks` — snapshot of all stacks + per-component lifecycle state
- `PUT /stacks/{id}` — submit & start a stack from a script
- `DELETE /api/stacks/{id}` — stop & remove a stack
- `POST /api/stacks/{id}/components/{componentId}/{start|stop}` — per-component control
- `WS /api/stacks/{id}/logs` — live log stream
