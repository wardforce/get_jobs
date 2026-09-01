'use client'

import { useEffect, useState } from 'react'
import { apiFetch } from '@/lib/api'

export default function LagouPage() {
  const [loggedIn, setLoggedIn] = useState(false)
  const [message, setMessage] = useState('检查登录状态中...')

  const refresh = async () => {
    const response = await apiFetch('/api/lagou/login-status')
    const data = await response.json()
    setLoggedIn(Boolean(data.isLoggedIn))
    setMessage(data.isLoggedIn ? '拉勾已登录' : '请先登录拉勾')
  }

  useEffect(() => {
    const initial = window.setTimeout(() => {
      void refresh().catch(() => setMessage('服务连接失败'))
    }, 0)
    const timer = window.setInterval(() => void refresh().catch(() => undefined), 2000)
    return () => {
      window.clearTimeout(initial)
      window.clearInterval(timer)
    }
  }, [])

  const call = async (path: string) => {
    const response = await apiFetch(`/api/lagou/${path}`, { method: 'POST' })
    const data = await response.json()
    setMessage(data.message || '操作完成')
    await refresh()
  }

  return (
    <main className="p-8">
      <h1 className="text-2xl font-bold">拉勾</h1>
      <p className="mt-2 text-muted-foreground">{message}</p>
      <div className="mt-6 flex gap-3">
        <button className="rounded bg-primary px-4 py-2 text-white" onClick={() => call('login')}>
          打开登录页
        </button>
        {loggedIn && (
          <button className="rounded border px-4 py-2" onClick={() => call('logout')}>
            退出登录
          </button>
        )}
      </div>
      <p className="mt-6 text-sm text-muted-foreground">
        登录成功后 Cookie 会自动保存，后续再接入拉勾岗位搜索和投递。
      </p>
    </main>
  )
}
