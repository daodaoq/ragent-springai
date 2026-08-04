import ReactMarkdown from 'react-markdown'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import 'katex/dist/katex.min.css'

/**
 * Markdown 渲染（P5 接入 KaTeX 数学公式：$行内$ / $$块级$$）
 */
export default function Markdown({ children }: { children: string }) {
  return (
    <div className="prose prose-slate max-w-none text-sm leading-6">
      <ReactMarkdown remarkPlugins={[remarkMath]} rehypePlugins={[rehypeKatex]}>
        {children}
      </ReactMarkdown>
    </div>
  )
}
