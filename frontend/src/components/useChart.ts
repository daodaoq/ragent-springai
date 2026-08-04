import { useEffect } from 'react'
import type { RefObject } from 'react'
import * as echarts from 'echarts'

/**
 * ECharts 挂载 hook：React 19 StrictMode 双挂载下，cleanup 里 dispose 后重新 init，
 * 避免重复图表；监听 resize 自适应。从 DashboardPage 抽出共享给「切片质量」页。
 */
export function useChart<T>(
  ref: RefObject<HTMLDivElement | null>,
  data: T | undefined,
  build: (data: T) => echarts.EChartsOption,
) {
  useEffect(() => {
    if (!ref.current) return
    const chart = echarts.init(ref.current)
    if (data) chart.setOption(build(data))
    const onResize = () => chart.resize()
    window.addEventListener('resize', onResize)
    return () => {
      window.removeEventListener('resize', onResize)
      chart.dispose()
    }
  }, [ref, data, build])
}
