import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import * as echarts from 'echarts'
import { useChart } from '../components/useChart'
import {
  getChunkSettings,
  getQualityReport,
  getQueryLogs,
  runEval,
  updateChunkSettings,
} from '../api/quality'
import type {
  ChunkQualityReport,
  ChunkSettings,
  DocQuality,
  EvalReport,
  QualityBucket,
  QueryLogEntry,
  QueryLogPage,
} from '../api/quality'
import { rechunkDocument } from '../api/kb'
import type { ChunkParams } from '../api/kb'
import Pagination from '../components/Pagination'
import { usePagination } from '../hooks/usePagination'
import { formatTime } from '../utils/format'

/** 重新切片弹窗状态；semantic 三态：'default'=全局默认 / 'true' / 'false' */
interface RechunkState {
  doc: DocQuality
  maxChars: string
  overlap: string
  semantic: 'default' | 'true' | 'false'
  saving: boolean
  error: string
}

function buildHistogramOption(buckets: QualityBucket[]): echarts.EChartsOption {
  return {
    title: { text: '切片长度分布（字符）', textStyle: { fontSize: 14 } },
    tooltip: { trigger: 'axis' },
    grid: { left: 44, right: 20, top: 44, bottom: 44 },
    xAxis: {
      type: 'category',
      data: buckets.map((b) => `${b.start}~${b.end}`),
      axisLabel: { interval: 0, rotate: buckets.length > 8 ? 30 : 0 },
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      { type: 'bar', barWidth: '60%', itemStyle: { color: '#2563eb' }, data: buckets.map((b) => b.count) },
    ],
  }
}

const inputCls =
  'w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500'

