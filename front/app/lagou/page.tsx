'use client'

import { useEffect, useState } from 'react'
import { BiBriefcase, BiLogIn, BiLogOut, BiPlay, BiSave, BiStop } from 'react-icons/bi'
import { createSSEWithBackoff } from '@/lib/sse'
import { API_BASE_URL, apiFetch } from '@/lib/api'
import PageHeader from '@/app/components/PageHeader'
import AnalysisContent from '@/app/lagou/analysis/AnalysisContent'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'

type LoginState = 'LOGGED_IN' | 'LOGGED_OUT' | 'UNKNOWN'
type Config = {
  id?: number
  keywords: string
  city: string
  resumeType: 'ONLINE' | 'ATTACHMENT'
  resumeName: string
  maxCount: number
}
type Option = { id?: number; code?: string; name: string }
type StatusPayload = { isRunning?: boolean; isLoggedIn?: boolean; loginState?: LoginState; lagouLoginState?: LoginState; message?: string }
type ProgressPayload = { message?: string; current?: number; total?: number }

const parseKeywords = (value: unknown) => {
  if (Array.isArray(value)) return value.filter(Boolean).join(', ')
  const text = String(value || '').trim()
  if (text.startsWith('[')) {
    try {
      const parsed = JSON.parse(text)
      if (Array.isArray(parsed)) return parsed.filter(Boolean).join(', ')
    } catch { /* keep raw value */ }
  }
  return text.replace(/，/g, ',')
}

const serializeKeywords = (value: string) => JSON.stringify(
  Array.from(new Set(value.replace(/，/g, ',').split(',').map((item) => item.trim()).filter(Boolean))),
)

function stateFromPayload(data: StatusPayload): LoginState {
  const state = data.loginState || data.lagouLoginState
  if (state === 'LOGGED_IN' || state === 'LOGGED_OUT' || state === 'UNKNOWN') {
    return state
  }
  return data.isLoggedIn ? 'LOGGED_IN' : 'UNKNOWN'
}

