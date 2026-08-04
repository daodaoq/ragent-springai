import { useEffect, useMemo, useRef, useState } from 'react'
import * as echarts from 'echarts'
import { useChart } from '../components/useChart'
import { getChunkSettings, getQualityReport, runEval, updateChunkSettings } from '../api/quality'
import type {
  ChunkQualityReport,
  ChunkSettings,
  DocQuality,
  EvalReport,
  QualityBucket,
} from '../api/quality'
import { rechunkDocument } from '../api/kb'
import type { ChunkParams } from '../api/kb'

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
  const [evalState, setEvalState] = useState<{ running: boolean; result: EvalReport | null; error: string }>({
    running: false,
    result: null,
    error: '',
  })

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

  const runRagEval = async () => {
    setEvalState({ running: true, result: null, error: '' })
    try {
      const res = await runEval()
      setEvalState({ running: false, result: res.data, error: '' })
    } catch (e) {
      setEvalState({ running: false, result: null, error: e instanceof Error ? e.message : '评测失败' })
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

  return (
    <div>
      <h1 className="text-lg font-bold text-slate-800 mb-4">🔍 切片质量评估</h1>
      {error && <div className="text-sm text-red-500 mb-3">⚠️ {error}</div>}
      {loading && <div className="text-sm text-slate-400 py-8">加载中…</div>}
      {!loading && report && (
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
                  {report.docs.map((d) => (
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
          </div>

          {/* RAG 评测 */}
          <div className="bg-white border border-slate-200 rounded-xl p-4 mb-5">
            <div className="flex items-center justify-between mb-3">
              <h2 className="font-bold text-slate-800">🧪 RAG 检索/回答评测</h2>
              <button
                onClick={runRagEval}
                disabled={evalState.running}
                className="px-4 py-2 rounded-lg bg-violet-600 text-white text-sm font-medium hover:bg-violet-700 disabled:opacity-60"
              >
                {evalState.running ? '评测中（约 10 用例）…' : '运行评测'}
              </button>
            </div>
            {evalState.error && <div className="text-sm text-red-500 mb-2">⚠️ {evalState.error}</div>}
            {evalState.result && (
              <div>
                <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3 mb-3">
                  {[
                    { label: 'Recall@5', v: evalState.result.retrieval.recallAt5 },
                    { label: 'MRR@5', v: evalState.result.retrieval.mrrAt5 },
                    { label: 'NDCG@5', v: evalState.result.retrieval.ndcgAt5 },
                    { label: '忠实度', v: evalState.result.answer.avgFaithfulness },
                    { label: '相关性', v: evalState.result.answer.avgRelevance },
                    { label: '引用率', v: evalState.result.answer.citationRate },
                  ].map((m) => (
                    <div key={m.label} className="border border-slate-200 rounded-lg px-3 py-2">
                      <div className="text-xs text-slate-400">{m.label}</div>
                      <div className="text-xl font-bold text-slate-800">{m.v}</div>
                    </div>
                  ))}
                </div>
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead className="bg-slate-50 text-slate-600">
                      <tr>
                        <th className="text-left px-3 py-2 font-medium">问题</th>
                        <th className="text-left px-3 py-2 font-medium">Recall</th>
                        <th className="text-left px-3 py-2 font-medium">MRR</th>
                        <th className="text-left px-3 py-2 font-medium">忠实度</th>
                        <th className="text-left px-3 py-2 font-medium">相关性</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {evalState.result.cases.map((c, i) => (
                        <tr key={i} className="hover:bg-slate-50">
                          <td className="px-3 py-2 text-slate-700 max-w-[280px] truncate" title={c.question}>
                            {c.question}
                          </td>
                          <td className="px-3 py-2 text-slate-700">{c.recall.toFixed(2)}</td>
                          <td className="px-3 py-2 text-slate-700">{c.mrr.toFixed(2)}</td>
                          <td className="px-3 py-2 text-slate-700">{c.faithfulness}</td>
                          <td className="px-3 py-2 text-slate-700">{c.relevance}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </div>
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
