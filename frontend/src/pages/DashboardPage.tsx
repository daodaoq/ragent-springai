import { useEffect, useRef, useState } from 'react'
import * as echarts from 'echarts'
import {
  getFeedbackStats,
  getQuestionTrend,
  getStatsOverview,
  getTagDistribution,
  getTopAskers,
} from '../api/stats'
import { useChart } from '../components/useChart'
import type { FeedbackStats, StatsOverview, TagCountRow, TopAskerRow, TrendRow } from '../types'

interface Data {
  overview?: StatsOverview
  trend?: TrendRow[]
  tags?: TagCountRow[]
  askers?: TopAskerRow[]
  feedback?: FeedbackStats
}

function buildTrendOption(rows: TrendRow[]): echarts.EChartsOption {
  return {
    title: { text: '提问趋势（近 14 天）', textStyle: { fontSize: 14 } },
    tooltip: { trigger: 'axis' },
    grid: { left: 44, right: 20, top: 44, bottom: 30 },
    xAxis: { type: 'category', data: rows.map((r) => r.createdDate) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        type: 'line',
        smooth: true,
        areaStyle: { opacity: 0.15 },
        itemStyle: { color: '#2563eb' },
        data: rows.map((r) => r.cnt),
      },
    ],
  }
}

function buildTagsOption(rows: TagCountRow[]): echarts.EChartsOption {
  return {
    title: { text: '标签分布', textStyle: { fontSize: 14 } },
    tooltip: { trigger: 'axis' },
    grid: { left: 44, right: 20, top: 44, bottom: 56 },
    xAxis: {
      type: 'category',
      data: rows.map((r) => r.tagName),
      axisLabel: { interval: 0, rotate: rows.length > 6 ? 30 : 0 },
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        type: 'bar',
        barWidth: '50%',
        itemStyle: { color: '#7c3aed' },
        data: rows.map((r) => r.cnt),
      },
    ],
  }
}

function buildAskersOption(rows: TopAskerRow[]): echarts.EChartsOption {
  return {
    title: { text: 'Top 提问者', textStyle: { fontSize: 14 } },
    tooltip: { trigger: 'axis' },
    grid: { left: 80, right: 20, top: 44, bottom: 30 },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: { type: 'category', data: rows.map((r) => r.nickname) },
    series: [
      {
        type: 'bar',
        barWidth: '50%',
        itemStyle: { color: '#0d9488' },
        data: rows.map((r) => r.cnt),
      },
    ],
  }
}

export default function DashboardPage() {
  const [data, setData] = useState<Data>({})
  const [error, setError] = useState('')
  const trendRef = useRef<HTMLDivElement>(null)
  const tagsRef = useRef<HTMLDivElement>(null)
  const askersRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let alive = true
    Promise.all([
      getStatsOverview(),
      getQuestionTrend(14),
      getTagDistribution(),
      getTopAskers(5),
      getFeedbackStats(),
    ])
      .then(([o, t, g, a, f]) => {
        if (!alive) return
        setData({ overview: o.data, trend: t.data, tags: g.data, askers: a.data, feedback: f.data })
      })
      .catch((e) => alive && setError(e instanceof Error ? e.message : '加载失败'))
    return () => {
      alive = false
    }
  }, [])

  useChart(trendRef, data.trend, buildTrendOption)
  useChart(tagsRef, data.tags, buildTagsOption)
  useChart(askersRef, data.askers, buildAskersOption)

  const cards = [
    { label: '问题数', value: data.overview?.questions ?? 0, icon: '❓' },
    { label: '回答数', value: data.overview?.answers ?? 0, icon: '💬' },
    { label: '用户数', value: data.overview?.users ?? 0, icon: '👥' },
    { label: '标签数', value: data.overview?.tags ?? 0, icon: '🏷️' },
    {
      label: 'AI 反馈好评率',
      value: data.feedback ? `${data.feedback.upRate.toFixed(0)}%` : '—',
      icon: '👍',
    },
  ]

  return (
    <div>
      <h1 className="text-lg font-bold text-slate-800 mb-4">📊 数据看板</h1>
      {error && <div className="text-sm text-red-500 mb-3">⚠️ {error}</div>}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3 mb-5">
        {cards.map((c) => (
          <div key={c.label} className="bg-white border border-slate-200 rounded-xl px-4 py-3">
            <div className="text-xs text-slate-400">
              {c.icon} {c.label}
            </div>
            <div className="text-2xl font-bold text-slate-800 mt-1">{c.value}</div>
          </div>
        ))}
      </div>
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <div className="bg-white border border-slate-200 rounded-xl p-2">
          <div ref={trendRef} className="h-72 w-full" />
        </div>
        <div className="bg-white border border-slate-200 rounded-xl p-2">
          <div ref={tagsRef} className="h-72 w-full" />
        </div>
        <div className="bg-white border border-slate-200 rounded-xl p-2 lg:col-span-2">
          <div ref={askersRef} className="h-72 w-full" />
        </div>
      </div>
    </div>
  )
}
