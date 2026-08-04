import { useEffect, useRef, useState } from 'react'
import Markdown from './Markdown'

interface MarkdownEditorProps {
  value: string
  onChange: (v: string) => void
  placeholder?: string
  /** textarea 最小高度（px），默认 140 */
  minHeight?: number
  disabled?: boolean
}

const btn =
  'inline-flex h-7 min-w-7 items-center justify-center rounded-md px-1.5 text-sm text-slate-600 transition-colors hover:bg-slate-100 hover:text-slate-900 disabled:opacity-40 disabled:cursor-not-allowed'

/**
 * 小型 Markdown 编辑器：工具栏（加粗/斜体/代码/标题/引用/列表/链接/图片/表格/分割线）
 * + 编辑/预览切换。选区感知：有选区则包裹，无选区则插入占位并全选；已包裹/已加前缀会反向解除（toggle）。
 */
export default function MarkdownEditor({
  value,
  onChange,
  placeholder,
  minHeight = 140,
  disabled,
}: MarkdownEditorProps) {
  const taRef = useRef<HTMLTextAreaElement>(null)
  /** 等 value 渲染后再恢复光标选区（工具栏点击后保持焦点不跳） */
  const pendingSel = useRef<{ start: number; end: number } | null>(null)
  const [mode, setMode] = useState<'edit' | 'preview'>('edit')

  useEffect(() => {
    const p = pendingSel.current
    if (p && taRef.current) {
      taRef.current.focus()
      taRef.current.setSelectionRange(p.start, p.end)
      pendingSel.current = null
    }
  }, [value])

  /** 核心：把 [s,e) 换成 text，记录待恢复选区 */
  const applyChange = (s: number, e: number, text: string, selStart: number, selEnd: number) => {
    pendingSel.current = { start: selStart, end: selEnd }
    onChange(value.slice(0, s) + text + value.slice(e))
  }

  /** 选中区包一层标记；已包裹则解开（toggle） */
  const wrap = (before: string, after: string, placeholderText: string) => {
    const ta = taRef.current
    if (!ta) return
    const s = ta.selectionStart
    const e = ta.selectionEnd
    const selected = value.slice(s, e)
    if (selected.startsWith(before) && selected.endsWith(after) && selected.length > before.length + after.length) {
      const inner = selected.slice(before.length, -after.length)
      applyChange(s, e, inner, s, s + inner.length)
      return
    }
    const content = selected || placeholderText
    applyChange(s, e, before + content + after, s + before.length, s + before.length + content.length)
  }

  /** 给选区所在每行加前缀；已全部带前缀则去掉（toggle） */
  const prefixLines = (prefix: string) => {
    const ta = taRef.current
    if (!ta) return
    const s = ta.selectionStart
    const e = ta.selectionEnd
    const lineStart = value.lastIndexOf('\n', s - 1) + 1
    const nl = value.indexOf('\n', e)
    const lineEnd = nl === -1 ? value.length : nl
    const block = value.slice(lineStart, lineEnd)
    const lines = block.split('\n')
    const allPrefixed = lines.length > 0 && lines.every((l) => l.startsWith(prefix))
    const out = allPrefixed
      ? lines.map((l) => l.slice(prefix.length)).join('\n')
      : lines.map((l) => prefix + l).join('\n')
    applyChange(lineStart, lineEnd, out, lineStart, lineStart + out.length)
  }

  /** 光标处插入固定文本 */
  const insertAt = (text: string, selStart?: number, selEnd?: number) => {
    const ta = taRef.current
    if (!ta) return
    const s = ta.selectionStart
    const e = ta.selectionEnd
    applyChange(s, e, text, selStart ?? s, selEnd ?? s + text.length)
  }

  const insertTable = () => {
    const t = '| 表头 | 表头 |\n| --- | --- |\n| 内容 | 内容 |'
    const ta = taRef.current
    if (!ta) return
    const s = ta.selectionStart
    applyChange(s, ta.selectionEnd, t, s + 2, s + 4) // 全选第一个「表头」方便直接改
  }

  const tools: { label: string; title: string; run: () => void }[] = [
    { label: 'B', title: '加粗 **加粗**', run: () => wrap('**', '**', '加粗文字') },
    { label: 'I', title: '斜体 *斜体*', run: () => wrap('*', '*', '斜体文字') },
    { label: 'S', title: '删除线 ~~删除~~', run: () => wrap('~~', '~~', '删除文字') },
    { label: '`', title: '行内代码 `code`', run: () => wrap('`', '`', 'code') },
    { label: '⚙', title: '代码块 ```', run: () => wrap('```\n', '\n```', '代码') },
    { label: 'H', title: '标题 ##', run: () => prefixLines('## ') },
    { label: '❯', title: '引用 >', run: () => prefixLines('> ') },
    { label: '•', title: '无序列表 - ', run: () => prefixLines('- ') },
    { label: '1.', title: '有序列表 1. ', run: () => prefixLines('1. ') },
    { label: '🔗', title: '链接 [文字](url)', run: () => wrap('[', '](https://)', '链接文字') },
    { label: '🖼', title: '图片 ![描述](url)', run: () => wrap('![', '](https://)', '图片描述') },
    { label: '⊞', title: '表格', run: () => insertTable() },
    { label: '—', title: '分割线', run: () => insertAt('\n\n---\n\n') },
  ]

  return (
    <div className="border border-slate-300 rounded-lg overflow-hidden focus-within:border-blue-500 focus-within:ring-2 focus-within:ring-blue-500/30">
      <div className="flex flex-wrap items-center gap-0.5 border-b border-slate-200 bg-slate-50 px-1.5 py-1">
        {tools.map((t) => (
          <button key={t.title} type="button" title={t.title} disabled={disabled} onClick={t.run} className={btn}>
            {t.label}
          </button>
        ))}
        <div className="mx-1 h-4 w-px bg-slate-200" />
        <div className="ml-auto flex items-center gap-0.5">
          <button
            type="button"
            disabled={disabled}
            onClick={() => setMode('edit')}
            className={`${btn} ${mode === 'edit' ? 'bg-blue-100 text-blue-700' : ''}`}
          >
            编辑
          </button>
          <button
            type="button"
            disabled={disabled}
            onClick={() => setMode('preview')}
            className={`${btn} ${mode === 'preview' ? 'bg-blue-100 text-blue-700' : ''}`}
          >
            预览
          </button>
        </div>
      </div>
      {mode === 'edit' ? (
        <textarea
          ref={taRef}
          className="w-full px-3 py-2 text-sm focus:outline-none disabled:bg-slate-50"
          style={{ minHeight }}
          placeholder={placeholder}
          value={value}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value)}
        />
      ) : (
        <div className="px-3 py-2 text-sm text-slate-700" style={{ minHeight }}>
          {value.trim() ? (
            <Markdown>{value}</Markdown>
          ) : (
            <span className="text-slate-400">（暂无内容，切回「编辑」输入）</span>
          )}
        </div>
      )}
    </div>
  )
}
