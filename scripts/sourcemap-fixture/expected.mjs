// 빌드한 번들의 위치마다 source-map 라이브러리가 돌려준 원본 위치를 정답으로 적는다.
// 위치는 (1) 모든 매핑의 시작 열, (2) 그 사이의 열, (3) 줄 끝 너머를 섞어 고른다.
import { readdirSync, readFileSync, writeFileSync, mkdirSync, copyFileSync } from 'node:fs'
import { SourceMapConsumer } from 'source-map'

const dir = 'dist/assets'
const js = readdirSync(dir).find((f) => f.endsWith('.js'))
const raw = JSON.parse(readFileSync(`${dir}/${js}.map`, 'utf8'))
const code = readFileSync(`${dir}/${js}`, 'utf8').split('\n')

const positions = []
await SourceMapConsumer.with(raw, null, (consumer) => {
  const starts = []
  consumer.eachMapping((m) => starts.push([m.generatedLine, m.generatedColumn]))
  // 결정적으로 고른다. 매번 같은 정답이 나와야 한다.
  for (let i = 0; i < starts.length; i += 7) {
    const [line, column] = starts[i]
    positions.push([line, column], [line, column + 3])
  }
  code.forEach((text, i) => positions.push([i + 1, text.length + 5]))
  positions.push([1, 0])

  const answers = positions.map(([line, column]) => {
    const p = consumer.originalPositionFor({ line, column, bias: SourceMapConsumer.GREATEST_LOWER_BOUND })
    return { line, column, source: p.source, originalLine: p.line, originalColumn: p.column, name: p.name }
  })
  mkdirSync('../../core/src/test/resources/sourcemap', { recursive: true })
  copyFileSync(`${dir}/${js}.map`, '../../core/src/test/resources/sourcemap/bundle.js.map')
  writeFileSync('../../core/src/test/resources/sourcemap/expected.json', JSON.stringify({ bundle: js, answers }, null, 0) + '\n')
  console.log(`위치 ${answers.length}개, 그중 원본이 있는 것 ${answers.filter((a) => a.source).length}개`)
})
