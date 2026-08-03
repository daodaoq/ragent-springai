import ReactMarkdown from 'react-markdown'

/**
 * Markdown 渲染（P2 接入 KaTeX 数学公式后在此扩展）
 */
export default function Markdown({ children }: { children: string }) {
  return (
    <div className="prose prose-slate max-w-none text-sm leading-6">
      <ReactMarkdown>{children}</ReactMarkdown>
    </div>
  )
}