export default function ChunkQualityPage() {
  const [settings, setSettings] = useState<ChunkSettings | null>(null)
  const [settingsForm, setSettingsForm] = useState({ maxChunkChars: '800', overlapChars: '100', semanticEnabled: false })
  const [report, setReport] = useState<ChunkQualityReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [settingsSaving, setSettingsSaving] = useState(false)
  const [settingsMsg, setSettingsMsg] = useState('')

  const [rechunk, setRechunk] = useState<RechunkState | null>(null)

  /** RAG 评测：A/B 两路结果（off=原样检索基线，on=查询处理管线）；withAnswer=是否含回答生成+LLM 裁判（慢） */
  const [evalProcessed, setEvalProcessed] = useState(true)
  const [evalWithAnswer, setEvalWithAnswer] = useState(true)
  const [evalCompare, setEvalCompare] = useState<{
    off: EvalReport | null
    on: EvalReport | null
    running: 'off' | 'on' | 'both' | null
    error: string
  }>({ off: null, on: null, running: null, error: '' })

  // 文档明细表 / 评测用例表客户端分页
  const docPaging = usePagination(report?.docs ?? [], 10)
  const evalRows = evalCompare.off ? evalCompare.off.cases : (evalCompare.on?.cases ?? [])
  const casePaging = usePagination(evalRows, 10)

  /** 真实查询日志（自动采集每次 RAG 请求轨迹） */
  const [queryLogs, setQueryLogs] = useState<QueryLogPage | null>(null)
  const [logLoading, setLogLoading] = useState(false)
  const [logMsg, setLogMsg] = useState('')
  const [logPage, setLogPage] = useState(1)
  const [logPageSize, setLogPageSize] = useState(10)
  /** 展开查看某条日志的召回来源 + 回答 */
  const [expandedLog, setExpandedLog] = useState<string | null>(null)

  /** 页内 Tab：quality=切片质量 / eval=检索评测 / logs=查询日志（避免一页纵向过长） */
  const [tab, setTab] = useState<'quality' | 'eval' | 'logs'>('quality')

  const histRef = useRef<HTMLDivElement>(null)
  useChart(histRef, report?.lengthBuckets, buildHistogramOption)

  const load = async () => {
    setLoading(true)
    setError('')
    try {
      const [s, r] = await Promise.all([getChunkSettings(), getQualityReport()])
      setSettings(s.data)
      setSettingsForm({
        maxChunkChars: String(s.data.maxChunkChars),
        overlapChars: String(s.data.overlapChars),
        semanticEnabled: s.data.semanticEnabled,
      })
      setReport(r.data)
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
    loadQueryLogs(1, 10)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const saveSettings = async () => {
    const maxChunkChars = Number(settingsForm.maxChunkChars)
    const overlapChars = Number(settingsForm.overlapChars)
    if (!Number.isInteger(maxChunkChars) || maxChunkChars < 100 || maxChunkChars > 4000) {
      setSettingsMsg('⚠️ 单切片最大字符数需在 100~4000 之间')
      return
    }
    if (!Number.isInteger(overlapChars) || overlapChars < 0 || overlapChars > maxChunkChars) {
      setSettingsMsg('⚠️ 重叠字符数需在 0~maxChunkChars 之间')
      return
    }
    setSettingsSaving(true)
    setSettingsMsg('')
    try {
      await updateChunkSettings({ maxChunkChars, overlapChars, semanticEnabled: settingsForm.semanticEnabled })
      setSettingsMsg('✅ 已保存，下次上传/重新切片生效')
      await load()
    } catch (e) {
      setSettingsMsg('⚠️ ' + (e instanceof Error ? e.message : '保存失败'))
    } finally {
      setSettingsSaving(false)
    }
  }

  const openRechunk = (doc: DocQuality) => {
    setRechunk({
      doc,
      maxChars: doc.maxChunkChars != null ? String(doc.maxChunkChars) : '',
      overlap: doc.overlapChars != null ? String(doc.overlapChars) : '',
      semantic: doc.semantic == null ? 'default' : doc.semantic ? 'true' : 'false',
      saving: false,
      error: '',
    })
  }

  const confirmRechunk = async () => {
    if (!rechunk) return
    const params: ChunkParams = {}
    if (rechunk.maxChars.trim() !== '') {
      const v = Number(rechunk.maxChars)
      if (!Number.isInteger(v) || v < 100 || v > 4000) {
        setRechunk({ ...rechunk, error: '单切片最大字符数需在 100~4000 之间' })
        return
      }
      params.maxChunkChars = v
    }
    if (rechunk.overlap.trim() !== '') {
      const v = Number(rechunk.overlap)
      if (!Number.isInteger(v) || v < 0 || v > (params.maxChunkChars ?? 4000)) {
        setRechunk({ ...rechunk, error: '重叠字符数需在 0~maxChunkChars 之间' })
        return
      }
      params.overlapChars = v
    }
    if (rechunk.semantic !== 'default') params.semantic = rechunk.semantic === 'true'
    setRechunk({ ...rechunk, saving: true, error: '' })
    try {
      await rechunkDocument(rechunk.doc.docId, params)
      setRechunk(null)
      await load()
    } catch (e) {
      setRechunk({ ...rechunk, saving: false, error: e instanceof Error ? e.message : '重新切片失败' })
    }
  }

  // ==================== RAG 评测（A/B） ====================

  const runEvalOnce = async (processed: boolean): Promise<EvalReport> => {
    const res = await runEval({ processed, withAnswer: evalWithAnswer })
    return res.data
  }

  /** 按当前开关跑一次评测 */
  const runSingleEval = async () => {
    const key = evalProcessed ? 'on' : 'off'
    setEvalCompare((s) => ({ ...s, running: key, error: '', [key]: null }))
    try {
      const rep = await runEvalOnce(evalProcessed)
      setEvalCompare((s) => ({ ...s, running: null, [key]: rep }))
    } catch (e) {
      setEvalCompare((s) => ({
        ...s,
        running: null,
        error: e instanceof Error ? e.message : '评测失败',
      }))
    }
  }

  /** A/B 对比：连续跑 原样 与 查询处理 两路，并排展示 */
  const runEvalAB = async () => {
    setEvalCompare((s) => ({ ...s, running: 'both', error: '', off: null, on: null }))
    try {
      const off = await runEvalOnce(false)
      const on = await runEvalOnce(true)
      setEvalCompare({ off, on, running: null, error: '' })
    } catch (e) {
      setEvalCompare((s) => ({
        ...s,
        running: null,
        error: e instanceof Error ? e.message : 'A/B 评测失败',
      }))
    }
  }

  const evalMetricRows = [
    { label: 'Recall@5', kind: 'retrieval', off: evalCompare.off?.retrieval.recallAt5, on: evalCompare.on?.retrieval.recallAt5 },
    { label: 'Precision@5', kind: 'retrieval', off: evalCompare.off?.retrieval.precisionAt5, on: evalCompare.on?.retrieval.precisionAt5 },
    { label: 'MRR@5', kind: 'retrieval', off: evalCompare.off?.retrieval.mrrAt5, on: evalCompare.on?.retrieval.mrrAt5 },
    { label: 'NDCG@5', kind: 'retrieval', off: evalCompare.off?.retrieval.ndcgAt5, on: evalCompare.on?.retrieval.ndcgAt5 },
    { label: '忠实度', kind: 'answer', off: evalCompare.off?.answer.avgFaithfulness, on: evalCompare.on?.answer.avgFaithfulness },
    { label: '相关性', kind: 'answer', off: evalCompare.off?.answer.avgRelevance, on: evalCompare.on?.answer.avgRelevance },
    { label: '引用率', kind: 'answer', off: evalCompare.off?.answer.citationRate, on: evalCompare.on?.answer.citationRate },
  ]
  const visibleEvalMetrics = evalMetricRows.filter((m) => evalWithAnswer || m.kind === 'retrieval')

  const loadQueryLogs = async (p = logPage, s = logPageSize) => {
    setLogLoading(true)
    setLogMsg('')
    try {
      const res = await getQueryLogs(p, s)
      setQueryLogs(res.data)
      setLogPage(p)
    } catch (e) {
      setLogMsg(e instanceof Error ? e.message : '加载查询日志失败')
    } finally {
      setLogLoading(false)
    }
  }

  /** 解析来源 JSON，返回来源条数 */
  const sourcesCount = (r: QueryLogEntry) => parseSources(r).length

  /** 解析来源 JSON 为可展示列表 */
  const parseSources = (r: QueryLogEntry): { filename: string; score?: number; headingPath?: string; excerpt?: string }[] => {
    if (!r.sources) return []
    try {
      const arr = JSON.parse(r.sources)
      return Array.isArray(arr) ? arr : []
    } catch {
      return []
    }
  }

  const effMax = (d: DocQuality) => (d.maxChunkChars != null ? d.maxChunkChars : settings?.maxChunkChars)
  const effSemantic = (d: DocQuality) =>
    d.semantic == null ? settings?.semanticEnabled ?? false : d.semantic

  const cards = useMemo(() => {
    const r = report
    const noHeadingPct = r && r.totalChunks > 0 ? ((r.noHeadingCount / r.totalChunks) * 100).toFixed(1) : '0'
    return [
      { label: '文档数', value: r?.docCount ?? 0, icon: '📄' },
      { label: '切片数', value: r?.totalChunks ?? 0, icon: '🧩' },
      { label: '平均长度', value: r ? r.avgChunkLen : 0, icon: '📏' },
      { label: '无标题切片', value: r ? `${noHeadingPct}%` : '0%', icon: '🏷️' },
      { label: '过长切片', value: r?.overlongCount ?? 0, icon: '⚠️' },
      { label: '重复切片', value: r?.duplicateCount ?? 0, icon: '🔁' },
    ]
  }, [report])

  const tabs: { key: 'quality' | 'eval' | 'logs'; label: string; count?: number }[] = [
    { key: 'quality', label: '📐 切片质量', count: report?.docs.length },
    { key: 'eval', label: '🧪 检索评测', count: evalCompare.on?.cases.length ?? evalCompare.off?.cases.length },
    { key: 'logs', label: '📊 查询日志', count: queryLogs?.total },
  ]

  return (
    <div>
      <h1 className="text-lg font-bold text-slate-800 mb-3">🔍 切片质量评估</h1>

      {/* 页内 Tab：避免 KPI/设置/文档/评测/日志纵向堆叠过高 */}
      <div className="flex items-center gap-1 mb-4 border-b border-slate-200">
        {tabs.map((t) => (
          <button
            key={t.key}
            onClick={() => setTab(t.key)}
            className={`px-4 py-2 -mb-px border-b-2 text-sm font-medium transition-colors ${
              tab === t.key
                ? 'border-blue-600 text-blue-600'
                : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300'
            }`}
          >
            {t.label}
            {t.count != null && (
              <span
                className={`ml-1.5 text-xs rounded-full px-1.5 py-0.5 ${
                  tab === t.key ? 'bg-blue-50 text-blue-600' : 'bg-slate-100 text-slate-500'
                }`}
              >
                {t.count}
              </span>
            )}
          </button>
        ))}
      </div>

      {error && <div className="text-sm text-red-500 mb-3">⚠️ {error}</div>}
      {loading && <div className="text-sm text-slate-400 py-8">加载中…</div>}
      {!loading && report && (
        <>
          {tab === 'quality' && (
            <>
          {/* KPI 卡 */}
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3 mb-5">
            {cards.map((c) => (
              <div key={c.label} className="bg-white border border-slate-200 rounded-xl px-4 py-3">
                <div className="text-xs text-slate-400">
                  {c.icon} {c.label}
                </div>
                <div className="text-2xl font-bold text-slate-800 mt-1">{c.value}</div>
              </div>
            ))}
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-3 gap-4 mb-5">
            {/* 全局设置 */}
            <div className="bg-white border border-slate-200 rounded-xl p-4">
              <h2 className="font-bold text-slate-800 mb-3">⚙️ 全局切片参数</h2>
              <label className="block mb-3">
                <span className="text-sm text-slate-600">单切片最大字符数</span>
                <input
                  className={`mt-1 ${inputCls}`}
                  type="number"
                  value={settingsForm.maxChunkChars}
                  onChange={(e) => setSettingsForm({ ...settingsForm, maxChunkChars: e.target.value })}
                />
              </label>
              <label className="block mb-3">
                <span className="text-sm text-slate-600">重叠字符数</span>
                <input
                  className={`mt-1 ${inputCls}`}
                  type="number"
                  value={settingsForm.overlapChars}
                  onChange={(e) => setSettingsForm({ ...settingsForm, overlapChars: e.target.value })}
                />
              </label>
              <label className="flex items-center gap-2 text-sm text-slate-600 mb-3">
                <input
                  type="checkbox"
                  className="accent-blue-600"
                  checked={settingsForm.semanticEnabled}
                  onChange={(e) => setSettingsForm({ ...settingsForm, semanticEnabled: e.target.checked })}
                />
                语义分片（长无标题小节按相似度断片）
              </label>
              <div className="flex items-center gap-3">
                <button
                  onClick={saveSettings}
                  disabled={settingsSaving}
                  className="px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-medium hover:bg-blue-700 disabled:opacity-60"
                >
                  {settingsSaving ? '保存中…' : '保存'}
                </button>
                {settingsMsg && <span className="text-xs text-slate-500">{settingsMsg}</span>}
              </div>
            </div>

            {/* 长度分布图 */}
            <div className="bg-white border border-slate-200 rounded-xl p-2 lg:col-span-2">
              <div ref={histRef} className="h-72 w-full" />
            </div>
          </div>

          {/* 文档明细 */}
          <div className="bg-white border border-slate-200 rounded-xl overflow-hidden mb-5">
            <div className="px-4 py-3 border-b border-slate-200 font-bold text-slate-800">
              📋 文档明细（{report.docs.length}）
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-slate-50 text-slate-600">
                  <tr>
                    {[
                      '文档',
                      '状态',
                      '切片数',
                      '生效参数',
                      '平均长度',
                      '过短',
                      '过长',
                      '无标题',
                      '重复',
                      '缺向量',
                      '操作',
                    ].map((h) => (
                      <th key={h} className="text-left px-4 py-3 font-medium whitespace-nowrap">
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {report.docs.length === 0 && (
                    <tr>
                      <td colSpan={11} className="px-4 py-8 text-center text-slate-400">
                        暂无 READY 文档
                      </td>
                    </tr>
                  )}
                  {docPaging.paged.map((d) => (
                    <tr key={d.docId} className="hover:bg-slate-50">
                      <td className="px-4 py-3 text-slate-800 max-w-[180px] truncate" title={d.filename}>
                        {d.filename}
                        {d.countMismatch && (
                          <span className="ml-1 text-xs text-amber-500" title="chunk_count 与实际切片数不一致，建议重新切片">
                            ⚠️
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <span
                          className={`px-2 py-0.5 rounded-full text-xs ${
                            d.status === 'READY'
                              ? 'bg-green-50 text-green-600'
                              : d.status === 'FAILED'
                                ? 'bg-red-50 text-red-500'
                                : 'bg-amber-50 text-amber-600'
                          }`}
                        >
                          {d.status}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-slate-700">{d.chunkCount}</td>
                      <td className="px-4 py-3 text-slate-600 whitespace-nowrap">
                        {effMax(d)}
                        {d.maxChunkChars != null && (
                          <span className="ml-1 text-xs text-blue-500">⚡</span>
                        )}
                        <span className="text-slate-400"> / {effSemantic(d) ? '语义' : '结构'}</span>
                      </td>
                      <td className="px-4 py-3 text-slate-700">{d.avgLen}</td>
                      <td className="px-4 py-3 text-slate-700">{d.tooShort}</td>
                      <td className="px-4 py-3 text-slate-700">{d.overlong}</td>
                      <td className="px-4 py-3 text-slate-700">{d.noHeading}</td>
                      <td className="px-4 py-3 text-slate-700">{d.duplicate}</td>
                      <td className="px-4 py-3 text-slate-700">{d.missingVector}</td>
                      <td className="px-4 py-3">
                        <button
                          onClick={() => openRechunk(d)}
                          className="px-2.5 py-1 rounded-lg bg-blue-50 text-blue-600 text-xs hover:bg-blue-100"
                        >
                          重新切片
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              page={docPaging.page}
              pageSize={docPaging.pageSize}
              total={docPaging.total}
              onPageChange={docPaging.setPage}
              onPageSizeChange={(s) => {
                docPaging.setPageSize(s)
                docPaging.setPage(1)
              }}
              className="border-t border-slate-100"
            />
          </div>
            </>
          )}

          {tab === 'eval' && (
            <>
          {/* RAG 评测 */}
          <div className="bg-white border border-slate-200 rounded-xl p-4 mb-5">
            <div className="flex flex-wrap items-center justify-between gap-3 mb-3">
              <h2 className="font-bold text-slate-800">🧪 RAG 检索/回答评测</h2>
              <div className="flex flex-wrap items-center gap-3">
                <label className="flex items-center gap-2 text-sm text-slate-600">
                  <input
                    type="checkbox"
                    className="accent-violet-600"
                    checked={evalProcessed}
                    onChange={(e) => setEvalProcessed(e.target.checked)}
                  />
                  查询处理（改写/多查询/HyDE/实体）
                </label>
                <label
                  className="flex items-center gap-2 text-sm text-slate-600"
                  title="关闭后仅算检索指标（Recall/MRR/NDCG），跳过回答生成与 LLM 裁判，几十秒跑完"
                >
                  <input
                    type="checkbox"
                    className="accent-violet-600"
                    checked={evalWithAnswer}
                    onChange={(e) => setEvalWithAnswer(e.target.checked)}
                  />
                  含回答打分（慢）
                </label>
                <button
                  onClick={runSingleEval}
                  disabled={evalCompare.running != null}
                  className="px-4 py-2 rounded-lg bg-violet-600 text-white text-sm font-medium hover:bg-violet-700 disabled:opacity-60"
                >
                  {evalCompare.running && evalCompare.running !== 'both'
                    ? '评测中（约 10 用例）…'
                    : '运行评测'}
                </button>
                <button
                  onClick={runEvalAB}
                  disabled={evalCompare.running != null}
                  className="px-4 py-2 rounded-lg border border-violet-300 text-violet-700 text-sm font-medium hover:bg-violet-50 disabled:opacity-60"
                >
                  {evalCompare.running === 'both' ? 'A/B 评测中…' : 'A/B 对比（原样 vs 查询处理）'}
                </button>
              </div>
            </div>
            {evalCompare.error && <div className="text-sm text-red-500 mb-2">⚠️ {evalCompare.error}</div>}
            {(evalCompare.off || evalCompare.on) && (
              <div>
                {evalCompare.off && evalCompare.on && (
                  <div className="text-xs text-slate-400 mb-2">
                    A/B：左=原样检索，右=查询处理管线，绿色=提升，红色=下降
                  </div>
                )}
                <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-3 mb-3">
                  {visibleEvalMetrics.map((m) => (
                    <div key={m.label} className="border border-slate-200 rounded-lg px-3 py-2">
                      <div className="text-xs text-slate-400">{m.label}</div>
                      {m.off != null && m.on != null ? (
                        <>
                          <div className="text-lg font-bold text-slate-800">
                            {m.off.toFixed(2)} → {m.on.toFixed(2)}
                          </div>
                          <div
                            className={`text-xs mt-0.5 ${m.on - m.off >= 0 ? 'text-green-600' : 'text-red-500'}`}
                          >
                            Δ {m.on - m.off >= 0 ? '+' : ''}
                            {(m.on - m.off).toFixed(2)}
                          </div>
                        </>
                      ) : (
                        <div className="text-xl font-bold text-slate-800">
                          {(m.on ?? m.off ?? 0).toFixed(2)}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead className="bg-slate-50 text-slate-600">
                      <tr>
                        <th className="text-left px-3 py-2 font-medium">问题</th>
                        {evalCompare.off && evalCompare.on ? (
                          <>
                            <th className="text-left px-3 py-2 font-medium">Recall（原样→处理）</th>
                            <th className="text-left px-3 py-2 font-medium">MRR（原样→处理）</th>
                            {evalWithAnswer && <th className="text-left px-3 py-2 font-medium">忠实度（原样→处理）</th>}
                          </>
                        ) : (
                          <>
                            <th className="text-left px-3 py-2 font-medium">Recall</th>
                            <th className="text-left px-3 py-2 font-medium">MRR</th>
                            {evalWithAnswer && <th className="text-left px-3 py-2 font-medium">忠实度</th>}
                          </>
                        )}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {casePaging.paged.map((c, localIdx) => {
                        const globalIdx = (casePaging.page - 1) * casePaging.pageSize + localIdx
                        const co = evalCompare.on?.cases[globalIdx]
                        return evalCompare.off && evalCompare.on ? (
                          <tr key={globalIdx} className="hover:bg-slate-50">
                            <td className="px-3 py-2 text-slate-700 max-w-[280px] truncate" title={c.question}>
                              {c.question}
                            </td>
                            <td className="px-3 py-2 text-slate-700">
                              {c.recall.toFixed(2)} → {co?.recall.toFixed(2)}
                            </td>
                            <td className="px-3 py-2 text-slate-700">
                              {c.mrr.toFixed(2)} → {co?.mrr.toFixed(2)}
                            </td>
                            {evalWithAnswer && (
                              <td className="px-3 py-2 text-slate-700">
                                {c.faithfulness} → {co?.faithfulness}
                              </td>
                            )}
                          </tr>
                        ) : (
                          <tr key={globalIdx} className="hover:bg-slate-50">
                            <td className="px-3 py-2 text-slate-700 max-w-[280px] truncate" title={c.question}>
                              {c.question}
                            </td>
                            <td className="px-3 py-2 text-slate-700">{c.recall.toFixed(2)}</td>
                            <td className="px-3 py-2 text-slate-700">{c.mrr.toFixed(2)}</td>
                            {evalWithAnswer && <td className="px-3 py-2 text-slate-700">{c.faithfulness}</td>}
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </div>
                <Pagination
                  page={casePaging.page}
                  pageSize={casePaging.pageSize}
                  total={casePaging.total}
                  onPageChange={casePaging.setPage}
                  onPageSizeChange={(s) => {
                    casePaging.setPageSize(s)
                    casePaging.setPage(1)
                  }}
                  className="border-t border-slate-100"
                />
              </div>
            )}
          </div>
            </>
          )}

          {tab === 'logs' && (
            <>
          {/* 真实查询日志（自动采集） */}
          <div className="bg-white border border-slate-200 rounded-xl overflow-hidden mb-5">
            <div className="flex items-center justify-between px-4 py-3 border-b border-slate-200">
              <h2 className="font-bold text-slate-800">📊 真实查询日志</h2>
              <div className="flex items-center gap-3">
                {logMsg && <span className="text-xs text-amber-600">{logMsg}</span>}
                <button
                  onClick={() => loadQueryLogs()}
                  disabled={logLoading}
                  className="text-xs px-3 py-1.5 rounded-lg bg-slate-100 hover:bg-slate-200 text-slate-600 disabled:opacity-60"
                >
                  {logLoading ? '加载中…' : '刷新'}
                </button>
              </div>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-slate-50 text-slate-600">
                  <tr>
                    <th className="text-left px-4 py-3 font-medium">问题</th>
                    <th className="text-left px-4 py-3 font-medium">意图</th>
                    <th className="text-left px-4 py-3 font-medium">改写查询</th>
                    <th className="text-left px-4 py-3 font-medium">来源</th>
                    <th className="text-left px-4 py-3 font-medium">耗时</th>
                    <th className="text-left px-4 py-3 font-medium">时间</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {queryLogs &&
                    queryLogs.records.map((r) => (
                      <Fragment key={r.id}>
                        <tr
                          onClick={() => setExpandedLog(expandedLog === r.id ? null : r.id)}
                          className={`hover:bg-slate-50 cursor-pointer transition-colors ${expandedLog === r.id ? 'bg-blue-50/40' : ''}`}
                          title="点击查看召回来源与回答"
                        >
                          <td className="px-4 py-3 max-w-[220px] truncate">{r.question}</td>
                          <td className="px-4 py-3">
                            <span
                              className={`px-2 py-0.5 rounded-full text-xs ${
                                r.gated ? 'bg-red-50 text-red-600' : 'bg-blue-50 text-blue-600'
                              }`}
                            >
                              {r.intent ?? '—'}
                              {r.gated ? ' ⛔' : ''}
                            </span>
                          </td>
                          <td
                            className="px-4 py-3 max-w-[200px] truncate text-slate-500"
                            title={r.rewrittenQuery ?? ''}
                          >
                            {r.rewrittenQuery || '—'}
                          </td>
                          <td className="px-4 py-3 text-slate-600">{sourcesCount(r)}</td>
                          <td className="px-4 py-3 text-slate-500">
                            {r.latencyMs != null ? `${r.latencyMs}ms` : '—'}
                          </td>
                          <td className="px-4 py-3 text-xs text-slate-400 whitespace-nowrap">
                            {formatTime(r.createdAt)}
                          </td>
                        </tr>
                        {expandedLog === r.id && (
                          <tr className="bg-slate-50/70">
                            <td colSpan={6} className="px-4 py-3">
                              {parseSources(r).length > 0 && (
                                <div className="mb-2">
                                  <div className="text-xs text-slate-400 mb-1">
                                    召回来源（{parseSources(r).length}）
                                  </div>
                                  <div className="space-y-1">
                                    {parseSources(r).map((s, i) => (
                                      <div key={i} className="text-xs text-slate-600">
                                        [{i + 1}] {s.filename}
                                        {s.score != null ? ` · 相关度 ${s.score.toFixed(2)}` : ''}
                                        {s.headingPath ? ` · 📑 ${s.headingPath}` : ''}
                                      </div>
                                    ))}
                                  </div>
                                </div>
                              )}
                              {r.answer && (
                                <div>
                                  <div className="text-xs text-slate-400 mb-1">AI 回答</div>
                                  <div className="text-xs text-slate-600 whitespace-pre-wrap line-clamp-4">
                                    {r.answer}
                                  </div>
                                </div>
                              )}
                            </td>
                          </tr>
                        )}
                      </Fragment>
                    ))}
                  {(!queryLogs || queryLogs.records.length === 0) && (
                    <tr>
                      <td colSpan={6} className="text-center py-8 text-slate-400">
                        暂无查询记录 —— 去「AI 助手」问一个问题，就会自动采集到这里
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            {queryLogs && queryLogs.total > 0 && (
              <Pagination
                page={queryLogs.current}
                pageSize={queryLogs.size}
                total={queryLogs.total}
                onPageChange={(p) => loadQueryLogs(p, logPageSize)}
                onPageSizeChange={(s) => {
                  setLogPageSize(s)
                  loadQueryLogs(1, s)
                }}
                className="border-t border-slate-100"
              />
            )}
          </div>
            </>
          )}
        </>
      )}

      {/* 重新切片弹窗 */}
      {rechunk && (
        <div
          className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4"
          onClick={() => !rechunk.saving && setRechunk(null)}
        >
          <div
            className="bg-white rounded-2xl shadow-xl w-full max-w-md"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="px-5 py-4 border-b border-slate-200">
              <h2 className="font-bold text-slate-800">重新切片：{rechunk.doc.filename}</h2>
            </div>
            <div className="px-5 py-4 space-y-3">
              <p className="text-xs text-slate-400">
                留空 = 使用全局默认；保存后从 MinIO 读取原文重新切分+向量化。
              </p>
              <label className="block">
                <span className="text-sm text-slate-600">单切片最大字符数（100~4000）</span>
                <input
                  className={`mt-1 ${inputCls}`}
                  type="number"
                  placeholder={`全局默认 ${settings?.maxChunkChars ?? ''}`}
                  value={rechunk.maxChars}
                  onChange={(e) => setRechunk({ ...rechunk, maxChars: e.target.value })}
                />
              </label>
              <label className="block">
                <span className="text-sm text-slate-600">重叠字符数（0~maxChunkChars）</span>
                <input
                  className={`mt-1 ${inputCls}`}
                  type="number"
                  placeholder={`全局默认 ${settings?.overlapChars ?? ''}`}
                  value={rechunk.overlap}
                  onChange={(e) => setRechunk({ ...rechunk, overlap: e.target.value })}
                />
              </label>
              <label className="block">
                <span className="text-sm text-slate-600">语义分片</span>
                <select
                  className={`mt-1 ${inputCls}`}
                  value={rechunk.semantic}
                  onChange={(e) =>
                    setRechunk({ ...rechunk, semantic: e.target.value as RechunkState['semantic'] })
                  }
                >
                  <option value="default">全局默认</option>
                  <option value="true">开启</option>
                  <option value="false">关闭</option>
                </select>
              </label>
              {rechunk.error && <div className="text-sm text-red-500">⚠️ {rechunk.error}</div>}
            </div>
            <div className="flex items-center justify-end gap-2 px-5 py-4 border-t border-slate-200">
              <button
                onClick={() => setRechunk(null)}
                disabled={rechunk.saving}
                className="px-3 py-1.5 rounded-lg text-slate-500 text-sm hover:bg-slate-50"
              >
                取消
              </button>
              <button
                onClick={confirmRechunk}
                disabled={rechunk.saving}
                className="px-3 py-1.5 rounded-lg bg-blue-600 text-white text-sm hover:bg-blue-700 disabled:opacity-60"
              >
                {rechunk.saving ? '切片中…' : '确认重新切片'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
