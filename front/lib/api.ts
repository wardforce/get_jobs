const DEFAULT_API_BASE_URL = "http://localhost:8888"

export const API_BASE_URL = process.env.API_BASE_URL || DEFAULT_API_BASE_URL

export function apiUrl(path: string): string {
  return `${API_BASE_URL}${path.startsWith("/") ? path : `/${path}`}`
}

export async function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  try {
    return await fetch(apiUrl(path), init)
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error)
    throw new Error(`后端服务不可用（${API_BASE_URL}）：${detail}`)
  }
}