export default function LagouPage() {
  const [config, setConfig] = useState<Config>({ keywords: '', city: '全国', resumeType: 'ONLINE', resumeName: '', maxCount: 30 })
  const [cities, setCities] = useState<Option[]>([])
  const [isCustomCity, setIsCustomCity] = useState(false)
  const [loading, setLoading] = useState(true)
  const [loginState, setLoginState] = useState<LoginState>('UNKNOWN')
  const [isRunning, setIsRunning] = useState(false)
  const [isStopping, setIsStopping] = useState(false)
  const [progress, setProgress] = useState<ProgressPayload | null>(null)
  const [notice, setNotice] = useState('')

  const loadConfig = async () => {
    try {
      const response = await apiFetch('/api/lagou/config')
      const data = await response.json()
      const availableCities: Option[] = data.options?.city || []
      if (data.config) {
        setConfig((current) => ({
          ...current,
          ...data.config,
          keywords: parseKeywords(data.config.keywords),
          city: data.config.city || '全国',
          resumeType: data.config.resumeType || 'ONLINE',
          resumeName: data.config.resumeName || '',
          maxCount: typeof data.config.maxCount === 'number' && data.config.maxCount > 0 ? data.config.maxCount : 30,
        }))
        setIsCustomCity(Boolean(data.config.city)
          && data.config.city !== '全国'
          && !availableCities.some((city) => city.name === data.config.city || city.code === data.config.city))
      }
      setCities(availableCities.filter((city) => city.name !== '全国'))
    } catch {
      setNotice('配置加载失败，请先运行 .\\gradlew.bat bootRun')
    } finally {
      setLoading(false)
    }
  }

  const applyStatus = (data: StatusPayload) => {
    setLoginState(stateFromPayload(data))
    if (typeof data.isRunning === 'boolean') setIsRunning(data.isRunning)
    if (data.message && data.loginState === 'UNKNOWN') setNotice(data.message)
    if (!data.isRunning) setIsStopping(false)
  }

  useEffect(() => { void loadConfig() }, [])

  useEffect(() => {
    if (typeof EventSource === 'undefined') return
    const client = createSSEWithBackoff(`${API_BASE_URL}/api/jobs/login-status/stream`, {
      onError: () => setLoginState('UNKNOWN'),
      listeners: [
        { name: 'connected', handler: (event) => { try { applyStatus(JSON.parse(event.data)) } catch { setLoginState('UNKNOWN') } } },
        { name: 'login-status', handler: (event) => { try { const data = JSON.parse(event.data); if (data.platform === 'lagou') applyStatus(data) } catch { /* retry stream */ } } },
        { name: 'ping', handler: () => undefined },
      ],
    })
    return () => client.close()
  }, [])

  useEffect(() => {
    let cancelled = false
    const poll = async () => {
      try {
        const response = await apiFetch('/api/lagou/status', { cache: 'no-store' })
        if (!response.ok) return
        const data = await response.json()
        if (!cancelled) applyStatus(data)
      } catch {
        if (!cancelled) setLoginState('UNKNOWN')
      }
    }
    void poll()
    const timer = window.setInterval(poll, 1500)
    return () => { cancelled = true; window.clearInterval(timer) }
  }, [])

  useEffect(() => {
    if (typeof EventSource === 'undefined') return
    const client = createSSEWithBackoff(`${API_BASE_URL}/api/lagou/stream`, {
      listeners: [
        { name: 'progress', handler: (event) => { try { setProgress(JSON.parse(event.data)) } catch { /* ignore malformed event */ } } },
        { name: 'ping', handler: () => undefined },
      ],
    })
    return () => client.close()
  }, [])

  const action = async (path: string) => {
    try {
      const response = await apiFetch(`/api/lagou/${path}`, { method: 'POST' })
      const data = await response.json()
      setNotice(data.message || (data.success ? '操作成功' : '操作失败'))
      if (path === 'login') setLoginState('UNKNOWN')
      if (path === 'logout') setLoginState('LOGGED_OUT')
    } catch {
      setNotice('后端未连接，请运行 .\\gradlew.bat bootRun')
      setLoginState('UNKNOWN')
    }
  }

  const save = async () => {
    if (!config.keywords.trim()) return setNotice('至少填写一个搜索关键词')
    if (config.resumeType === 'ATTACHMENT' && !config.resumeName.trim()) return setNotice('附件简历模式必须填写简历名称')
    if (config.maxCount < 1) return setNotice('单次投递数量必须大于 0')
    try {
      const response = await apiFetch('/api/lagou/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...config, keywords: serializeKeywords(config.keywords) }),
      })
      const data = await response.json()
      setNotice(data.message || (response.ok ? '配置保存成功' : '配置保存失败'))
    } catch { setNotice('配置保存失败，请检查后端服务') }
  }

  const start = async () => { setIsRunning(true); await action('start') }
  const stop = async () => {
    setIsStopping(true)
    try {
      const response = await apiFetch('/api/lagou/stop', { method: 'POST' })
      const data = await response.json()
      setNotice(data.message || (data.success ? '停止请求已发送' : '停止失败'))
      if (!response.ok || !data.success) setIsStopping(false)
    } catch { setIsStopping(false); setNotice('停止投递失败：后端未连接') }
  }

  const stateText = loginState === 'LOGGED_IN' ? '已登录' : loginState === 'LOGGED_OUT' ? '未登录' : '检测中/页面重连'

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<BiBriefcase className="text-2xl" />}
        title="拉勾配置"
        subtitle="配置拉勾平台的求职参数"
        iconClass="text-white"
        accentBgClass="bg-orange-500"
        actions={<div className="flex flex-wrap items-center gap-2">
          {isRunning ? <Button onClick={stop} disabled={isStopping} size="sm" className="rounded-full bg-red-500 text-white"><BiStop className="mr-1" />{isStopping ? '正在停止...' : '正在投递，点击停止'}</Button> : <Button onClick={start} disabled={loginState !== 'LOGGED_IN'} size="sm" className="rounded-full bg-teal-500 text-white"><BiPlay className="mr-1" />{loginState === 'UNKNOWN' ? '检测登录中...' : loginState === 'LOGGED_OUT' ? '请先登录拉勾' : '开始投递'}</Button>}
          <Button onClick={() => action('login')} size="sm" className="rounded-full bg-blue-500 text-white"><BiLogIn className="mr-1" />打开登录</Button>
          <Button onClick={() => action('logout')} size="sm" className="rounded-full bg-red-500 text-white"><BiLogOut className="mr-1" />退出登录</Button>
          <Button onClick={save} size="sm" className="rounded-full bg-indigo-500 text-white"><BiSave className="mr-1" />保存配置</Button>
        </div>}
      />
      {notice && <p role="status" className="text-sm text-muted-foreground">{notice}</p>}
      {progress && <Card><CardContent className="pt-5"><p className="text-sm">{progress.message || '投递进行中'}</p>{progress.current != null && <p className="mt-1 text-xs text-muted-foreground">进度：{progress.current}/{progress.total ?? '?'}</p>}</CardContent></Card>}
      <Tabs defaultValue="config" className="w-full">
        <TabsList className="grid w-full grid-cols-2"><TabsTrigger value="config">平台配置</TabsTrigger><TabsTrigger value="analytics">投递分析</TabsTrigger></TabsList>
        <TabsContent value="config" className="mt-6 space-y-6">
          <Card><CardHeader><CardTitle>拉勾平台说明</CardTitle><CardDescription>登录后即可按配置启动自动投递</CardDescription></CardHeader><CardContent><p className="text-sm text-muted-foreground">当前状态：{stateText}。后端断线时请运行 <code>.\gradlew.bat bootRun</code>，页面会自动重试。</p></CardContent></Card>
          <Card><CardHeader><CardTitle>配置参数</CardTitle></CardHeader><CardContent>{loading ? <p className="text-sm text-muted-foreground">配置加载中...</p> : <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <div className="space-y-2"><Label htmlFor="keywords">搜索关键词（逗号分隔）</Label><Input id="keywords" value={config.keywords} onChange={(e) => setConfig({ ...config, keywords: e.target.value })} placeholder="如：Java，后端，Spring" /><p className="text-xs text-muted-foreground">支持英文逗号和中文逗号。</p></div>
            <div className="space-y-2"><div className="flex items-center justify-between"><Label htmlFor="city">城市</Label><button type="button" className="text-xs text-primary hover:underline" onClick={() => { setIsCustomCity((value) => !value); if (!isCustomCity) setConfig({ ...config, city: '' }) }}>{isCustomCity ? '从列表选择' : '手动填写'}</button></div>{isCustomCity ? <Input id="city" value={config.city || ''} onChange={(e) => setConfig({ ...config, city: e.target.value })} placeholder="例如：珠海" /> : <Select id="city" value={config.city || '全国'} onChange={(e) => setConfig({ ...config, city: e.target.value })}><option value="全国">全国</option>{cities.map((city) => <option key={city.id || city.code || city.name} value={city.name}>{city.name}</option>)}</Select>}<p className="text-xs text-muted-foreground">支持列表城市，也支持直接填写城市名称；保存后写入数据库。</p></div>
            <div className="space-y-2"><Label htmlFor="maxCount">单次投递数量</Label><Input id="maxCount" type="number" min={1} max={200} value={config.maxCount || 30} onChange={(e) => setConfig({ ...config, maxCount: Math.max(1, parseInt(e.target.value, 10) || 1) })} placeholder="30" /><p className="text-xs text-muted-foreground">达到该有效新投递数后自动完成本轮投递。</p></div>
            <fieldset className="space-y-2"><legend className="text-sm font-medium">简历类型</legend><div className="flex gap-5 pt-2"><label className="flex items-center gap-2 text-sm"><input type="radio" name="resumeType" checked={config.resumeType === 'ONLINE'} onChange={() => setConfig({ ...config, resumeType: 'ONLINE' })} />在线简历</label><label className="flex items-center gap-2 text-sm"><input type="radio" name="resumeType" checked={config.resumeType === 'ATTACHMENT'} onChange={() => setConfig({ ...config, resumeType: 'ATTACHMENT' })} />附件简历</label></div></fieldset>
            {config.resumeType === 'ATTACHMENT' && <div className="space-y-2"><Label htmlFor="resumeName">附件简历名称</Label><Input id="resumeName" value={config.resumeName} onChange={(e) => setConfig({ ...config, resumeName: e.target.value })} placeholder="请输入拉勾弹窗中显示的完整名称" /><p className="text-xs text-muted-foreground">名称匹配失败会停止本轮投递，避免误选简历。</p></div>}
          </div>}</CardContent></Card>
        </TabsContent>
        <TabsContent value="analytics" className="mt-6"><AnalysisContent /></TabsContent>
      </Tabs>
    </div>
  )
}
