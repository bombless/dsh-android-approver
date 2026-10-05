import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { randomUUID } from 'node:crypto'
import { WebSocketServer, type WebSocket } from 'ws'
import type { Context } from '@deepseek-ai/cordis'

export const name = 'dsh-android-approver'

export interface Config {
  host: '127.0.0.1' | '0.0.0.0'
  port: number
  token: string
  approvalTimeoutMs: number
}

type Outcome = 'allowed-once' | 'rejected' | 'cancelled' | 'unavailable'

interface Task {
  id: string
  sessionId: string
  cwd?: string
  startedAt: number
  status: 'running'
}

interface Approval {
  id: string
  taskId: string
  sessionId: string
  toolName: string
  reason?: string
  createdAt: number
  expiresAt: number
  callId?: string
}

interface Pending extends Approval {
  resolve: (outcome: Outcome) => void
  settled: boolean
  timer: NodeJS.Timeout
}

const DEFAULTS: Config = {
  host: '0.0.0.0',
  port: 38741,
  token: '',
  approvalTimeoutMs: 120000,
}

function json(res: ServerResponse, status: number, body: unknown) {
  const payload = JSON.stringify(body)
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'content-length': Buffer.byteLength(payload),
  })
  res.end(payload)
}

function safeEqual(a: string, b: string): boolean {
  if (!a || !b || a.length !== b.length) return false
  let diff = 0
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i)
  return diff === 0
}

function authorized(req: IncomingMessage, token: string): boolean {
  const value = req.headers.authorization
  return typeof value === 'string' &&
    value.startsWith('Bearer ') &&
    safeEqual(value.slice('Bearer '.length), token)
}

export function apply(ctx: Context, config?: Partial<Config>) {
  const cfg = { ...DEFAULTS, ...config }
  if (!cfg.token) throw new Error('dsh-android-approver: config.token must be set')

  const tasks = new Map<string, Task>()
  const pending = new Map<string, Pending>()
  const clients = new Set<WebSocket>()

  const snapshot = () => ({
    type: 'snapshot',
    tasks: [...tasks.values()].map(task => ({
      ...task,
      pendingApprovals: [...pending.values()].filter(p => p.taskId === task.id && !p.settled).length,
    })),
    approvals: [...pending.values()].map(({ resolve, settled, timer, ...approval }) => approval).filter(
      approval => pending.has(approval.id)
    ),
  })

  const broadcast = (message: unknown) => {
    const payload = JSON.stringify(message)
    for (const ws of clients) {
      if (ws.readyState === ws.OPEN) ws.send(payload)
    }
  }

  const finish = (approvalId: string, outcome: Outcome) => {
    const item = pending.get(approvalId)
    if (!item || item.settled) return false
    item.settled = true
    clearTimeout(item.timer)
    pending.delete(approvalId)
    item.resolve(outcome)
    broadcast({ type: 'approval/decided', approvalId, outcome })
    broadcast(snapshot())
    return true
  }

  const server = createServer((req, res) => {
    if (!authorized(req, cfg.token)) {
      json(res, 401, { error: 'unauthorized' })
      return
    }
    const url = new URL(req.url ?? '/', 'http://dsh.android')
    if (req.method === 'GET' && url.pathname === '/health') {
      json(res, 200, { ok: true, plugin: name })
      return
    }
    if (req.method === 'GET' && url.pathname === '/tasks') {
      json(res, 200, snapshot())
      return
    }
    json(res, 404, { error: 'not_found' })
  })

  const wss = new WebSocketServer({ noServer: true })

  server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url ?? '/', 'http://dsh.android')
    if (url.pathname !== '/ws' || !safeEqual(url.searchParams.get('token') ?? '', cfg.token)) {
      socket.destroy()
      return
    }
    wss.handleUpgrade(req, socket, head, ws => {
      clients.add(ws)
      ws.send(JSON.stringify(snapshot()))
      ws.on('message', raw => {
        try {
          const message = JSON.parse(raw.toString()) as {
            type?: string
            approvalId?: string
            decision?: string
          }
          if (message.type !== 'approval/decision' || !message.approvalId) return
          if (message.decision === 'allow-once') finish(message.approvalId, 'allowed-once')
          if (message.decision === 'reject') finish(message.approvalId, 'rejected')
        } catch {
          // Malformed client messages are ignored and the approval remains fail-closed.
        }
      })
      ws.on('close', () => clients.delete(ws))
    })
  })

  ctx.on('agent/created', ({ agent }) => {
    const raw = agent as any
    const id = String(raw.id ?? raw.session?.id ?? randomUUID())
    tasks.set(id, {
      id,
      sessionId: String(raw.session?.id ?? id),
      cwd: raw.session?.header?.cwd,
      startedAt: Date.now(),
      status: 'running',
    })
    broadcast(snapshot())
  })

  ctx.on('agent/disposed', ({ agent }) => {
    const raw = agent as any
    const id = String(raw.id ?? raw.session?.id ?? '')
    tasks.delete(id)
    for (const approval of [...pending.values()]) {
      if (approval.taskId === id) finish(approval.id, 'cancelled')
    }
    broadcast(snapshot())
  })

  ctx.on('approval/request', async (request: any, next: () => Promise<Outcome>) => {
    const agent = request.agent as any
    const taskId = String(agent?.id ?? agent?.session?.id ?? '')
    const task = tasks.get(taskId)
    if (!task) return next()

    const approval: Approval = {
      id: randomUUID(),
      taskId,
      sessionId: String(agent?.session?.id ?? task.sessionId),
      toolName: String(request.toolName),
      reason: request.reason,
      createdAt: Date.now(),
      expiresAt: Date.now() + cfg.approvalTimeoutMs,
      callId: request.callId ? String(request.callId) : undefined,
    }

    return await new Promise<Outcome>(resolve => {
      const timer = setTimeout(() => finish(approval.id, 'unavailable'), cfg.approvalTimeoutMs)
      pending.set(approval.id, { ...approval, resolve, settled: false, timer })
      broadcast({ type: 'approval/requested', approval })
      broadcast(snapshot())

      if (request.signal) {
        if (request.signal.aborted) finish(approval.id, 'cancelled')
        else request.signal.addEventListener(
          'abort',
          () => finish(approval.id, 'cancelled'),
          { once: true }
        )
      }
    })
  })

  server.listen(cfg.port, cfg.host, () => {
    ctx.logger.info('dsh-android-approver listening on ' + cfg.host + ':' + cfg.port)
  })

  ctx.effect(() => () => {
    for (const approval of [...pending.values()]) finish(approval.id, 'cancelled')
    for (const ws of clients) ws.close()
    wss.close()
    server.close()
  }, 'dsh-android-approver.server')
}
