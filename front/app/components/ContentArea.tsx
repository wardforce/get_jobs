"use client"
import { usePathname } from 'next/navigation'
import { ReactNode, useEffect, useMemo, useState } from 'react'
import { motion } from 'framer-motion'
import { apiFetch } from '@/lib/api'

export default function ContentArea({ children }: { children: ReactNode }) {
  const pathname = usePathname()
  const [backendAvailable, setBackendAvailable] = useState<boolean | null>(null)
  const [healthRetry, setHealthRetry] = useState(0)

  useEffect(() => {
    let active = true
    const checkBackend = async () => {
      const controller = new AbortController()
      const timeout = window.setTimeout(() => controller.abort(), 3000)
      try {
        const response = await apiFetch('/api/health', { signal: controller.signal })
        if (active) setBackendAvailable(response.ok)
      } catch {
        if (active) setBackendAvailable(false)
      } finally {
        window.clearTimeout(timeout)
      }
    }

    void checkBackend()
    const timer = window.setInterval(checkBackend, 5000)
    return () => {
      active = false
      window.clearInterval(timer)
    }
  }, [healthRetry])

  const accentClass = useMemo(() => {
    switch (pathname) {
      case '/boss':
        return 'accent-teal'
      case '/liepin':
        return 'accent-orange'
      case '/51job':
        return 'accent-amber'
      case '/zhilian':
        return 'accent-sky'
      default:
        return ''
    }
  }, [pathname])

  return (
    <main className={`flex-1 ml-64 bg-background dark:bg-blacksection content-bg ${accentClass} min-h-screen`}>
      <motion.div
        key={pathname}
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        exit={{ opacity: 0, y: -20 }}
        transition={{ duration: 0.4, ease: "easeInOut" }}
        className="container py-8"
      >
        {backendAvailable === false && (
          <div role="alert" className="mb-6 flex items-center justify-between gap-4 rounded-xl border border-red-300/60 bg-red-50 px-4 py-3 text-sm text-red-800 shadow-sm">
            <span>后端 8889 未连接，配置、投递和分析数据暂不可用。</span>
            <button type="button" className="rounded-lg border border-red-300 px-3 py-1 font-medium hover:bg-red-100" onClick={() => setHealthRetry((value) => value + 1)}>
              重试
            </button>
          </div>
        )}
        {children}
      </motion.div>
    </main>
  )
}
