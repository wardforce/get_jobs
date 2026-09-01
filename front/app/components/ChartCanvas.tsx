"use client"

import { useEffect, useRef } from "react"
import Chart from "chart.js/auto"

type ChartType = "pie" | "bar" | "line"

type ChartDataset = {
  label: string
  data: number[]
  backgroundColor: string | string[]
  borderColor?: string | string[]
  fill?: boolean
  pointBackgroundColor?: string
  pointBorderColor?: string
}

export type ChartCanvasProps = {
  type: ChartType
  labels: string[]
  data: number[]
  title?: string
  color?: string
  colors?: string[]
}

const DEFAULT_COLOR = "#3b82f6"
const PIE_COLORS = [
  "#3b82f6",
  "#10b981",
  "#f59e0b",
  "#ef4444",
  "#6366f1",
  "#22c55e",
  "#fb7185",
  "#a78bfa",
  "#f97316",
  "#06b6d4",
]

export default function ChartCanvas({
  type,
  labels,
  data,
  title,
  color = DEFAULT_COLOR,
  colors,
}: ChartCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const chartRef = useRef<Chart | null>(null)

  useEffect(() => {
    const context = canvasRef.current?.getContext("2d")
    if (!context) {
      return
    }

    chartRef.current?.destroy()
    chartRef.current = null

    const backgroundColor =
      type === "pie"
        ? (colors?.length ? colors : PIE_COLORS).slice(0, labels.length)
        : type === "bar" && colors?.length
          ? colors.slice(0, data.length)
          : color
    const borderColor =
      type === "pie"
        ? undefined
        : type === "bar" && colors?.length
          ? colors.slice(0, data.length)
          : color
    const dataset: ChartDataset = {
      label: title ?? "",
      data,
      backgroundColor,
      borderColor,
      ...(type === "line"
        ? {
            fill: false,
            pointBackgroundColor: color,
            pointBorderColor: color,
          }
        : {}),
    }

    try {
      chartRef.current = new Chart(context, {
          type,
          data: { labels, datasets: [dataset] },
          options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
              legend: { display: type === "pie" },
              title: { display: Boolean(title), text: title },
            },
            scales:
              type === "pie"
                ? undefined
                : {
                    x: { ticks: { autoSkip: true } },
                    y: { beginAtZero: true },
                  },
          },
      })
    } catch (error: unknown) {
      console.error("Failed to create chart:", error)
    }

    return () => {
      chartRef.current?.destroy()
      chartRef.current = null
    }
  }, [type, labels, data, title, color, colors])

  return <canvas ref={canvasRef} className="w-full h-64" />
}
