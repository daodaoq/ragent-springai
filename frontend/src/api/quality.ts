import http from './http'
import type { ApiResult } from '../types'

/** 全局切片参数（有效默认值） */
export interface ChunkSettings {
  maxChunkChars: number
  overlapChars: number
  semanticEnabled: boolean
}

/** 切片长度直方图桶 [start, end) */
export interface QualityBucket {
  start: number
  end: number
  count: number
}

/** 单文档质量明细 */
export interface DocQuality {
  docId: string
  filename: string
  status: string
  chunkCount: number
  maxChunkChars: number | null
  overlapChars: number | null
  semantic: boolean | null
  avgLen: number
  minLen: number
  maxLen: number
  tooShort: number
  overlong: number
  noHeading: number
  duplicate: number
  missingVector: number
  countMismatch: boolean
}

/** 聚合质量报告 */
export interface ChunkQualityReport {
  docCount: number
  totalChunks: number
  avgChunkLen: number
  tooShortCount: number
  overlongCount: number
  noHeadingCount: number
  duplicateCount: number
  missingVectorCount: number
  lengthBuckets: QualityBucket[]
  docs: DocQuality[]
}

export const getChunkSettings = () =>
  http.get('/kb/quality/settings') as Promise<ApiResult<ChunkSettings>>

export const updateChunkSettings = (s: ChunkSettings) =>
  http.put('/kb/quality/settings', s) as Promise<ApiResult<null>>

/** 质量报告：docId 缺省 = 全库聚合 */
export const getQualityReport = (docId?: string) =>
  http.get('/kb/quality/report', {
    params: docId ? { docId } : {},
  }) as Promise<ApiResult<ChunkQualityReport>>

// ---------------- 复用现有 RAG 评测（/api/eval/run） ----------------

export interface EvalRetrievalMetrics {
  recallAt5: number
  precisionAt5: number
  mrrAt5: number
  ndcgAt5: number
}

export interface EvalAnswerMetrics {
  avgFaithfulness: number
  avgRelevance: number
  citationRate: number
}

export interface EvalCaseResult {
  question: string
  expectedDocs: string[]
  recall: number
  precision: number
  mrr: number
  ndcg: number
  faithfulness: number
  relevance: number
  answer: string
}

export interface EvalReport {
  totalCases: number
  retrieval: EvalRetrievalMetrics
  answer: EvalAnswerMetrics
  cases: EvalCaseResult[]
}

/** 运行内置 RAG 评测（同步较慢：会真实检索+LLM 打分 10 个用例） */
export const runEval = () => http.post('/eval/run') as Promise<ApiResult<EvalReport>>
