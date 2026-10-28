import assert from 'node:assert/strict'
import test from 'node:test'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { SessionId } from '@deepseek-ai/dsh-session'
import Todo from '@deepseek-ai/dsh-tool-todo'
import { nativeRuntime } from '../fixtures/native-runtime.js'
import { initialSelections, mountAgents, TextAdapter } from '../fixtures/agent-runtime.js'

async function until(check: () => boolean) {
  for (let n = 0; n < 500; n++) { if (check()) return; await delay(10) }
  assert.ok(check(), 'expected native progress within 5 seconds')
}

test('session state carries the current DSH todo list, whole-value and in write order', { timeout: 30_000 }, async () => {
  const home = await mkdtemp(join(tmpdir(), 'aa-todos-'))
  const adapter = new TextAdapter()
  const f = await nativeRuntime(home, async ctx => {
    await mountAgents(ctx, adapter)
    await ctx.plugin(Todo, { allowParallelInProgress: true }).await()
  })
  const runtime = f.ctx.agentsAnywhereRuntime.native
  const id = SessionId('todo-session')
  try {
    await runtime.send(id, 'plan the work', 'todo-plan', home, true, initialSelections, 'standard')
    await until(() => adapter.requests.length === 1)
    assert.equal((await runtime.configuration.state(id)).metadata.todos, undefined, 'no card before the first write')

    const agent = f.ctx.agents.get(id)!
    agent.session.append('todo/write', { todos: [
      { content: 'Cut the branch', status: 'completed' },
      { content: 'Add the bridge projection', status: 'in_progress' },
      { content: 'Publish the APK', status: 'pending' },
    ] })
    assert.deepEqual((await runtime.configuration.state(id)).metadata.todos, [
      { content: 'Cut the branch', status: 'completed' },
      { content: 'Add the bridge projection', status: 'in_progress' },
      { content: 'Publish the APK', status: 'pending' },
    ])

    // Every write replaces the list; the previous snapshot never survives.
    agent.session.append('todo/write', { todos: [
      { content: 'Cut the branch', status: 'completed' },
      { content: 'Add the bridge projection', status: 'completed' },
      { content: 'Publish the APK', status: 'in_progress' },
    ] })
    assert.deepEqual((await runtime.configuration.state(id)).metadata.todos, [
      { content: 'Cut the branch', status: 'completed' },
      { content: 'Add the bridge projection', status: 'completed' },
      { content: 'Publish the APK', status: 'in_progress' },
    ])

    // An empty list is a real write; it clears the card instead of echoing the old one.
    agent.session.append('todo/write', { todos: [] })
    assert.equal((await runtime.configuration.state(id)).metadata.todos, undefined)
    adapter.release!()
    await f.ctx.agents.get(id)!.whenIdle()
  } finally {
    await f.ctx.fiber.dispose()
    await rm(home, { recursive: true, force: true })
  }
})
