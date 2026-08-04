import { Fragment, useEffect, useState } from 'react'
import { getQueryStages, saveQueryStages } from '../api/quality'
import type { QueryStageConfig } from '../api/quality'

/** 每个阶段的图标（前端装饰） */
const STAGE_ICONS: Record<string, string> = {
  context: '🧠',
  normalize: '🧹',
  intent: '🎯',
  rewrite: '✍️',
  multiQuery: '🔀',
  hyde: '💭',
  entity: '🏷️',
}

/**
 * 检索查询处理管线：A-G 阶段的可视化编排页。
 * 阶段卡片横向排布，卡片间连接线上有持续从左往右流动的亮点，象征「问题数据在阶段间传递」。
 * 启停/顺序存 DB（kb_query_stage），保存后立即生效，无需重启。
 */
export default function QueryPipelinePage() {
  const [stages, setStages] = useState<QueryStageConfig[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState('')

  const load = async () => {
    setLoading(true)
    setError('')
    try {
      const res = await getQueryStages()
      setStages(res.data)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
  }, [])

  const toggle = (i: number) =>
    setStages((prev) => prev.map((s, idx) => (idx === i ? { ...s, enabled: !s.enabled } : s)))

  const move = (i: number, dir: -1 | 1) => {
    setStages((prev) => {
      const j = i + dir
      if (j < 0 || j >= prev.length) return prev
      const copy = [...prev]
      const tmp = copy[i]
      copy[i] = copy[j]
      copy[j] = tmp
      return copy
    })
  }

  const save = async () => {
    setSaving(true)
    setMsg('')
    try {
      await saveQueryStages(stages.map((s, i) => ({ ...s, sortOrder: (i + 1) * 10 })))
      setMsg('✅ 已保存，立即生效（无需重启）')
    } catch (e) {
      setMsg('⚠️ ' + (e instanceof Error ? e.message : '保存失败'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="space-y-4">
      <style>{`
        .pipe-connector {
          position: relative;
          width: 60px;
          height: 40px;
          flex-shrink: 0;
        }
        .pipe-connector::before {
          content: '';
          position: absolute;
          left: 0;
          right: 0;
          top: 50%;
          height: 3px;
          transform: translateY(-50%);
          border-radius: 999px;
          background-color: #e2e8f0;
          background-image: repeating-linear-gradient(90deg, #cbd5e1 0 8px, transparent 8px 16px);
        }
        .pipe-connector .pipe-dot {
          position: absolute;
          top: 50%;
          width: 8px;
          height: 8px;
          margin-top: -4px;
          border-radius: 9999px;
          background: #2563eb;
          box-shadow: 0 0 8px rgba(37, 99, 235, 0.9);
          animation: pipe-flow 1.8s linear infinite;
        }
        .pipe-connector .pipe-dot:nth-of-type(1) { animation-delay: 0s; }
        .pipe-connector .pipe-dot:nth-of-type(2) { animation-delay: 0.6s; }
        .pipe-connector .pipe-dot:nth-of-type(3) { animation-delay: 1.2s; }
        @keyframes pipe-flow {
          0%   { left: 0; opacity: 0; }
          15%  { opacity: 1; }
          85%  { opacity: 1; }
          100% { left: calc(100% - 8px); opacity: 0; }
        }
      `}</style>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-lg font-bold text-slate-800">🔧 检索查询处理管线</h1>
          <p className="text-sm text-slate-500 mt-0.5">
            每次 RAG 检索前，问题按下面的顺序依次流过各阶段。可独立启停、调整顺序，保存后立即生效。
          </p>
        </div>
        <div className="flex items-center gap-3">
          {msg && <span className="text-xs text-slate-500">{msg}</span>}
          <button
            onClick={save}
            disabled={saving || stages.length === 0}
            className="px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-60"
          >
            {saving ? '保存中…' : '保存'}
          </button>
        </div>
      </div>

      {error && <div className="text-sm text-red-500">⚠️ {error}</div>}
      {loading ? (
        <div className="text-sm text-slate-400 py-16 text-center">加载中…</div>
      ) : (
        <div className="bg-white border border-slate-200 rounded-2xl p-6 overflow-x-auto">
          <div className="flex items-center min-w-[980px] py-4">
            <FlowNode label="用户问题" tone="plain" />
            {stages.map((s, i) => (
              <Fragment key={s.name}>
                <FlowConnector />
                <StageCard
                  stage={s}
                  index={i}
                  total={stages.length}
                  icon={STAGE_ICONS[s.name] ?? '⚙️'}
                  onToggle={() => toggle(i)}
                  onMove={(d) => move(i, d)}
                />
              </Fragment>
            ))}
            <FlowConnector />
            <FlowNode label="混合检索" tone="accent" />
          </div>
          <div className="text-[11px] text-slate-400 text-center mt-2">
            亮点持续流动 = 问题数据在阶段间传递；停用的阶段会变灰（不参与检索）
          </div>
        </div>
      )}
    </div>
  )
}

/** 阶段间的连接线：虚线轨道 + 3 个持续左→右流动的亮点 */
function FlowConnector() {
  return (
    <div className="pipe-connector" aria-hidden>
      <span className="pipe-dot" />
      <span className="pipe-dot" />
      <span className="pipe-dot" />
    </div>
  )
}

/** 管线起点 / 终点节点 */
function FlowNode({ label, tone }: { label: string; tone: 'plain' | 'accent' }) {
  return (
    <div
      className={`shrink-0 px-3 py-2 rounded-xl text-xs font-medium border whitespace-nowrap ${
        tone === 'accent'
          ? 'bg-blue-50 text-blue-700 border-blue-200'
          : 'bg-slate-100 text-slate-500 border-slate-200'
      }`}
    >
      {label}
    </div>
  )
}

/** 单个阶段卡片：图标 + 名称 + 说明 + 启停开关 + 排序箭头 */
function StageCard({
  stage,
  index,
  total,
  icon,
  onToggle,
  onMove,
}: {
  stage: QueryStageConfig
  index: number
  total: number
  icon: string
  onToggle: () => void
  onMove: (dir: -1 | 1) => void
}) {
  return (
    <div
      className={`w-44 shrink-0 rounded-2xl border-2 p-3 text-center transition-colors ${
        stage.enabled ? 'border-blue-200 bg-blue-50/50' : 'border-slate-200 bg-slate-50'
      }`}
    >
      <div className="text-2xl">{icon}</div>
      <div className="mt-0.5 font-mono text-[10px] text-slate-400">#{index + 1}</div>
      <div className="font-bold text-slate-800 text-sm truncate" title={stage.name}>
        {stage.name}
      </div>
      <div className="text-[11px] text-slate-500 mt-1 min-h-[30px] leading-snug">{stage.description}</div>
      <div className="mt-2 flex items-center justify-center gap-1.5">
        <button
          onClick={() => onMove(-1)}
          disabled={index === 0}
          className="px-1.5 py-0.5 rounded-md bg-white border border-slate-200 text-xs text-slate-500 hover:text-blue-600 hover:border-blue-300 disabled:opacity-30"
        >
          ↑
        </button>
        <label className="flex items-center gap-1 text-[11px] cursor-pointer select-none">
          <input type="checkbox" checked={stage.enabled} onChange={onToggle} className="accent-blue-600" />
          {stage.enabled ? '启用' : '停用'}
        </label>
        <button
          onClick={() => onMove(1)}
          disabled={index === total - 1}
          className="px-1.5 py-0.5 rounded-md bg-white border border-slate-200 text-xs text-slate-500 hover:text-blue-600 hover:border-blue-300 disabled:opacity-30"
        >
          ↓
        </button>
      </div>
    </div>
  )
}
